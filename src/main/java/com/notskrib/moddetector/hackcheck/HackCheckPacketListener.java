package com.notskrib.moddetector.hackcheck;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import com.notskrib.moddetector.Sched;

import java.nio.charset.StandardCharsets;

public final class HackCheckPacketListener
extends PacketListenerAbstract {
    private static final int MAX_LINE_CHARS = 384;
    // Sanity cap on the declared byte length before we trust it enough to read - well above anything a real
    // sign reply should ever need, just to stop a malformed/hostile length claim from reading garbage.
    private static final int MAX_DECLARED_BYTES = 65535;

    private final Plugin plugin;
    private final HackCheckManager manager;

    public HackCheckPacketListener(Plugin plugin, HackCheckManager hackCheckManager) {
        this.plugin = plugin;
        this.manager = hackCheckManager;
    }

    public static void registerIfAvailable(Plugin plugin, HackCheckManager hackCheckManager) throws Throwable {
        Class.forName("com.github.retrooper.packetevents.PacketEvents");
        PacketEvents.getAPI().getEventManager().registerListener((PacketListenerCommon) new HackCheckPacketListener(plugin, hackCheckManager));
        plugin.getLogger().info("[HackCheck] PacketEvents sign listener registered.");
    }

    public void onPacketReceive(PacketReceiveEvent packetReceiveEvent) {
        if (packetReceiveEvent.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) {
            return;
        }
        Object object = packetReceiveEvent.getPlayer();
        if (!(object instanceof Player)) {
            return;
        }
        Player player = (Player) object;
        if (!this.manager.isChecking(player.getUniqueId())) {
            return;
        }
        try {
            // The packet bytes must be consumed here, on the netty thread, before the buffer is
            // released. Everything that touches the player (getWorld included) waits for the hop to
            // the player's own scheduler below: this thread belongs to no region on Folia.
            RawUpdateSign sign = readUpdateSignSafely(packetReceiveEvent);
            packetReceiveEvent.setCancelled(true);
            Sched.onPlayer(this.plugin, player, 0, () -> {
                Location location = new Location(player.getWorld(), sign.position.getX(), sign.position.getY(), sign.position.getZ());
                this.manager.handleSignResponse(player, location, sign.lines);
            }, null);
        } catch (Throwable throwable) {
            this.plugin.getLogger().warning("[HackCheck] failed to read UPDATE_SIGN: " + throwable.getMessage());
        }
    }

    /**
     * Manual UPDATE_SIGN reader that can never desync the connection.
     *
     * PacketEvents' own WrapperPlayClientUpdateSign.read() calls readString(384) per line, which throws once a
     * line's DECODED length exceeds 384 chars. That throw aborts the 4-line read loop, leaving any remaining
     * lines' bytes un-consumed in the buffer - the next packet on the connection then gets misread starting
     * from those leftover bytes, desyncing the whole connection (client sees "Received unknown packet id" and
     * disconnects). This happened for real this session ("received string length is longer than maximum
     * allowed (389 > 384)" -> hard disconnect), at only 20 keys/line.
     *
     * We now pack every hack definition onto a single sign (see HackCheckManager.processBatch), which routinely
     * puts 50+ keys per line - oversized replies are the norm now, not a rare edge case. This reader
     * unconditionally consumes each line's full declared byte length regardless of how long it decodes to, then
     * just truncates the Java string afterward. No throw, no partial read, no desync - worst case we lose the
     * tail of an oversized line for detection purposes, which is fine since evaluateBatch only tests for the
     * presence/absence of specific markers, not the exact text.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RawUpdateSign readUpdateSignSafely(PacketReceiveEvent event) {
        PacketWrapper raw = new PacketWrapper(event, false) {};
        Object buffer = raw.buffer;
        Vector3i position = new Vector3i(raw.readLong(), raw.getServerVersion());
        if (raw.getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_20)) {
            raw.readBoolean();
        }
        String[] lines = new String[4];
        for (int i = 0; i < 4; ++i) {
            lines[i] = readSafeString(buffer);
        }
        return new RawUpdateSign(position, lines);
    }

    private static String readSafeString(Object buffer) {
        int declaredLen = ByteBufHelper.readVarInt(buffer);
        if (declaredLen < 0) {
            declaredLen = 0;
        }
        if (declaredLen > MAX_DECLARED_BYTES) {
            declaredLen = MAX_DECLARED_BYTES;
        }
        int available = ByteBufHelper.readableBytes(buffer);
        if (declaredLen > available) {
            declaredLen = available;
        }
        String s = ByteBufHelper.toString(buffer, ByteBufHelper.readerIndex(buffer), declaredLen, StandardCharsets.UTF_8);
        ByteBufHelper.readerIndex(buffer, ByteBufHelper.readerIndex(buffer) + declaredLen);
        if (s.length() > MAX_LINE_CHARS) {
            s = s.substring(0, MAX_LINE_CHARS);
        }
        return s;
    }

    private static final class RawUpdateSign {
        final Vector3i position;
        final String[] lines;

        RawUpdateSign(Vector3i position, String[] lines) {
            this.position = position;
            this.lines = lines;
        }
    }
}
