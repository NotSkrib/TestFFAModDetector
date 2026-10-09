package xyz.nim.modDetectorPlugin.hackcheck;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import xyz.nim.modDetectorPlugin.ModDetectorPlugin;
import xyz.nim.modDetectorPlugin.Sched;
import xyz.nim.modDetectorPlugin.catalog.DetectionScope;
import xyz.nim.modDetectorPlugin.hackcheck.HackCheckManager;

public final class HackCheckListener
implements Listener {
    private final Plugin plugin;
    private final HackCheckManager manager;
    private final Set<UUID> firstJoinSeen = ConcurrentHashMap.newKeySet();
    // configureOnJoin runs on the reload thread; the join handler reads these on a player's region thread.
    private volatile boolean onJoinEnabled;
    private volatile int onJoinDelayTicks;
    private volatile boolean onlyFirstJoin;
    private volatile int passiveDelayTicks = 5;

    public HackCheckListener(Plugin plugin, HackCheckManager hackCheckManager) {
        this.plugin = plugin;
        this.manager = hackCheckManager;
    }

    public void configureOnJoin(boolean bl, int n, boolean bl2, int n2) {
        this.onJoinEnabled = bl;
        this.onJoinDelayTicks = Math.max(0, n);
        this.onlyFirstJoin = bl2;
        this.passiveDelayTicks = Math.max(0, n2);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent playerJoinEvent) {
        try {
            this.handleJoin(playerJoinEvent.getPlayer());
        }
        catch (Throwable throwable) {
            this.plugin.getLogger().warning("[HackCheck] onJoin failed for " + playerJoinEvent.getPlayer().getName() + ": " + throwable);
        }
    }

    private void handleJoin(Player player) {
        // Read on the player's region thread, which is where PlayerJoinEvent is dispatched anyway.
        boolean bl = !player.hasPlayedBefore();
        this.firstJoinSeen.add(player.getUniqueId());
        Sched.onPlayer(this.plugin, player, this.passiveDelayTicks, () -> this.runPassive(player), null);
        if (!this.onJoinEnabled || this.onlyFirstJoin && !bl) {
            return;
        }
        // markPending must run before the delayed startCheck: it is what tells the passive task
        // that an active pipeline already owns this player's result.
        this.manager.markPending(player.getUniqueId());
        // Both tasks run on the player's own scheduler: startCheck reads the player's world and
        // location to pick sign spots, which only the owning region thread may do on Folia.
        Sched.onPlayer(this.plugin, player, this.onJoinDelayTicks, () -> {
            if (player.isOnline() && this.firstJoinSeen.contains(player.getUniqueId())) {
                this.manager.startCheck(player);
            }
        }, () -> this.manager.clearPending(player.getUniqueId()));
    }

    private void runPassive(Player player) {
        try {
            if (!player.isOnline() || !this.firstJoinSeen.contains(player.getUniqueId())) {
                return;
            }
            // Tier 1 only. This single argument is the whole design: if the passive scan read the
            // full catalog, every vanilla/Fabric client would trip on its own brand string and
            // escalate on every join, which is the old behaviour with extra steps.
            Set<String> set = ((ModDetectorPlugin)this.plugin).scanPassive(player, DetectionScope.PRIMARY);
            if (set.isEmpty()) {
                return;
            }
            if (this.manager.ownsResult(player.getUniqueId())) {
                return;
            }
            ((ModDetectorPlugin)this.plugin).handlePassiveResults(player, set);
        }
        catch (Throwable throwable) {
            this.plugin.getLogger().warning("[HackCheck] passive scan failed for " + player.getName() + ": " + throwable);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent playerQuitEvent) {
        try {
            this.firstJoinSeen.remove(playerQuitEvent.getPlayer().getUniqueId());
            this.manager.cancelCheck(playerQuitEvent.getPlayer().getUniqueId());
            ((ModDetectorPlugin)this.plugin).clearCachedResults(playerQuitEvent.getPlayer().getUniqueId());
        }
        catch (Throwable throwable) {
            this.plugin.getLogger().warning("[HackCheck] onQuit failed for " + playerQuitEvent.getPlayer().getName() + ": " + throwable);
        }
    }

    @EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
    public void onSignChange(SignChangeEvent signChangeEvent) {
        try {
            Player player = signChangeEvent.getPlayer();
            if (!this.manager.isChecking(player.getUniqueId())) {
                return;
            }
            String[] stringArray = new String[4];
            for (int i = 0; i < 4; ++i) {
                Component component = signChangeEvent.line(i);
                stringArray[i] = component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
            }
            this.manager.handleSignResponse(player, signChangeEvent.getBlock().getLocation(), stringArray);
        }
        catch (Throwable throwable) {
            this.plugin.getLogger().warning("[HackCheck] onSignChange failed: " + throwable);
        }
    }
}


