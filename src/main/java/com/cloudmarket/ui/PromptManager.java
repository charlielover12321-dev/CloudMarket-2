package com.cloudmarket.ui;

import com.cloudmarket.CloudMarket;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Chat-based number and price entry.
 *
 * <p>This is the Java-side counterpart to the Bedrock forms. Chat is used rather
 * than an anvil-rename GUI because an open anvil would have to be closed to read
 * the text, which fights with the shop GUI the player came from, and because anvil
 * text entry is one of the things that does not survive translation to Bedrock -
 * so a chat fallback is needed anyway and there is no reason to maintain two.
 *
 * <p>Chat events fire off the main thread, so every callback is bounced back onto
 * it before it touches anything in the Bukkit API.
 */
public final class PromptManager implements Listener {

    private sealed interface Pending permits NumberPending, PricePending {
        long expiry();
    }

    private record NumberPending(int min, int max, Consumer<Integer> callback, long expiry) implements Pending {
    }

    private record PricePending(Consumer<BigDecimal> callback, long expiry) implements Pending {
    }

    private final CloudMarket plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public PromptManager(CloudMarket plugin) {
        this.plugin = plugin;
    }

    public void shutdown() {
        pending.clear();
        HandlerList.unregisterAll(this);
    }

    public void beginNumberPrompt(Player player, String label, int min, int max, Consumer<Integer> onResult) {
        pending.put(player.getUniqueId(),
                new NumberPending(min, max, onResult, System.currentTimeMillis() + 60_000L));
        plugin.configs().messages().send(player, "input.chat-number", Map.of(
                "label", label, "min", String.valueOf(min), "max", String.valueOf(max)));
    }

    public void beginPricePrompt(Player player, String label, Consumer<BigDecimal> onResult) {
        pending.put(player.getUniqueId(),
                new PricePending(onResult, System.currentTimeMillis() + 60_000L));
        plugin.configs().messages().send(player, "input.chat-price", Map.of("label", label));
    }

    public boolean hasPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    public void cancel(Player player) {
        pending.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending waiting = pending.get(player.getUniqueId());
        if (waiting == null) {
            return;
        }
        if (waiting.expiry() < System.currentTimeMillis()) {
            pending.remove(player.getUniqueId());
            return;
        }

        // The message is consumed as input, not broadcast to the server.
        event.setCancelled(true);
        pending.remove(player.getUniqueId());

        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (message.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.configs().messages().send(player, "input.cancelled"));
            return;
        }

        if (waiting instanceof NumberPending numberPending) {
            int value;
            try {
                value = Integer.parseInt(message.replace(",", ""));
            } catch (NumberFormatException e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.configs().messages().send(player, "input.not-a-number"));
                return;
            }
            int clamped = Math.max(numberPending.min(), Math.min(numberPending.max(), value));
            Bukkit.getScheduler().runTask(plugin, () -> numberPending.callback().accept(clamped));
        } else if (waiting instanceof PricePending pricePending) {
            BigDecimal value;
            try {
                value = new BigDecimal(message.replace(",", "")).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.configs().messages().send(player, "input.not-a-number"));
                return;
            }
            if (value.signum() <= 0) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.configs().messages().send(player, "input.not-a-number"));
                return;
            }
            BigDecimal finalValue = value;
            Bukkit.getScheduler().runTask(plugin, () -> pricePending.callback().accept(finalValue));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
