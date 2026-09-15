package com.cloudmarket.blackmarket;

import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One black market listing.
 *
 * <p>The items are held here, not in the seller's inventory. Listing an item takes
 * it; cancelling or expiry gives it back. Leaving it with the seller would let them
 * list a pickaxe, mine it to one durability point, and still collect full price -
 * or drop it entirely and sell a buyer something that no longer exists.
 *
 * <p>{@code template} is a single unit with amount 1. Quantity is tracked
 * separately so a partial purchase is just a decrement, and so a listing of twelve
 * spawners does not need twelve copies of the same NBT.
 */
public final class Listing {

    private final int id;
    private final UUID seller;
    private final ItemStack template;
    private final BigDecimal unitPrice;
    private final long listedAt;
    private final long expiresAt;
    private volatile int remaining;

    public Listing(int id, UUID seller, ItemStack template, int remaining,
                   BigDecimal unitPrice, long listedAt, long expiresAt) {
        this.id = id;
        this.seller = seller;
        this.template = template;
        this.remaining = remaining;
        this.unitPrice = unitPrice;
        this.listedAt = listedAt;
        this.expiresAt = expiresAt;
    }

    public int getId() {
        return id;
    }

    public UUID getSeller() {
        return seller;
    }

    /** A fresh copy every time, so callers cannot mutate the stored template. */
    public ItemStack copyOf(int amount) {
        ItemStack copy = template.clone();
        copy.setAmount(Math.max(1, amount));
        return copy;
    }

    public ItemStack getTemplate() {
        return template;
    }

    public int getRemaining() {
        return remaining;
    }

    public void setRemaining(int remaining) {
        this.remaining = Math.max(0, remaining);
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public long getListedAt() {
        return listedAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    /** Visible on the black market: still has stock and has not timed out. */
    public boolean isActive() {
        return remaining > 0 && !isExpired();
    }

    /** Has items the seller can still reclaim. */
    public boolean isReclaimable() {
        return remaining > 0;
    }

    public BigDecimal priceFor(int amount) {
        return unitPrice.multiply(BigDecimal.valueOf(amount)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal totalValue() {
        return priceFor(remaining);
    }

    /** Whole days left before expiry, floored at zero. */
    public long daysRemaining() {
        long millis = expiresAt - System.currentTimeMillis();
        return millis <= 0 ? 0 : millis / 86_400_000L;
    }
}
