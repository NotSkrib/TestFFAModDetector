package xyz.nim.modDetectorPlugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

// The Bukkit scheduler throws UnsupportedOperationException on Folia. The entity and global-region
// schedulers that replaced it are in the Paper API and run on the main thread on plain Paper, Purpur
// and Leaf, so there is one code path and no "is this Folia" branch.
//
// Rule: anything reading a player's world or location runs on that player's scheduler; anything
// dispatching a console command runs on the global region. Folia forbids the first from any other thread.
public final class Sched {
    private Sched() {
    }

    // Runs on the thread that owns the player. A delay below 1 is raised to 1: Folia rejects zero, and
    // runTaskLater(..., 0) already meant "next tick".
    //
    // retired runs instead of task if the player leaves first (quit, kick), and may be null. The
    // scheduler returns null WITHOUT calling retired when the player is already gone at scheduling
    // time, so that case is routed to retired here and callers get one cleanup path.
    public static ScheduledTask onPlayer(Plugin plugin, Player player, long delayTicks, Runnable task, Runnable retired) {
        ScheduledTask scheduled = player.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), retired, Math.max(1L, delayTicks));
        if (scheduled == null && retired != null) {
            retired.run();
        }
        return scheduled;
    }

    // Runs task on the global region, which is where console commands (kick/ban punishments) belong.
    public static void onGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }
}
