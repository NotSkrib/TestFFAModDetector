package xyz.nim.modDetectorPlugin.hackcheck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import xyz.nim.modDetectorPlugin.hackcheck.BedrockDetector;
import xyz.nim.modDetectorPlugin.hackcheck.HackDefinition;
import xyz.nim.modDetectorPlugin.hackcheck.SignProbe;

public final class HackCheckManager {
    // The global bypass node ("<node>" exempts a player entirely, "<node>.<id>" exempts one definition).
    // Both forms are looked up per player and per definition, so the name is built from this one constant
    // rather than repeated - a second hardcoded copy is how a permission rename ends up half-applied.
    private static final String BYPASS_NODE = "testffa.bypass";
    private final Plugin plugin;
    private final Map<UUID, CheckSession> activeChecks = new HashMap<UUID, CheckSession>();
    private final Set<UUID> pendingKick = new HashSet<UUID>();
    private final Random random = new Random();
    private List<HackDefinition> definitions = List.of();
    private Function<UUID, Set<String>> channelMods = uUID -> Set.of();
    private Function<String, String> displayResolver = string -> string;
    private Predicate<String> punishResolver = string -> true;
    private Function<String, List<String>> punishmentResolver = string -> List.of();
    private BiConsumer<Player, Set<String>> onResult = (player, set) -> {};
    private boolean kickEnabled = false;
    private String kickMessage = "&cUnauthorized modifications detected: <punishable>";
    private int timeoutTicks = 200;
    private int betweenBatchTicks = 0;
    private boolean debug = false;
    private boolean skipBedrock = true;
    private String bedrockNamePrefix = ".";

    public HackCheckManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void setDefinitions(List<HackDefinition> list) {
        this.definitions = list;
    }

    public void setChannelMods(Function<UUID, Set<String>> function) {
        this.channelMods = function;
    }

    public void setOnResult(BiConsumer<Player, Set<String>> biConsumer) {
        this.onResult = biConsumer;
    }

    public void setDisplayResolver(Function<String, String> function) {
        this.displayResolver = function;
    }

    public void setPunishResolver(Predicate<String> predicate) {
        this.punishResolver = predicate == null ? string -> true : predicate;
    }

    public void setPunishmentResolver(Function<String, List<String>> function) {
        this.punishmentResolver = function == null ? string -> List.of() : function;
    }

    public void handlePassiveResults(Player player, Set<String> set) {
        this.pendingKick.remove(player.getUniqueId());
        this.onResult.accept(player, set);
        String string = this.join(set);
        this.executePunishment(player, set, string, string);
    }

    private void executePunishment(Player player, Set<String> set, String string, String string2) {
        if (!this.kickEnabled || set.isEmpty()) {
            return;
        }
        ArrayList<String> arrayList = new ArrayList<String>();
        for (String modId : set) {
            if (!this.punishResolver.test(modId)) continue;
            arrayList.add(modId);
        }
        if (arrayList.isEmpty()) {
            return;
        }
        String string4 = this.join(set);
        String string32 = this.join(new LinkedHashSet<String>(arrayList));
        boolean bl = false;
        for (String string5 : arrayList) {
            if (!player.isOnline()) {
                return;
            }
            for (String string6 : this.punishmentResolver.apply(string5)) {
                if (!player.isOnline()) {
                    return;
                }
                bl = true;
                String string7 = ChatColor.translateAlternateColorCodes((char)'&', (String)string6.replace("%player%", player.getName()).replace("%punishable%", string32).replace("%detected%", string4).replace("%mods%", string2).replace("%hacks%", string));
                this.dbg("running custom punishment for " + string5 + ": " + string7);
                Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)string7);
                String string8 = string7.stripLeading();
                if (!HackCheckManager.startsWithIgnoreCase(string8, "kick") && !HackCheckManager.startsWithIgnoreCase(string8, "ban")) continue;
            }
        }
        if (bl) {
            return;
        }
        String string9 = this.kickMessage.replace("<punishable>", string32).replace("<detected>", string4).replace("<hacks>", string).replace("<mods>", string2);
        player.kick((Component)LegacyComponentSerializer.legacyAmpersand().deserialize(string9));
    }

    public void configure(boolean bl, String string, int n, int n2, boolean bl2, boolean skipBedrock, String bedrockNamePrefix) {
        this.kickEnabled = bl;
        this.kickMessage = string;
        this.timeoutTicks = Math.max(1, n);
        this.betweenBatchTicks = Math.max(0, n2);
        this.debug = bl2;
        this.skipBedrock = skipBedrock;
        this.bedrockNamePrefix = bedrockNamePrefix;
    }

    private void dbg(String string) {
        if (this.debug) {
            this.plugin.getLogger().info("[HackCheck] " + string);
        }
    }

    public boolean willHandleKick(UUID uUID) {
        return this.kickEnabled && (this.pendingKick.contains(uUID) || this.activeChecks.containsKey(uUID));
    }

    public void markPending(UUID uUID) {
        this.pendingKick.add(uUID);
    }

    public void clearPending(UUID uUID) {
        this.pendingKick.remove(uUID);
    }

    public boolean isChecking(UUID uUID) {
        return this.activeChecks.containsKey(uUID);
    }

    public void startCheck(Player player) {
        Object object;
        if (player.hasPermission(BYPASS_NODE)) {
            this.pendingKick.remove(player.getUniqueId());
            return;
        }
        if (this.activeChecks.containsKey(player.getUniqueId())) {
            return;
        }
        if (this.skipBedrock && BedrockDetector.isBedrock(player, this.bedrockNamePrefix)) {
            this.dbg("skipping active sign-probe for " + player.getName() + " (Bedrock/Floodgate)");
            this.finishCheck(player, Set.of());
            return;
        }
        ArrayList<HackDefinition> arrayList = new ArrayList<HackDefinition>();
        for (HackDefinition hackDefinition : this.definitions) {
            boolean bl;
            object = BYPASS_NODE + "." + hackDefinition.id();
            boolean bl2 = bl = player.isPermissionSet((String)object) && player.hasPermission((String)object);
            if (bl) continue;
            arrayList.add(hackDefinition);
        }
        if (!this.definitions.isEmpty() && arrayList.size() < this.definitions.size()) {
            int n = this.definitions.size() - arrayList.size();
            this.plugin.getLogger().warning("[HackCheck] " + n + "/" + this.definitions.size() + " hack definitions are bypassed for " + player.getName() + " via " + BYPASS_NODE + ".<id> permissions" + (arrayList.isEmpty() ? " - ALL of them, so the sign-probe will not run at all for this player." : "."));
        }
        if (arrayList.isEmpty()) {
            this.finishCheck(player, Set.of());
            return;
        }
        // Back to batches of 80/sign (20/line). Packing everything onto one sign blew past the real ~384-char
        // protocol cap per line (some real key strings run 40-60 chars) - anything past that point silently gets
        // truncated by the safe reader and reads back as "detected" for every player, hack or not. 20/line keeps
        // that from happening in the common case; the safe reader in HackCheckPacketListener still protects
        // against a crash on the rare line that overflows anyway, it just won't false-positive as often as 56/line did.
        ArrayDeque<List<HackDefinition>> arrayDeque = new ArrayDeque<List<HackDefinition>>();
        for (int i = 0; i < arrayList.size(); i += 80) {
            arrayDeque.add(arrayList.subList(i, Math.min(i + 80, arrayList.size())));
        }
        List<Location> list = SignProbe.findSignSpots(player, arrayDeque.size());
        if (list.isEmpty()) {
            this.dbg("no sign spot found for " + player.getName() + ", finishing check with 0 hacks");
            this.finishCheck(player, Set.of());
            return;
        }
        object = new CheckSession(player.getUniqueId(), arrayDeque);
        ((CheckSession)object).signSpots = list;
        this.activeChecks.put(player.getUniqueId(), (CheckSession)object);
        this.processBatch(player, (CheckSession)object);
    }

    public void cancelCheck(UUID uUID) {
        CheckSession checkSession = this.activeChecks.remove(uUID);
        if (checkSession != null) {
            this.endSession(checkSession);
        }
        this.pendingKick.remove(uUID);
    }

    public void shutdown() {
        for (CheckSession checkSession : new ArrayList<CheckSession>(this.activeChecks.values())) {
            this.endSession(checkSession);
        }
        this.activeChecks.clear();
        this.pendingKick.clear();
    }

    private void endSession(CheckSession checkSession) {
        if (checkSession.timeoutTask != null) {
            checkSession.timeoutTask.cancel();
        }
        this.restoreBlock(checkSession);
    }

    private void processBatch(Player player, CheckSession checkSession) {
        UUID uUID = player.getUniqueId();
        if (!player.isOnline() || checkSession.batches.isEmpty()) {
            this.activeChecks.remove(uUID);
            this.finishCheck(player, checkSession.detectedHacks);
            return;
        }
        List<HackDefinition> list = checkSession.batches.poll();
        ++checkSession.batchIndex;
        checkSession.awaitingReply = true;
        checkSession.signLoc = checkSession.signSpots.get(checkSession.batchIndex % checkSession.signSpots.size());
        ArrayList<List<Assignment>> lineAssignments = new ArrayList<List<Assignment>>();
        ArrayList<List<SignProbe.ProbeLine>> probeLines = new ArrayList<List<SignProbe.ProbeLine>>();
        for (int line = 0; line < 4; ++line) {
            int idx;
            ArrayList<Assignment> assignments = new ArrayList<Assignment>();
            ArrayList<SignProbe.ProbeLine> probes = new ArrayList<SignProbe.ProbeLine>();
            for (int k = 0; k < 20 && (idx = line * 20 + k) < list.size(); ++k) {
                HackDefinition hackDefinition = list.get(idx);
                String indicator = hackDefinition.mode() == HackDefinition.Mode.KEYBIND ? hackDefinition.key() : this.randomFallback();
                assignments.add(new Assignment(hackDefinition, indicator));
                probes.add(new SignProbe.ProbeLine(hackDefinition.mode(), hackDefinition.key(), indicator));
            }
            lineAssignments.add(assignments);
            probeLines.add(probes);
        }
        checkSession.lineAssignments = lineAssignments;
        if (this.debug) {
            StringBuilder stringBuilder = new StringBuilder("batch ").append(checkSession.batchIndex).append(" -> ").append(player.getName()).append(" @ ").append(checkSession.signLoc.getBlockX()).append(',').append(checkSession.signLoc.getBlockY()).append(',').append(checkSession.signLoc.getBlockZ()).append(" probing ");
            for (int i = 0; i < list.size(); ++i) {
                HackDefinition object = list.get(i);
                stringBuilder.append(i == 0 ? "[" : ", ").append(object.id()).append('(').append(object.mode().name().toLowerCase()).append(':').append(object.key()).append(object.required() ? ",trap" : "").append(')');
            }
            this.dbg(stringBuilder.append(']').toString());
        }
        SignProbe.openProbe(player, checkSession.signLoc, probeLines, this.plugin, this.debug);
        checkSession.timeoutTask = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            CheckSession checkSession2 = this.activeChecks.get(uUID);
            if (checkSession2 == null || checkSession2 != checkSession || !checkSession.awaitingReply) {
                return;
            }
            this.dbg("batch " + checkSession.batchIndex + " timed out for " + player.getName());
            this.advanceBatch(player, checkSession);
        }, (long)this.timeoutTicks);
    }

    public void handleSignResponse(Player player, Location location, String[] stringArray) {
        CheckSession checkSession = this.activeChecks.get(player.getUniqueId());
        if (checkSession == null || !checkSession.awaitingReply || checkSession.signLoc == null || !checkSession.signLoc.equals((Object)location)) {
            if (this.debug && checkSession != null) {
                this.dbg("dropped stray sign reply from " + player.getName() + " (stale batch or wrong position)");
            }
            return;
        }
        if (this.debug) {
            StringBuilder stringBuilder = new StringBuilder("reply from ").append(player.getName()).append(':');
            for (int i = 0; i < stringArray.length; ++i) {
                stringBuilder.append(" [").append(i).append("]='").append(stringArray[i]).append('\'');
            }
            this.dbg(stringBuilder.toString());
        }
        this.evaluateBatch(checkSession, stringArray);
        this.advanceBatch(player, checkSession);
    }

    private void evaluateBatch(CheckSession checkSession, String[] stringArray) {
        for (int line = 0; line < checkSession.lineAssignments.size() && line < 4; ++line) {
            String lineText = line < stringArray.length && stringArray[line] != null ? stringArray[line] : "";
            for (Assignment assignment : checkSession.lineAssignments.get(line)) {
                String expected;
                boolean bl;
                HackDefinition hackDefinition = assignment.definition;
                boolean containsIndicator = lineText.contains(assignment.indicator);
                if (hackDefinition.required()) {
                    bl = containsIndicator;
                    expected = "<must NOT contain trap marker '" + assignment.indicator + "'>";
                } else if (hackDefinition.mode() == HackDefinition.Mode.KEYBIND) {
                    bl = !containsIndicator;
                    expected = "<line must not contain raw id '" + hackDefinition.key() + "'>";
                } else {
                    bl = !containsIndicator;
                    expected = "<line must not contain fallback marker '" + assignment.indicator + "'>";
                }
                if (bl) {
                    checkSession.detectedHacks.add(hackDefinition.id());
                }
                if (!this.debug) continue;
                this.dbg("line " + line + " " + hackDefinition.id() + " (" + hackDefinition.mode().name().toLowerCase() + " '" + hackDefinition.key() + "', " + (hackDefinition.required() ? "trap" : "normal") + ") " + expected + " -> " + (bl ? "DETECTED" : "clean"));
            }
        }
    }

    private void advanceBatch(Player player, CheckSession checkSession) {
        if (!checkSession.awaitingReply) {
            return;
        }
        checkSession.awaitingReply = false;
        if (checkSession.timeoutTask != null) {
            checkSession.timeoutTask.cancel();
            checkSession.timeoutTask = null;
        }
        if (this.betweenBatchTicks > 0) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                Player onlinePlayer = Bukkit.getPlayer((UUID)checkSession.uuid);
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    this.processBatch(onlinePlayer, checkSession);
                } else {
                    this.activeChecks.remove(checkSession.uuid);
                }
            }, (long)this.betweenBatchTicks);
        } else {
            this.processBatch(player, checkSession);
        }
    }

    private void restoreBlock(CheckSession checkSession) {
        if (checkSession.signLoc == null) {
            return;
        }
        Player player = Bukkit.getPlayer((UUID)checkSession.uuid);
        if (player != null && player.isOnline()) {
            SignProbe.revert(player, checkSession.signLoc);
        }
    }

    private String randomFallback() {
        StringBuilder stringBuilder = new StringBuilder("___mdp___");
        for (int i = 0; i < 6; ++i) {
            stringBuilder.append((char)(97 + this.random.nextInt(26)));
        }
        return stringBuilder.toString();
    }

    private String join(Set<String> set) {
        LinkedHashSet<String> linkedHashSet = new LinkedHashSet<String>();
        for (String string : set) {
            linkedHashSet.add(this.displayResolver.apply(string));
        }
        return String.join((CharSequence)", ", linkedHashSet);
    }

    private static boolean startsWithIgnoreCase(String string, String string2) {
        return string.regionMatches(true, 0, string2, 0, string2.length());
    }

    private void finishCheck(Player player, Set<String> set) {
        UUID uUID = player.getUniqueId();
        HashSet<String> hashSet = new HashSet<String>(set);
        hashSet.addAll((Collection<String>)this.channelMods.apply(uUID));
        this.onResult.accept(player, hashSet);
        this.pendingKick.remove(uUID);
        String string = this.join(set);
        HashSet<String> hashSet2 = new HashSet<String>(hashSet);
        hashSet2.removeAll(set);
        String string2 = this.join(hashSet2);
        this.executePunishment(player, hashSet, string, string2);
    }

    private static final class CheckSession {
        final UUID uuid;
        final Deque<List<HackDefinition>> batches;
        final Set<String> detectedHacks = new HashSet<String>();
        int batchIndex = 0;
        List<List<Assignment>> lineAssignments = List.of();
        boolean awaitingReply = false;
        List<Location> signSpots = List.of();
        Location signLoc;
        BukkitTask timeoutTask;

        CheckSession(UUID uUID, Deque<List<HackDefinition>> deque) {
            this.uuid = uUID;
            this.batches = deque;
        }
    }

    private static final class Assignment {
        final HackDefinition definition;
        final String indicator;

        Assignment(HackDefinition definition, String indicator) {
            this.definition = definition;
            this.indicator = indicator;
        }
    }
}


