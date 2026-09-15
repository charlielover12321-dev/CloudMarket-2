package com.cloudmarket.market;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window cap on how much of one material a single player can dump into the
 * cloud market per hour.
 *
 * <p>This matters more than it looks. The bonding curve makes large trades
 * self-limiting in price, but it does not stop one player with an iron farm from
 * walking the price down to the floor and leaving it there for everyone else. The
 * cap spreads that out over time so other players get a chance to sell before the
 * price bottoms out.
 */
public final class SellLimiter {

    private record Entry(long timestamp, int amount) {
    }

    private final Map<UUID, Map<Material, Deque<Entry>>> history = new ConcurrentHashMap<>();
    private volatile int capPerHour;

    public SellLimiter(int capPerHour) {
        this.capPerHour = capPerHour;
    }

    public void setCapPerHour(int capPerHour) {
        this.capPerHour = capPerHour;
    }

    public int getCapPerHour() {
        return capPerHour;
    }

    public boolean isEnabled() {
        return capPerHour > 0;
    }

    /** How many more units this player may sell of this material right now. */
    public int remaining(UUID player, Material material) {
        if (!isEnabled()) {
            return Integer.MAX_VALUE;
        }
        Deque<Entry> entries = history
                .computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(material, k -> new ArrayDeque<>());

        int used = 0;
        synchronized (entries) {
            prune(entries);
            for (Entry entry : entries) {
                used += entry.amount();
            }
        }
        return Math.max(0, capPerHour - used);
    }

    /** Record a completed sale against the player's hourly allowance. */
    public void record(UUID player, Material material, int amount) {
        if (!isEnabled() || amount <= 0) {
            return;
        }
        Deque<Entry> entries = history
                .computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(material, k -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(new Entry(System.currentTimeMillis(), amount));
            prune(entries);
        }
    }

    public void clear(UUID player) {
        history.remove(player);
    }

    private void prune(Deque<Entry> entries) {
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        while (!entries.isEmpty() && entries.peekFirst().timestamp() < cutoff) {
            entries.removeFirst();
        }
    }
}
