package com.notskrib.moddetector.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.chat.SignedMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

// A signature is the one mod signal that is not a channel, brand or translation key, so hiding it
// means deliberately suppressing the chat protocol. OpSec does this under Signing Mode: OFF.
//
// Alert-only and off by default: unsigned chat has innocent causes (a server without
// enforce-secure-profile, a player who disabled signing, an old client), so punishing it is the
// operator's call after watching their own players.
public final class ChatSigningListener
implements Listener {
    // Consecutive, so one unsigned message amid signed ones is not a signal.
    private static final int MIN_UNSIGNED_MESSAGES = 5;
    private final Plugin plugin;
    private final Map<UUID, Integer> unsignedCounts = new ConcurrentHashMap<UUID, Integer>();
    private volatile boolean enabled;
    private volatile boolean punish;
    private volatile Consumer<Player> onUnsigned;

    public ChatSigningListener(Plugin plugin) {
        this.plugin = plugin;
    }

    public void configure(boolean enabled, boolean punish, Consumer<Player> onUnsigned) {
        this.enabled = enabled;
        this.punish = punish;
        this.onUnsigned = onUnsigned;
        this.unsignedCounts.clear();
    }

    public boolean enabled() {
        return this.enabled;
    }

    public boolean punish() {
        return this.punish;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!this.enabled) {
            return;
        }
        // A non-null SignedMessage can still be unsigned: signature() is separately nullable.
        SignedMessage signed = event.signedMessage();
        if (signed != null && signed.signature() != null) {
            return;
        }
        Player player = event.getPlayer();
        int count = this.unsignedCounts.merge(player.getUniqueId(), 1, Integer::sum);
        if (count != MIN_UNSIGNED_MESSAGES) {
            return;
        }
        try {
            this.onUnsigned.accept(player);
            if (this.punish) {
                player.kick(net.kyori.adventure.text.Component.text(
                        "Chat signing is required on this server.", net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        }
        catch (Throwable throwable) {
            this.plugin.getLogger().warning("[ChatSigning] handling unsigned chat failed: " + throwable);
        }
        finally {
            // Reset so this reports once per player, not once per message.
            this.unsignedCounts.remove(player.getUniqueId());
        }
    }

    public void forget(UUID uuid) {
        this.unsignedCounts.remove(uuid);
    }
}