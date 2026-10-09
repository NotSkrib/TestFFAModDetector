package com.notskrib.moddetector;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

// Bukkit's scheduler throws UnsupportedOperationException on Folia. These two are in the Paper API
// and run on the main thread on plain Paper, Purpur and Leaf, so there is one code path and no
// platform branch. Folia forbids reading a player's world or location off their owning thread.
public final class Sched {
    private Sched() {
    }

    // A delay below 1 is raised to 1: Folia rejects zero, and runTaskLater(..., 0) already meant
    // "next tick". retired runs instead of task if the player leaves first, and may be null.
    public static ScheduledTask onPlayer(Plugin plugin, Player player, long delayTicks, Runnable task, Runnable retired) {
        ScheduledTask scheduled = player.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), retired, Math.max(1L, delayTicks));
        // The scheduler returns null without calling retired when the player is already gone at
        // scheduling time, so callers get one cleanup path rather than two.
        if (scheduled == null && retired != null) {
            retired.run();
        }
        return scheduled;
    }

    // Console commands belong on the global region.
    public static void onGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }
}
