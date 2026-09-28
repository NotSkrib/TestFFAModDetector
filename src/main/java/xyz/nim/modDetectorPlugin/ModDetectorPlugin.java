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
import xyz.nim.modDetectorPlugin.catalog.ModCatalog;
import xyz.nim.modDetectorPlugin.command.ModDetectorCommand;
import xyz.nim.modDetectorPlugin.hackcheck.HackCheckListener;
import xyz.nim.modDetectorPlugin.hackcheck.HackCheckManager;
import xyz.nim.modDetectorPlugin.hackcheck.HackCheckPacketListener;
import xyz.nim.modDetectorPlugin.hackcheck.HackDefinition;

public final class ModDetectorPlugin
extends JavaPlugin {
    private static final int DEFAULT_PASSIVE_DELAY_TICKS = 5;
    private static final int DEFAULT_TIMEOUT_TICKS = 40;
    private static final int DEFAULT_BETWEEN_BATCH_TICKS = 0;
    private final ModCatalog catalog = new ModCatalog();
    private HackCheckManager hackCheckManager;
    private HackCheckListener hackCheckListener;
    private List<HackDefinition> hackDefinitions = List.of();
    private boolean signProbeActive = false;
    private boolean kickEnabled = false;
    private int passiveDelayTicks = 5;
    private final Map<UUID, Set<String>> passiveResults = new HashMap<UUID, Set<String>>();
    private final Map<UUID, Set<String>> lastResult = new HashMap<UUID, Set<String>>();
    private final Map<UUID, CommandSender> manualCheckSenders = new HashMap<UUID, CommandSender>();

    public void onEnable() {
        this.saveDefaultConfig();
        this.hackCheckManager = new HackCheckManager((Plugin)this);
        this.hackCheckManager.setChannelMods(uUID -> this.passiveResults.getOrDefault(uUID, Set.of()));
        this.hackCheckManager.setDisplayResolver(this::displayFor);
        this.hackCheckManager.setPunishResolver(string -> {
            for (HackDefinition hackDefinition : this.hackDefinitions) {
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
        this.getLogger().info(Msg.BRAND + " enabled. Known mods: " + this.catalog.knownCount() + " | Hack definitions: " + this.hackDefinitions.size() + " | Sign-probe: " + (this.signProbeActive ? "active" : "disabled"));
    }

    public void onDisable() {
        if (this.hackCheckManager != null) {
            this.hackCheckManager.shutdown();
        }
    }

    public void reloadAll() {
        this.reloadConfig();
        this.passiveResults.clear();
        this.lastResult.clear();
        this.loadCatalog();
        this.loadHackDefinitions();
        boolean bl = this.getConfig().getBoolean("hack-checks.kick", false);
        String string = this.getConfig().getString("hack-checks.kick-message", "&cUnauthorized modifications detected: <punishable>");
        boolean bl2 = this.getConfig().getBoolean("hack-checks.sign-probe-debug", false);
        this.kickEnabled = bl;
        this.passiveDelayTicks = 5;
        boolean bl5 = this.getConfig().getBoolean("hack-checks.skip-bedrock", true);
        String string3 = this.getConfig().getString("hack-checks.bedrock-name-prefix", ".");
        this.hackCheckManager.configure(bl, string, 40, 0, bl2, bl5, string3);
        this.hackCheckManager.setDefinitions(this.signProbeActive ? this.hackDefinitions : List.of());
        boolean bl3 = this.getConfig().getBoolean("hack-checks.on-join.enabled", true);
        int n = this.getConfig().getInt("hack-checks.on-join.delay-ticks", 40);
        boolean bl4 = this.getConfig().getBoolean("hack-checks.on-join.only-first-join", false);
        this.hackCheckListener.configureOnJoin(bl3, n, bl4, this.passiveDelayTicks);
    }

    private void loadCatalog() {
        ConfigurationSection configurationSection = this.getConfig().getConfigurationSection("mods");
        ConfigurationSection configurationSection2 = this.getConfig().getConfigurationSection("custom-mods");
        List list = this.getConfig().getStringList("blocked-mods");
        boolean bl = "whitelist".equalsIgnoreCase(this.getConfig().getString("mode", "blacklist"));
        this.catalog.load(configurationSection, configurationSection2, list, bl);
    }

    private void loadHackDefinitions() {
        ArrayList<HackDefinition> arrayList = new ArrayList<HackDefinition>(this.catalog.activeDefinitions());
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
        this.hackDefinitions = this.dedupeHackDefinitions(arrayList);
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
        for (HackDefinition hackDefinition : this.hackDefinitions) {
            if (!hackDefinition.id().equals(string)) continue;
            return hackDefinition.display();
        }
        return this.catalog.displayName(string);
    }

    public Set<String> scanPassive(Player player) {
        HashSet<String> hashSet = new HashSet<String>(this.catalog.detect(player));
        this.passiveResults.put(player.getUniqueId(), hashSet);
        return hashSet;
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
        this.scanPassive(player);
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
        return this.hackDefinitions.size();
    }

    public int enabledHackCount() {
        return this.signProbeActive ? this.totalHackCount() : 0;
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
}


