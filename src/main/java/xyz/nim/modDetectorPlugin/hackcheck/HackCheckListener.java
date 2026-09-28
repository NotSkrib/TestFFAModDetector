package xyz.nim.modDetectorPlugin.hackcheck;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import xyz.nim.modDetectorPlugin.ModDetectorPlugin;
import xyz.nim.modDetectorPlugin.hackcheck.HackCheckManager;

public final class HackCheckListener
implements Listener {
    private final Plugin plugin;
    private final HackCheckManager manager;
    private final Set<UUID> firstJoinSeen = new HashSet<UUID>();
    private boolean onJoinEnabled;
    private int onJoinDelayTicks;
    private boolean onlyFirstJoin;
    private int passiveDelayTicks = 5;

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
        Player player = playerJoinEvent.getPlayer();
        boolean bl = !player.hasPlayedBefore();
        this.firstJoinSeen.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (!player.isOnline() || !this.firstJoinSeen.contains(player.getUniqueId())) {
                return;
            }
            Set<String> set = ((ModDetectorPlugin)this.plugin).scanPassive(player);
            if (set.isEmpty()) {
                return;
            }
            if (this.manager.willHandleKick(player.getUniqueId())) {
                return;
            }
            ((ModDetectorPlugin)this.plugin).handlePassiveResults(player, set);
        }, (long)this.passiveDelayTicks);
        if (!this.onJoinEnabled || this.onlyFirstJoin && !bl) {
            return;
        }
        this.manager.markPending(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (player.isOnline() && this.firstJoinSeen.contains(player.getUniqueId())) {
                this.manager.startCheck(player);
            }
        }, (long)this.onJoinDelayTicks);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent playerQuitEvent) {
        this.firstJoinSeen.remove(playerQuitEvent.getPlayer().getUniqueId());
        this.manager.cancelCheck(playerQuitEvent.getPlayer().getUniqueId());
        ((ModDetectorPlugin)this.plugin).clearCachedResults(playerQuitEvent.getPlayer().getUniqueId());
    }

    @EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
    public void onSignChange(SignChangeEvent signChangeEvent) {
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
}


