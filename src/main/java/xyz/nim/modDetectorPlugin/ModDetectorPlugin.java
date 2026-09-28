package xyz.nim.modDetectorPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.nim.modDetectorPlugin.Msg;
import xyz.nim.modDetectorPlugin.catalog.DetectionScope;
import xyz.nim.modDetectorPlugin.catalog.ModCatalog;
import xyz.nim.modDetectorPlugin.command.ModDetectorCommand;
import xyz.nim.modDetectorPlugin.hackcheck.HackCheckListener;
import xyz.nim.modDetectorPlugin.hackcheck.HackCheckManager;
import xyz.nim.modDetectorPlugin.hackcheck.HackCheckPacketListener;
import xyz.nim.modDetectorPlugin.hackcheck.HackCheckSettings;
import xyz.nim.modDetectorPlugin.hackcheck.HackDefinition;

public final class ModDetectorPlugin
extends JavaPlugin {
    private static final int DEFAULT_PASSIVE_DELAY_TICKS = 5;
    private static final int DEFAULT_TIMEOUT_TICKS = 40;
    private static final int DEFAULT_BETWEEN_BATCH_TICKS = 0;
    // A "detect:" key at column 0, with an optional inline flow list and an optional trailing
    // comment. Group 1 is the flow list; a non-empty one is a shape this editor does not write and
    // will not rewrite, so it is reported rather than silently reformatted.
    private static final Pattern DETECT_KEY = Pattern.compile("^detect:\\s*(\\[.*\\])?\\s*(#.*)?$");
    // Space-indented "- id" only. A tab-indented, deeper-indented or commented line is never consumed.
    private static final Pattern DETECT_ITEM = Pattern.compile("^[ ]+- (.+)$");
    private final ModCatalog catalog = new ModCatalog();
    private HackCheckManager hackCheckManager;
    private HackCheckListener hackCheckListener;
    private List<HackDefinition> hackDefinitionsPrimary = List.of();
    private List<HackDefinition> hackDefinitionsFull = List.of();
    private boolean signProbeActive = false;
    private boolean kickEnabled = false;
    private boolean escalateOnDetection = true;
    private int passiveDelayTicks = 5;
    // One entry per player per scope, so a tier-1 and a tier-2 passive scan of the same player do
    // not overwrite each other. EnumMap keeps the scope iteration order stable.
    private final Map<UUID, Map<DetectionScope, Set<String>>> passiveResults = new HashMap<UUID, Map<DetectionScope, Set<String>>>();
    private final Map<UUID, Set<String>> lastResult = new HashMap<UUID, Set<String>>();
    private final Map<UUID, CommandSender> manualCheckSenders = new HashMap<UUID, CommandSender>();

    public void onEnable() {
        try {
            this.enable();
        }
        catch (Throwable throwable) {
            // A bad config.yml must not leave a half-wired plugin registered: disable cleanly so the
            // server keeps running and the admin sees the stack trace in the console.
            this.getLogger().log(java.util.logging.Level.SEVERE, Msg.BRAND + " failed to enable; disabling.", throwable);
            this.getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enable() {
        this.saveDefaultConfig();
        this.hackCheckManager = new HackCheckManager((Plugin)this);
        this.hackCheckManager.setPassiveCache((uUID, detectionScope) -> this.passiveResultFor(uUID, detectionScope));
        this.hackCheckManager.setPassiveProbe((player, detectionScope) -> this.scanPassive(player, detectionScope));
        this.hackCheckManager.setDisplayResolver(this::displayFor);
        this.hackCheckManager.setPunishResolver(string -> {
            for (HackDefinition hackDefinition : this.hackDefinitionsFull) {
                if (!hackDefinition.id().equals(string)) continue;
                return hackDefinition.punish();
            }
            return this.catalog.shouldPunish((String)string);
        });
        this.hackCheckManager.setPunishmentResolver(this.catalog::punishmentsFor);
        this.hackCheckManager.setOnResult(this::handleResult);
        this.hackCheckListener = new HackCheckListener((Plugin)this, this.hackCheckManager);
        this.getServer().getPluginManager().registerEvents((Listener)this.hackCheckListener, (Plugin)this);
        try {
            HackCheckPacketListener.registerIfAvailable((Plugin)this, this.hackCheckManager);
            this.signProbeActive = true;
        }
        catch (Throwable throwable) {
            this.signProbeActive = false;
            this.getLogger().warning("Sign-probe disabled: PacketEvents not found or failed to hook (" + throwable.getMessage() + "). Channel-based passive detection still works; install PacketEvents for real hack-client detection.");
        }
        this.reloadAll();
        PluginCommand pluginCommand = this.getCommand("moddetector");
        if (pluginCommand != null) {
            ModDetectorCommand modDetectorCommand = new ModDetectorCommand(this);
            pluginCommand.setExecutor((CommandExecutor)modDetectorCommand);
            pluginCommand.setTabCompleter((TabCompleter)modDetectorCommand);
        }
        this.getLogger().info(Msg.BRAND + " enabled. Known mods: " + this.catalog.knownCount() + " | Detect list: " + this.catalog.tickedCount() + " ticked | Hack definitions: " + this.hackDefinitionsPrimary.size() + " tier 1 / " + this.hackDefinitionsFull.size() + " full | Sign-probe: " + (this.signProbeActive ? "active" : "disabled"));
    }

    public void onDisable() {
        try {
            if (this.hackCheckManager != null) {
                this.hackCheckManager.shutdown();
            }
        }
        catch (Throwable throwable) {
            this.getLogger().log(java.util.logging.Level.WARNING, Msg.BRAND + " threw while disabling; ignoring.", throwable);
        }
    }

    public void reloadAll() {
        this.reloadConfig();
        this.passiveResults.clear();
        this.lastResult.clear();
        this.loadCatalog();
        this.loadHackDefinitions();
        if (this.hackCheckManager != null) {
            // Any in-flight check keeps the definition list it started with, and finishes instead
            // of escalating. It is the same object in the same map slot, so nothing else has to know.
            this.hackCheckManager.setReloadBarrier();
        }
        boolean bl = this.getConfig().getBoolean("hack-checks.kick", false);
        String string = this.getConfig().getString("hack-checks.kick-message", "&cUnauthorized modifications detected: <punishable>");
        boolean bl2 = this.getConfig().getBoolean("hack-checks.sign-probe-debug", false);
        this.kickEnabled = bl;
        this.escalateOnDetection = this.getConfig().getBoolean("hack-checks.escalate", true);
        this.passiveDelayTicks = this.clamp(this.getConfig().getInt("hack-checks.passive-delay-ticks", ModDetectorPlugin.DEFAULT_PASSIVE_DELAY_TICKS), 0, Integer.MAX_VALUE);
        boolean bl5 = this.getConfig().getBoolean("hack-checks.skip-bedrock", true);
        String string3 = this.getConfig().getString("hack-checks.bedrock-name-prefix", ".");
        int n = this.clamp(this.getConfig().getInt("hack-checks.timeout-ticks", ModDetectorPlugin.DEFAULT_TIMEOUT_TICKS), 1, Integer.MAX_VALUE);
        int n2 = this.clamp(this.getConfig().getInt("hack-checks.between-batch-ticks", ModDetectorPlugin.DEFAULT_BETWEEN_BATCH_TICKS), 0, Integer.MAX_VALUE);
        int n3 = this.clamp(this.getConfig().getInt("hack-checks.batch-size", 80), 1, 80);
        int n4 = this.clamp(this.getConfig().getInt("hack-checks.keys-per-line", 20), 1, 20);
        this.hackCheckManager.configure(new HackCheckSettings(bl, string, n, n2, bl2, bl5, string3, this.escalateOnDetection, n3, n4));
        this.hackCheckManager.setDefinitions(this.signProbeActive ? this.hackDefinitionsPrimary : List.of(), this.signProbeActive ? this.hackDefinitionsFull : List.of());
        boolean bl3 = this.getConfig().getBoolean("hack-checks.on-join.enabled", true);
        int n5 = this.getConfig().getInt("hack-checks.on-join.delay-ticks", 40);
        boolean bl4 = this.getConfig().getBoolean("hack-checks.on-join.only-first-join", false);
        this.hackCheckListener.configureOnJoin(bl3, n5, bl4, this.passiveDelayTicks);
    }

    private static int clamp(int n, int n2, int n3) {
        return Math.max(n2, Math.min(n3, n));
    }

    private void loadCatalog() {
        ConfigurationSection configurationSection = this.getConfig().getConfigurationSection("mods");
        ConfigurationSection configurationSection2 = this.getConfig().getConfigurationSection("custom-mods");
        List<String> list = this.getConfig().getStringList("detect");
        if (list.isEmpty() && (this.getConfig().contains("mode") || this.getConfig().contains("blocked-mods"))) {
            // Load once un-ticked first: the legacy fallback below needs the parsed catalog (allIds).
            this.catalog.load(configurationSection, configurationSection2, List.of(), null);
            list = this.deriveLegacyDetectList();
        }
        this.catalog.load(configurationSection, configurationSection2, list, this.getLogger());
    }

    // Pre-5.0.0 installs carry mode: + blocked-mods: instead of detect:. Rather than rewriting the
    // admin's file (which would cost every comment), derive the ticked set in memory and say so loudly.
    // A blacklist has no faithful tick-off equivalent - the safe reading of it is "tick the mods with a
    // working punish chain", which is what the shipped default already is - so that is what it falls
    // back to. Getting this wrong in either direction only ever changes which tier a mod is probed in.
    private List<String> deriveLegacyDetectList() {
        java.util.logging.Logger logger = this.getLogger();
        String string = this.getConfig().getString("mode", "blacklist");
        List<String> list = this.getConfig().getStringList("blocked-mods");
        logger.warning("detect: is missing but the legacy mode: / blocked-mods: keys are present - this looks like a pre-5.0.0 config.yml. Nothing in this file has been rewritten.");
        if (!"whitelist".equalsIgnoreCase(string)) {
            logger.warning("Legacy mode: " + string + " over " + list.size() + " blocked mod(s) is a blacklist, which has no direct tick-off equivalent. Falling back to the shipped default (the mods with a working punish chain). Add ids to the detect: list to override.");
            return this.catalog.allIds();
        }
        java.util.LinkedHashSet<String> linkedHashSet = new java.util.LinkedHashSet<String>();
        for (String string2 : list) {
            if (string2 != null && !string2.isBlank()) {
                linkedHashSet.add(string2.trim().toLowerCase());
            }
        }
        if (linkedHashSet.isEmpty()) {
            logger.warning("Legacy mode: whitelist with an empty blocked-mods: list tracked nothing. Nothing is ticked for now - add ids to the detect: list.");
            return List.of();
        }
        logger.warning("Legacy mode: whitelist with " + linkedHashSet.size() + " blocked mod(s) - ticking those for this session. Move them into the detect: list to make it permanent.");
        return new ArrayList<String>(linkedHashSet);
    }

    private void loadHackDefinitions() {
        ArrayList<HackDefinition> arrayList = new ArrayList<HackDefinition>(this.catalog.activeDefinitions(DetectionScope.FULL));
        ConfigurationSection configurationSection = this.getConfig().getConfigurationSection("hacks");
        if (configurationSection != null) {
            for (String string : configurationSection.getKeys(false)) {
                HackDefinition.Mode mode;
                ConfigurationSection configurationSection2 = configurationSection.getConfigurationSection(string);
                if (configurationSection2 == null || !configurationSection2.getBoolean("enabled", true)) continue;
                String string2 = configurationSection2.getString("display-name", configurationSection2.getString("display", string));
                boolean bl = configurationSection2.getBoolean("punish", true);
                String string3 = configurationSection2.getString("key");
                if (string3 == null || string3.isBlank()) continue;
                try {
                    mode = HackDefinition.Mode.valueOf(configurationSection2.getString("mode", "TRANSLATE").toUpperCase());
                }
                catch (IllegalArgumentException illegalArgumentException) {
                    this.getLogger().warning("hacks: unknown mode for '" + string + "', defaulting to TRANSLATE");
                    mode = HackDefinition.Mode.TRANSLATE;
                }
                boolean bl2 = configurationSection2.getBoolean("required", false);
                arrayList.add(new HackDefinition(string, string2, mode, string3, bl, bl2));
            }
        }
        this.hackDefinitionsFull = this.dedupeHackDefinitions(arrayList);
        // Tier 1 = the detect: list. A hacks:-section id the catalog has never heard of is always
        // tier 1: it was written by hand on purpose, and making it escalation-only would mean it
        // could never be unticked - there would be nothing to un-tick.
        ArrayList<HackDefinition> arrayList2 = new ArrayList<HackDefinition>();
        for (HackDefinition hackDefinition : this.hackDefinitionsFull) {
            if (this.catalog.isTicked(hackDefinition.id()) || !this.catalog.knows(hackDefinition.id())) {
                arrayList2.add(hackDefinition);
            }
        }
        this.hackDefinitionsPrimary = this.dedupeHackDefinitions(arrayList2);
    }

    private List<HackDefinition> dedupeHackDefinitions(List<HackDefinition> list) {
        HashMap<String, HackDefinition> hashMap = new HashMap<String, HackDefinition>();
        for (HackDefinition hackDefinition : list) {
            String string = hackDefinition.id() + "\u0000" + String.valueOf((Object)hackDefinition.mode()) + "\u0000" + hackDefinition.key();
            hashMap.putIfAbsent(string, hackDefinition);
        }
        return new ArrayList<HackDefinition>(hashMap.values());
    }

    private void handleResult(Player player, Set<String> set) {
        this.lastResult.put(player.getUniqueId(), set);
        CommandSender commandSender = this.manualCheckSenders.remove(player.getUniqueId());
        if (set.isEmpty()) {
            if (commandSender != null) {
                commandSender.sendMessage(Msg.prefixed(((TextComponent)Component.text((String)"Check finished on ", (TextColor)NamedTextColor.GRAY).append((Component)Component.text((String)player.getName(), (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)" - no unauthorized modifications detected.", (TextColor)NamedTextColor.GREEN))));
            }
            return;
        }
        LinkedHashSet<String> linkedHashSet = new LinkedHashSet<String>();
        for (String string2 : set) {
            linkedHashSet.add(this.displayFor(string2));
        }
        String string = String.join((CharSequence)", ", linkedHashSet);
        if (commandSender != null) {
            commandSender.sendMessage(Msg.prefixed(((TextComponent)((TextComponent)Component.text((String)"Check finished on ", (TextColor)NamedTextColor.GRAY).append((Component)Component.text((String)player.getName(), (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)" - detected: ", (TextColor)NamedTextColor.RED))).append((Component)Component.text((String)string, (TextColor)NamedTextColor.YELLOW))));
        }
        if (this.getConfig().getBoolean("alert-staff", true)) {
            Component string2;
            string2 = Msg.prefixed(((TextComponent)Component.text((String)player.getName(), (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)" detected with: ", (TextColor)NamedTextColor.GRAY))).append((Component)Component.text((String)string, (TextColor)NamedTextColor.YELLOW)));
            for (Player player2 : Bukkit.getOnlinePlayers()) {
                if (!player2.hasPermission("testffa.alerts")) continue;
                player2.sendMessage((Component)string2);
            }
            this.getLogger().info(player.getName() + " detected with: " + string);
        }
        if (this.getConfig().getBoolean("detection-log.enabled", false)) {
            this.logDetection(player, set);
        }
    }

    private void logDetection(Player player, Set<String> set) {
        try {
            String string = this.getConfig().getString("detection-log.file", "detections.jsonl");
            Path path = Paths.get(this.getDataFolder().getAbsolutePath(), string);
            StringBuilder stringBuilder = new StringBuilder("{\"time\":").append(Instant.now().toEpochMilli()).append(",\"player\":\"").append(player.getName()).append("\"").append(",\"uuid\":\"").append(player.getUniqueId()).append("\"").append(",\"mods\":[");
            int n = 0;
            for (String string2 : set) {
                if (n++ > 0) {
                    stringBuilder.append(",");
                }
                stringBuilder.append("\"").append(string2).append("\"");
            }
            stringBuilder.append("]}\n");
            Files.write(path, stringBuilder.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Exception exception) {
            this.getLogger().warning("detection-log write failed: " + exception.getMessage());
        }
    }

    public String displayFor(String string) {
        for (HackDefinition hackDefinition : this.hackDefinitionsFull) {
            if (!hackDefinition.id().equals(string)) continue;
            return hackDefinition.display();
        }
        return this.catalog.displayName(string);
    }

    public Set<String> scanPassive(Player player, DetectionScope scope) {
        HashSet<String> hashSet = new HashSet<String>(this.catalog.detect(player, scope));
        this.passiveResults.computeIfAbsent(player.getUniqueId(), uUID -> new EnumMap<DetectionScope, Set<String>>(DetectionScope.class))
                .put(scope, hashSet);
        return hashSet;
    }

    public Set<String> passiveResultFor(UUID uUID, DetectionScope scope) {
        Map<DetectionScope, Set<String>> map = this.passiveResults.get(uUID);
        return map == null ? Set.of() : map.getOrDefault(scope, Set.of());
    }

    public void clearCachedResults(UUID uUID) {
        this.passiveResults.remove(uUID);
    }

    public void handlePassiveResults(Player player, Set<String> set) {
        this.hackCheckManager.handlePassiveResults(player, set);
    }

    public boolean manualCheck(Player player, CommandSender commandSender) {
        if (player.hasPermission("testffa.bypass")) {
            commandSender.sendMessage(Msg.prefixed(Component.text((String)player.getName(), (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)" has testffa.bypass - check skipped.", (TextColor)NamedTextColor.YELLOW))));
            return false;
        }
        if (this.hackCheckManager.isChecking(player.getUniqueId())) {
            commandSender.sendMessage(Msg.prefixed(((TextComponent)Component.text((String)"A check is already running on ", (TextColor)NamedTextColor.YELLOW).append((Component)Component.text((String)player.getName(), (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)" - wait for it to finish.", (TextColor)NamedTextColor.YELLOW))));
            return false;
        }
        this.manualCheckSenders.put(player.getUniqueId(), commandSender);
        this.hackCheckManager.markPending(player.getUniqueId());
        // Tier 1 only, for the same reason as the on-join path: the full catalog is the manager's
        // job (it escalates by itself), and a manual check must report the same thing an automatic
        // one does.
        this.scanPassive(player, DetectionScope.PRIMARY);
        this.hackCheckManager.startCheck(player);
        return true;
    }

    public ModCatalog catalog() {
        return this.catalog;
    }

    public boolean signProbeActive() {
        return this.signProbeActive;
    }

    public boolean kickEnabled() {
        return this.kickEnabled;
    }

    public int totalHackCount() {
        return this.hackDefinitionsFull.size();
    }

    public int primaryHackCount() {
        return this.hackDefinitionsPrimary.size();
    }

    public int enabledHackCount() {
        return this.signProbeActive ? this.totalHackCount() : 0;
    }

    public boolean escalateOnDetection() {
        return this.escalateOnDetection;
    }

    public Set<String> knownSignalIds() {
        // A hacks:-section id the catalog has never heard of is a valid input to /moddetector
        // detect|ignore: it is always tier 1, so it must be tickable and untickable like any other.
        LinkedHashSet<String> linkedHashSet = new LinkedHashSet<String>(this.catalog.allIds());
        for (HackDefinition hackDefinition : this.hackDefinitionsFull) {
            linkedHashSet.add(hackDefinition.id());
        }
        return linkedHashSet;
    }

    public Set<String> lastResultFor(UUID uUID) {
        return this.lastResult.get(uUID);
    }

    public UUID resolveHistoryUuid(String string) {
        Player player = Bukkit.getPlayerExact((String)string);
        if (player != null) {
            return player.getUniqueId();
        }
        for (UUID uUID : this.lastResult.keySet()) {
            String string2 = Bukkit.getOfflinePlayer((UUID)uUID).getName();
            if (string2 == null || !string2.equalsIgnoreCase(string)) continue;
            return uUID;
        }
        return null;
    }

    public List<String> knownHistoryNames() {
        ArrayList<String> arrayList = new ArrayList<String>();
        for (UUID uUID : this.lastResult.keySet()) {
            String string = Bukkit.getOfflinePlayer((UUID)uUID).getName();
            if (string == null) continue;
            arrayList.add(string);
        }
        return arrayList;
    }

    public PunishEditResult setPunish(String string, boolean bl) {
        int n;
        int n2;
        List<String> list;
        File file = new File(this.getDataFolder(), "config.yml");
        try {
            list = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        }
        catch (IOException iOException) {
            this.getLogger().warning("setPunish: failed reading config.yml: " + iOException.getMessage());
            return PunishEditResult.IO_ERROR;
        }
        int n3 = -1;
        for (int i = 0; i < list.size(); ++i) {
            if (!list.get(i).equals("mods:")) continue;
            n3 = i;
            break;
        }
        if (n3 == -1) {
            return PunishEditResult.NOT_FOUND;
        }
        Pattern pattern = Pattern.compile("^  " + Pattern.quote(string) + ":\\s*$");
        Pattern pattern2 = Pattern.compile("^  [A-Za-z0-9][A-Za-z0-9_-]*:\\s*$");
        Pattern pattern3 = Pattern.compile("^(\\s*punish:\\s*)(true|false)(.*)$");
        int n4 = -1;
        for (n2 = n3 + 1; n2 < list.size(); ++n2) {
            if (!pattern.matcher(list.get(n2)).matches()) continue;
            n4 = n2;
            break;
        }
        if (n4 == -1) {
            return PunishEditResult.NOT_FOUND;
        }
        n2 = list.size();
        for (n = n4 + 1; n < list.size(); ++n) {
            if (!pattern2.matcher(list.get(n)).matches()) continue;
            n2 = n;
            break;
        }
        for (n = n2; n > n4 + 1 && list.get(n - 1).isBlank(); --n) {
        }
        boolean bl2 = false;
        for (int i = n4 + 1; i < n2; ++i) {
            Matcher matcher = pattern3.matcher(list.get(i));
            if (!matcher.matches()) continue;
            list.set(i, matcher.group(1) + bl + matcher.group(3));
            bl2 = true;
            break;
        }
        if (!bl2) {
            list.add(n, "    punish: " + bl);
        }
        try {
            Files.write(file.toPath(), list, StandardCharsets.UTF_8, new OpenOption[0]);
        }
        catch (IOException iOException) {
            this.getLogger().warning("setPunish: failed writing config.yml: " + iOException.getMessage());
            return PunishEditResult.IO_ERROR;
        }
        this.reloadAll();
        return PunishEditResult.OK;
    }

    public static enum PunishEditResult {
        OK,
        NOT_FOUND,
        IO_ERROR;

    }

    // Line-edits the detect: block in place. The config is never round-tripped through
    // saveConfig(), because that rewrites every line and would destroy the hand-written comments -
    // including the reference block listing every available mod id, which is the whole reason the
    // tick-off list is usable without running a command.
    public DetectEditResult setTicked(String modId, boolean ticked) {
        int keyIndex;
        int endIndex;
        List<String> lines;
        File file = new File(this.getDataFolder(), "config.yml");
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        }
        catch (IOException iOException) {
            this.getLogger().warning("setTicked: failed reading config.yml: " + iOException.getMessage());
            return DetectEditResult.IO_ERROR;
        }
        Matcher matcher = ModDetectorPlugin.DETECT_KEY.matcher("");
        keyIndex = -1;
        int duplicateLine = -1;
        for (int i = 0; i < lines.size(); ++i) {
            matcher.reset(lines.get(i));
            if (!matcher.matches()) continue;
            if (keyIndex == -1) {
                keyIndex = i;
            }
            else {
                duplicateLine = i;
                break;
            }
        }
        if (keyIndex == -1) {
            return DetectEditResult.NOT_FOUND;
        }
        if (duplicateLine != -1) {
            this.getLogger().warning("setTicked: found a second 'detect:' key in config.yml at line " + (duplicateLine + 1) + " - editing the first one (line " + (keyIndex + 1) + "). Remove the duplicate.");
        }
        matcher.reset(lines.get(keyIndex));
        matcher.matches();
        String flow = matcher.group(1);
        if (flow != null && !flow.replace("[", "").replace("]", "").trim().isEmpty()) {
            // A non-empty inline list is valid YAML but not the block form this command edits, and
            // rewriting it into block form would silently reorder whatever the admin grouped.
            return DetectEditResult.UNSUPPORTED_SHAPE;
        }
        // Consume only "  - id" lines. The regex cannot match "#  # meteor" because '#' is not
        // whitespace, which is what makes the commented reference block structurally immune.
        for (endIndex = keyIndex + 1; endIndex < lines.size(); ++endIndex) {
            if (!ModDetectorPlugin.DETECT_ITEM.matcher(lines.get(endIndex)).matches()) break;
        }
        while (endIndex > keyIndex + 1 && lines.get(endIndex - 1).isBlank()) {
            --endIndex;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        for (int i = keyIndex + 1; i < endIndex; ++i) {
            Matcher itemMatcher = ModDetectorPlugin.DETECT_ITEM.matcher(lines.get(i));
            itemMatcher.matches();
            ids.add(itemMatcher.group(1).trim().toLowerCase());
        }
        boolean present = ids.contains(modId);
        if (ticked == present) {
            return ticked ? DetectEditResult.ALREADY_TICKED : DetectEditResult.NOT_TICKED;
        }
        int insertAt = endIndex;
        if (ticked) {
            lines.add(insertAt, "  - " + modId);
        }
        else {
            for (int i = keyIndex + 1; i < endIndex; ++i) {
                Matcher itemMatcher = ModDetectorPlugin.DETECT_ITEM.matcher(lines.get(i));
                if (itemMatcher.matches() && modId.equals(itemMatcher.group(1).trim().toLowerCase())) {
                    lines.remove(i);
                    break;
                }
            }
        }
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8, new OpenOption[0]);
        }
        catch (IOException iOException) {
            this.getLogger().warning("setTicked: failed writing config.yml: " + iOException.getMessage());
            return DetectEditResult.IO_ERROR;
        }
        this.reloadAll();
        return DetectEditResult.OK;
    }

    public static enum DetectEditResult {
        OK,
        ALREADY_TICKED,
        NOT_TICKED,
        UNSUPPORTED_SHAPE,
        NOT_FOUND,
        IO_ERROR;

    }
}


