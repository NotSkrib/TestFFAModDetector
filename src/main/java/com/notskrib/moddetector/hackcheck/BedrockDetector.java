package com.notskrib.moddetector.hackcheck;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.entity.Player;

final class BedrockDetector {
    private static volatile boolean floodgateChecked = false;
    private static Object floodgateApi = null;
    private static Method isFloodgatePlayerMethod = null;

    private BedrockDetector() {
    }

    static boolean isBedrock(Player player, String namePrefixFallback) {
        Boolean viaFloodgate = BedrockDetector.checkFloodgate(player);
        if (viaFloodgate != null) {
            return viaFloodgate;
        }
        return namePrefixFallback != null && !namePrefixFallback.isEmpty() && player.getName().startsWith(namePrefixFallback);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     * Converted monitor instructions to comments
     * Lifted jumps to return sites
     */
    private static Boolean checkFloodgate(Player player) {
        if (!floodgateChecked) {
            Class<BedrockDetector> clazz = BedrockDetector.class;
            // MONITORENTER : com.notskrib.moddetector.hackcheck.BedrockDetector.class
            if (!floodgateChecked) {
                try {
                    Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                    floodgateApi = apiClass.getMethod("getInstance", new Class[0]).invoke(null, new Object[0]);
                    isFloodgatePlayerMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                }
                catch (Throwable ignored) {
                    floodgateApi = null;
                    isFloodgatePlayerMethod = null;
                }
                floodgateChecked = true;
            }
            // MONITOREXIT : clazz
        }
        if (floodgateApi == null) return null;
        if (isFloodgatePlayerMethod == null) {
            return null;
        }
        try {
            return (Boolean)isFloodgatePlayerMethod.invoke(floodgateApi, player.getUniqueId());
        }
        catch (Throwable ignored) {
            return null;
        }
    }
}


