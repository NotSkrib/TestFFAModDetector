package com.notskrib.moddetector.hackcheck;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
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
import com.notskrib.moddetector.ModDetectorPlugin;
import com.notskrib.moddetector.Sched;
import com.notskrib.moddetector.catalog.DetectionScope;
import com.notskrib.moddetector.hackcheck.BedrockDetector;
import com.notskrib.moddetector.hackcheck.HackDefinition;
import com.notskrib.moddetector.hackcheck.HackCheckSettings;
import com.notskrib.moddetector.hackcheck.SignProbe;

public final class HackCheckManager {
    // Both bypass forms are looked up per definition, so the name is built here rather than repeated -
    // a second hardcoded copy is how a permission rename ends up half-applied.
    private static final String BYPASS_NODE = "testffa.bypass";
    private final Plugin plugin;
    // Concurrent: Folia checks different players on different threads. Each session is only ever
    // advanced from its own player's scheduler (see Sched), so the sessions need no lock.
    private final Map<UUID, CheckSession> activeChecks = new ConcurrentHashMap<UUID, CheckSession>();
    private final Set<UUID> pendingKick = ConcurrentHashMap.newKeySet();
    private final Random random = new Random();
    // Tier 2 only ever probes definitionsFull MINUS definitionsPrimary, so no definition is put on a
    // sign twice and the union of both passes is the old single full-catalog result.
    private volatile List<HackDefinition> definitionsPrimary = List.of();
    // Volatile: /md reload writes these on the command thread while every player's thread reads them.
    private volatile List<HackDefinition> definitionsFull = List.of();
    private BiFunction<UUID, DetectionScope, Set<String>> passiveCache = (uUID, scope) -> Set.of();
    private BiFunction<Player, DetectionScope, Set<String>> passiveProbe = (player, scope) -> Set.of();
    private Function<String, String> displayResolver = string -> string;
    private Predicate<String> punishResolver = string -> true;
    private Function<String, List<String>> punishmentResolver = string -> List.of();
    private BiConsumer<Player, Set<String>> onResult = (player, set) -> {};
    // Written by configure() on reload, read from every player's thread.
    private volatile boolean kickEnabled = false;
    private volatile String kickMessage = "&cUnauthorized modifications detected: <punishable>";
    private volatile int timeoutTicks = 200;
    private volatile int betweenBatchTicks = 0;
    private volatile boolean debug = false;
    private volatile boolean skipBedrock = true;
    private volatile String bedrockNamePrefix = ".";
    private volatile boolean escalate = true;
    // Cap is 4 sign lines x keysPerLine definitions; see the batching note in processBatch.
    private volatile int batchSize = 80;
    private volatile int keysPerLine = 20;

    public HackCheckManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void setDefinitions(List<HackDefinition> list, List<HackDefinition> list2) {
        this.definitionsPrimary = list;
        this.definitionsFull = list2;
    }

    public void setPassiveCache(BiFunction<UUID, DetectionScope, Set<String>> biFunction) {
        this.passiveCache = biFunction;
    }

    public void setPassiveProbe(BiFunction<Player, DetectionScope, Set<String>> biFunction) {
        this.passiveProbe = biFunction;
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

    public void handlePassiveResults(Player player, Set<String> tier1) {
        UUID uuid = player.getUniqueId();
        // Both guards precede the alert because a tier-1-only report is partial, and a partial report is
        // worse than none.
        if (player.hasPermission(BYPASS_NODE)) {
            this.pendingKick.remove(uuid);
            return;
        }
        if (this.skipBedrock && BedrockDetector.isBedrock(player, this.bedrockNamePrefix)) {
            this.pendingKick.remove(uuid);
            return;
        }
        HashSet<String> hashSet = new HashSet<String>(tier1);
        if (this.escalate && !tier1.isEmpty()) {
            // Re-reads server-side brand/channel state, so completing tier 2 costs no network traffic.
            hashSet.addAll(this.passiveProbe.apply(player, DetectionScope.FULL));
        }
        this.onResult.accept(player, hashSet);
        this.pendingKick.remove(uuid);
        this.executePunishment(player, hashSet, this.join(Set.of()), this.join(hashSet));
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
        // Placeholders are filled here, on the player's thread; only the dispatch has to leave it.
        ArrayList<PunishCommand> commands = new ArrayList<PunishCommand>();
        for (String string5 : arrayList) {
            for (String string6 : this.punishmentResolver.apply(string5)) {
                String string7 = ChatColor.translateAlternateColorCodes((char)'&', (String)string6.replace("%player%", player.getName()).replace("%punishable%", string32).replace("%detected%", string4).replace("%mods%", string2).replace("%hacks%", string));
                commands.add(new PunishCommand(string5, string7));
            }
        }
        if (!commands.isEmpty()) {
            // One task, in order, so the isOnline() guard still stops the rest once an earlier
            // command has kicked or banned the player.
            Sched.onGlobal(this.plugin, () -> {
                for (PunishCommand command : commands) {
                    if (!player.isOnline()) {
                        return;
                    }
                    this.dbg("running custom punishment for " + command.modId() + ": " + command.line());
                    Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)command.line());
                }
            });
            return;
        }
        String string9 = this.kickMessage.replace("<punishable>", string32).replace("<detected>", string4).replace("<hacks>", string).replace("<mods>", string2);
        player.kick((Component)LegacyComponentSerializer.legacyAmpersand().deserialize(string9));
    }

    public void configure(HackCheckSettings hackCheckSettings) {
        this.kickEnabled = hackCheckSettings.kick();
        this.kickMessage = hackCheckSettings.kickMessage();
        this.timeoutTicks = Math.max(1, hackCheckSettings.timeoutTicks());
        this.betweenBatchTicks = Math.max(0, hackCheckSettings.betweenBatchTicks());
        this.debug = hackCheckSettings.debug();
        this.skipBedrock = hackCheckSettings.skipBedrock();
        this.bedrockNamePrefix = hackCheckSettings.bedrockNamePrefix();
        this.escalate = hackCheckSettings.escalate();
        this.batchSize = Math.max(1, hackCheckSettings.batchSize());
        this.keysPerLine = Math.max(1, Math.min(20, hackCheckSettings.keysPerLine()));
    }

    public void setReloadBarrier() {
        for (CheckSession checkSession : this.activeChecks.values()) {
            // A pass 2 assembled from the new config would not match the config pass 1 ran under.
            checkSession.reloadBarrier = true;
        }
    }

    private void dbg(String string) {
        if (this.debug) {
            this.plugin.getLogger().info("[HackCheck] " + string);
        }
    }

    public boolean ownsResult(UUID uUID) {
        return this.pendingKick.contains(uUID) || this.activeChecks.containsKey(uUID);
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
        if (player.hasPermission(BYPASS_NODE)) {
            this.pendingKick.remove(player.getUniqueId());
            return;
        }
        if (this.activeChecks.containsKey(player.getUniqueId())) {
            return;
        }
        if (this.skipBedrock && BedrockDetector.isBedrock(player, this.bedrockNamePrefix)) {
            this.dbg("skipping active sign-probe for " + player.getName() + " (Bedrock/Floodgate)");
            // Reported explicitly empty: a Bedrock client cannot answer a sign probe, so any match
            // would be a false positive. Still releases a waiting /md check sender.
            this.pendingKick.remove(player.getUniqueId());
            this.onResult.accept(player, Set.of());
            return;
        }
        List<HackDefinition> arrayList = this.withoutBypassed(player, this.definitionsPrimary);
        if (arrayList.isEmpty()) {
            // Nothing active left to probe, but a client can still be identified by brand/channel alone.
            this.endPass(player, new CheckSession(player.getUniqueId(), DetectionScope.PRIMARY));
            return;
        }
        // Past the real ~384-char per-line cap anything is silently truncated by the safe reader and reads
        // back as "detected" for everyone. 20/line avoids that; both numbers are configurable now, and
        // raising either re-arms the failure mode.
        CheckSession checkSession = new CheckSession(player.getUniqueId(), DetectionScope.PRIMARY);
        checkSession.batches = this.batch(arrayList);
        this.beginPass(player, checkSession);
    }

    private void beginPass(Player player, CheckSession checkSession) {
        List<Location> list = SignProbe.findSignSpots(player, checkSession.batches.size());
        if (list.isEmpty()) {
            // No sign means no probe, so widen the merge instead: the passive picture is server-side and free.
            this.dbg("no sign spot found for " + player.getName() + ", finishing check with 0 hacks");
            this.activeChecks.remove(checkSession.uuid);
            checkSession.scope = DetectionScope.FULL;
            this.finishCheck(player, checkSession);
            return;
        }
        checkSession.signSpots = list;
        checkSession.batchIndex = 0;
        this.activeChecks.put(checkSession.uuid, checkSession);
        this.processBatch(player, checkSession);
    }

    private ArrayDeque<List<HackDefinition>> batch(List<HackDefinition> list) {
        ArrayDeque<List<HackDefinition>> arrayDeque = new ArrayDeque<List<HackDefinition>>();
        for (int i = 0; i < list.size(); i += this.batchSize) {
            arrayDeque.add(list.subList(i, Math.min(i + this.batchSize, list.size())));
        }
        return arrayDeque;
    }

    private List<HackDefinition> withoutBypassed(Player player, List<HackDefinition> list) {
        ArrayList<HackDefinition> arrayList = new ArrayList<HackDefinition>();
        for (HackDefinition hackDefinition : list) {
            if (this.isModBypassed(player, hackDefinition.id())) continue;
            arrayList.add(hackDefinition);
        }
        return arrayList;
    }

    // Applied to BOTH passes: filtering only pass 1 would let a bypassed ticked mod return through
    // escalation, making the permission do nothing.
    private boolean isModBypassed(Player player, String modId) {
        String string = BYPASS_NODE + "." + modId;
        return player.isPermissionSet(string) && player.hasPermission(string);
    }

    private void endPass(Player player, CheckSession checkSession) {
        if (checkSession.awaitingReply && checkSession.timeoutTask != null) {
            checkSession.timeoutTask.cancel();
            checkSession.timeoutTask = null;
        }
        checkSession.awaitingReply = false;
        this.restoreBlock(checkSession);
        List<HackDefinition> arrayList = this.escalationDefinitions(player, checkSession);
        if (arrayList.isEmpty()) {
            this.finishCheck(player, checkSession);
            return;
        }
        this.dbg("tier 1 hit for " + player.getName() + " - escalating to the full catalog (" + arrayList.size() + " more definitions)");
        checkSession.scope = DetectionScope.FULL;
        ++checkSession.passIndex;
        checkSession.coveredKeys.addAll(HackCheckManager.definitionKeys(arrayList));
        checkSession.batches = this.batch(arrayList);
        this.beginPass(player, checkSession);
    }

    private List<HackDefinition> escalationDefinitions(Player player, CheckSession checkSession) {
        this.warnIfBypassed(player);
        if (!this.escalate || checkSession.scope != DetectionScope.PRIMARY || checkSession.reloadBarrier) {
            return List.of();
        }
        HashSet<String> hashSet = new HashSet<String>(checkSession.detectedHacks);
        hashSet.addAll(this.passiveCache.apply(checkSession.uuid, DetectionScope.PRIMARY));
        if (hashSet.isEmpty()) {
            return List.of();
        }
        // Subtract on the dedupe key, so pass 1 can never be probed twice regardless of config changes.
        ArrayList<HackDefinition> arrayList = new ArrayList<HackDefinition>();
        for (HackDefinition hackDefinition : this.definitionsFull) {
            if (checkSession.coveredKeys.contains(HackCheckManager.definitionKey(hackDefinition))) continue;
            if (this.isModBypassed(player, hackDefinition.id())) continue;
            arrayList.add(hackDefinition);
        }
        return arrayList;
    }

    // Counted over both passes, emitted once, so an admin sees one line per check.
    private void warnIfBypassed(Player player) {
        if (this.definitionsFull.isEmpty()) {
            return;
        }
        int bypassed = 0;
        for (HackDefinition hackDefinition : this.definitionsFull) {
            if (this.isModBypassed(player, hackDefinition.id())) {
                ++bypassed;
            }
        }
        if (bypassed == 0) {
            return;
        }
        this.plugin.getLogger().warning("[HackCheck] " + bypassed + "/" + this.definitionsFull.size() + " hack definitions are bypassed for " + player.getName() + " via " + BYPASS_NODE + ".<id> permissions"
                + (bypassed == this.definitionsFull.size() ? " - ALL of them, so the sign-probe will not run at all for this player." : "."));
    }

    private static Set<String> definitionKeys(List<HackDefinition> list) {
        LinkedHashSet<String> linkedHashSet = new LinkedHashSet<String>();
        for (HackDefinition hackDefinition : list) {
            linkedHashSet.add(HackCheckManager.definitionKey(hackDefinition));
        }
        return linkedHashSet;
    }

    // Must stay identical to dedupeHackDefinitions()'s key, or the two passes stop being a partition.
    private static String definitionKey(HackDefinition hackDefinition) {
        return hackDefinition.id() + "\u0000" + String.valueOf((Object)hackDefinition.mode()) + "\u0000" + hackDefinition.key();
    }

    public void cancelCheck(UUID uUID) {
        CheckSession checkSession = this.activeChecks.remove(uUID);
        if (checkSession != null) {
            this.endSession(checkSession);
        }
        this.pendingKick.remove(uUID);
        // A cancelled check never produces a result, so the waiting sender is told instead of left
        // watching a line that will never be answered.
        if (this.plugin instanceof ModDetectorPlugin) {
            ((ModDetectorPlugin)this.plugin).notifyCheckCancelled(uUID, "the player left before the check finished");
        }
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
        if (!player.isOnline()) {
            this.activeChecks.remove(uUID);
            return;
        }
        if (checkSession.batches.isEmpty()) {
            this.activeChecks.remove(uUID);
            this.endPass(player, checkSession);
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
            for (int k = 0; k < this.keysPerLine && (idx = line * this.keysPerLine + k) < list.size(); ++k) {
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
            StringBuilder stringBuilder = new StringBuilder("pass ").append(checkSession.passIndex).append(" batch ").append(checkSession.batchIndex).append(" -> ").append(player.getName()).append(" @ ").append(checkSession.signLoc.getBlockX()).append(',').append(checkSession.signLoc.getBlockY()).append(',').append(checkSession.signLoc.getBlockZ()).append(" probing ");
            for (int i = 0; i < list.size(); ++i) {
                HackDefinition object = list.get(i);
                stringBuilder.append(i == 0 ? "[" : ", ").append(object.id()).append('(').append(object.mode().name().toLowerCase()).append(':').append(object.key()).append(object.required() ? ",trap" : "").append(')');
            }
            this.dbg(stringBuilder.append(']').toString());
        }
        SignProbe.openProbe(player, checkSession.signLoc, probeLines, this.plugin, this.debug);
        // Not the global scheduler: advanceBatch opens the next sign, which reads the player's world.
        checkSession.timeoutTask = Sched.onPlayer(this.plugin, player, this.timeoutTicks, () -> {
            CheckSession checkSession2 = this.activeChecks.get(uUID);
            if (checkSession2 == null || checkSession2 != checkSession || !checkSession.awaitingReply) {
                return;
            }
            this.dbg("batch " + checkSession.batchIndex + " timed out for " + player.getName());
            this.advanceBatch(player, checkSession);
        }, () -> this.activeChecks.remove(uUID, checkSession));
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
            Sched.onPlayer(this.plugin, player, this.betweenBatchTicks, () -> {
                Player onlinePlayer = Bukkit.getPlayer((UUID)checkSession.uuid);
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    this.processBatch(onlinePlayer, checkSession);
                } else {
                    this.activeChecks.remove(checkSession.uuid);
                }
            }, () -> this.activeChecks.remove(checkSession.uuid, checkSession));
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

    private void finishCheck(Player player, CheckSession checkSession) {
        UUID uUID = player.getUniqueId();
        // Probe rather than read the cache: the scheduled passive task may never have run, and a
        // partial report defeats the point of escalating.
        HashSet<String> hashSet = new HashSet<String>(checkSession.detectedHacks);
        hashSet.addAll(this.passiveProbe.apply(player, DetectionScope.PRIMARY));
        if (checkSession.scope == DetectionScope.FULL) {
            hashSet.addAll(this.passiveProbe.apply(player, DetectionScope.FULL));
        }
        this.onResult.accept(player, hashSet);
        this.pendingKick.remove(uUID);
        HashSet<String> hashSet2 = new HashSet<String>(hashSet);
        hashSet2.removeAll(checkSession.detectedHacks);
        this.executePunishment(player, hashSet, this.join(checkSession.detectedHacks), this.join(hashSet2));
    }

    private static final class CheckSession {
        final UUID uuid;
        final Set<String> detectedHacks = new HashSet<String>();
        final Set<String> coveredKeys = new LinkedHashSet<String>();
        DetectionScope scope = DetectionScope.PRIMARY;
        Deque<List<HackDefinition>> batches = new ArrayDeque<List<HackDefinition>>();
        // Set by setReloadBarrier() on the reload thread, read on the player's.
        volatile boolean reloadBarrier = false;
        int batchIndex = 0;
        int passIndex = 1;
        List<List<Assignment>> lineAssignments = List.of();
        // shutdown() ends sessions from the disabling thread.
        volatile boolean awaitingReply = false;
        List<Location> signSpots = List.of();
        Location signLoc;
        volatile ScheduledTask timeoutTask;

        CheckSession(UUID uUID, DetectionScope detectionScope) {
            this.uuid = uUID;
            this.scope = detectionScope;
        }
    }

    private record PunishCommand(String modId, String line) {
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


