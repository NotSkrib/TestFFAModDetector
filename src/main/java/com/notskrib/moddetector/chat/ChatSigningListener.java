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

/**
 * Catches clients that strip chat signatures, which is the one server-observable trace left by
 * anti-detection mods that otherwise look exactly like vanilla.
 *
 * A signed message is the odd one out among mod signals: it is not a channel, a brand or a
 * translation key, so an anti-detection mod has to specifically suppress the chat protocol to hide
 * it. OpSec does exactly that under Signing Mode: OFF.
 *
 * Ships alert-only and off by default. Unsigned chat has innocent causes - a server without
 * enforce-secure-profile, a player who disabled chat signing in their account settings, a client too
 * old to sign - so punishing on it is a decision only the operator has the context to make.
 */
public final class ChatSigningListener
implements Listener {
    // Only a player whose messages were ALL unsigned trips this. One unsigned message mid-session can be
    // a rate-limit or a suppressed-command case; a pattern of them cannot.
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
            // One report per player, however long they keep chatting unsigned.
            this.unsignedCounts.remove(player.getUniqueId());
        }
    }

    public void forget(UUID uuid) {
        this.unsignedCounts.remove(uuid);
    }
}