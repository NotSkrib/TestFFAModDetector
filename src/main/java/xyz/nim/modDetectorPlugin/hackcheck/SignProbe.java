
package xyz.nim.modDetectorPlugin.hackcheck;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import xyz.nim.modDetectorPlugin.hackcheck.HackDefinition;

public final class SignProbe {
    private static final int[][] HORIZONTAL_OFFSETS = new int[][]{{2, 0}, {0, 2}, {-2, 0}, {0, -2}, {2, 2}, {2, -2}, {-2, 2}, {-2, -2}, {3, 0}, {0, 3}, {-3, 0}, {0, -3}, {3, 2}, {3, -2}, {-3, 2}, {-3, -2}, {2, 3}, {-2, 3}, {2, -3}, {-2, -3}, {4, 0}, {0, 4}, {-4, 0}, {0, -4}};
    private static final int[] Y_OFFSETS = new int[]{0, 1, -1, 2, -2, 3};

    private SignProbe() {
    }

    public static void openProbe(Player player, Location location, List<List<ProbeLine>> probeLines, Plugin plugin, boolean bl) {
        Vector3i vector3i = SignProbe.toVector(location);
        PlayerManager playerManager = PacketEvents.getAPI().getPlayerManager();
        int n = WrappedBlockState.getDefaultState((StateType)StateTypes.OAK_SIGN).getGlobalId();
        NBTCompound nBTCompound = SignProbe.buildSignNBT(probeLines);
        if (bl) {
            plugin.getLogger().info("[HackCheck] fake sign NBT: " + String.valueOf(nBTCompound));
        }

        // No bundle. Sent individually, back to back. Order: place, data, open, CloseWindow(0), revert.
        playerManager.sendPacketSilently((Object)player, (PacketWrapper)new WrapperPlayServerBlockChange(vector3i, n));
        // SIGN is the standing sign (BlockEntityTypes.SIGN, registry id 7). Prefer the named constant over the
        // bare int: the int constructor is deprecated, and PacketEvents owns the per-version block-entity
        // mapping, so this stays correct if a future version ever shifts the registry. Hanging signs are a
        // different constant (HANGING_SIGN) and are not what this probe places - the block change above puts
        // a standing OAK_SIGN down.
        playerManager.sendPacketSilently((Object)player, (PacketWrapper)new WrapperPlayServerBlockEntityData(vector3i, BlockEntityTypes.SIGN, nBTCompound));
        playerManager.sendPacketSilently((Object)player, (PacketWrapper)new WrapperPlayServerOpenSignEditor(vector3i, true));
        playerManager.sendPacketSilently((Object)player, (PacketWrapper)new WrapperPlayServerCloseWindow(0));
        playerManager.sendPacketSilently((Object)player, (PacketWrapper)new WrapperPlayServerBlockChange(vector3i, 0));
    }

    public static void revert(Player player, Location location) {
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently((Object)player, (PacketWrapper)new WrapperPlayServerBlockChange(SignProbe.toVector(location), 0));
    }

    private static Vector3i toVector(Location location) {
        return new Vector3i(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static NBTCompound buildSignNBT(List<List<ProbeLine>> probeLines) {
        NBTCompound nBTCompound = new NBTCompound();
        nBTCompound.setTag("front_text", (NBT)SignProbe.buildTextSide(probeLines));
        nBTCompound.setTag("back_text", (NBT)SignProbe.buildTextSide(List.of()));
        nBTCompound.setTag("is_waxed", (NBT)new NBTByte(false));
        return nBTCompound;
    }

    private static NBTCompound buildTextSide(List<List<ProbeLine>> probeLines) {
        NBTCompound nBTCompound = new NBTCompound();
        NBTList nBTList = new NBTList(NBTType.COMPOUND);
        for (int i = 0; i < 4; ++i) {
            List<ProbeLine> lineItems = i < probeLines.size() ? probeLines.get(i) : List.of();
            nBTList.addTag((NBT)SignProbe.buildLine(lineItems));
        }
        nBTCompound.setTag("messages", (NBT)nBTList);
        nBTCompound.setTag("color", (NBT)new NBTString("black"));
        nBTCompound.setTag("has_glowing_text", (NBT)new NBTByte(false));
        return nBTCompound;
    }

    private static NBTCompound buildLine(List<ProbeLine> lineItems) {
        NBTCompound nBTCompound = new NBTCompound();
        nBTCompound.setTag("text", (NBT)new NBTString(""));
        if (!lineItems.isEmpty()) {
            NBTList nBTList = new NBTList(NBTType.COMPOUND);
            for (ProbeLine probeLine : lineItems) {
                nBTList.addTag((NBT)SignProbe.buildSignal(probeLine));
            }
            nBTCompound.setTag("extra", (NBT)nBTList);
        }
        return nBTCompound;
    }

    private static NBTCompound buildSignal(ProbeLine probeLine) {
        NBTCompound nBTCompound = new NBTCompound();
        if (probeLine.mode() == HackDefinition.Mode.KEYBIND) {
            nBTCompound.setTag("keybind", (NBT)new NBTString(probeLine.key()));
        } else {
            nBTCompound.setTag("translate", (NBT)new NBTString(probeLine.key()));
            nBTCompound.setTag("fallback", (NBT)new NBTString(probeLine.fallback()));
        }
        return nBTCompound;
    }

    public static List<Location> findSignSpots(Player player, int n) {
        World world = player.getWorld();
        Location location = player.getLocation();
        int n2 = location.getBlockX();
        int n3 = location.getBlockY();
        int n4 = location.getBlockZ();
        int n5 = world.getMinHeight() + 1;
        int n6 = world.getMaxHeight() - 1;
        ArrayList<Location> arrayList = new ArrayList<Location>();
        for (int[] nArray : HORIZONTAL_OFFSETS) {
            int n7 = n2 + nArray[0];
            int n8 = n4 + nArray[1];
            if (!world.isChunkLoaded(n7 >> 4, n8 >> 4)) continue;
            for (int n9 : Y_OFFSETS) {
                int n10 = n3 + n9;
                if (n10 < n5 || n10 > n6) continue;
                arrayList.add(new Location(world, (double)n7, (double)n10, (double)n8));
                if (arrayList.size() < n) continue;
                return arrayList;
            }
        }
        return arrayList;
    }

    public record ProbeLine(HackDefinition.Mode mode, String key, String fallback) {
    }
}

