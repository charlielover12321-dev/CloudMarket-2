package com.cloudmarket.market;

import org.bukkit.Material;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One tradable material on the global cloud market.
 *
 * <p>Stock is the single source of truth for pricing, so it lives in memory as an
 * atomic and is written through to the database asynchronously. Reading it from
 * SQL on every quote would both be slow and open a race where two players get the
 * same price for the same units.
 */
public final class MarketItem {

    private final Material material;
    private volatile Category category;
    private volatile double basePrice;
    private volatile double floorPrice;
    private volatile double ceilingPrice;
    private volatile long equilibriumStock;
    private volatile boolean enabled;
    private final AtomicLong stock = new AtomicLong();

    public MarketItem(Material material, Category category, double basePrice, double floorPrice,
                      double ceilingPrice, long equilibriumStock, long initialStock, boolean enabled) {
        this.material = material;
        this.category = category;
        this.basePrice = basePrice;
        this.floorPrice = floorPrice;
        this.ceilingPrice = ceilingPrice;
        this.equilibriumStock = Math.max(1L, equilibriumStock);
        this.enabled = enabled;
        this.stock.set(Math.max(0L, initialStock));
    }

    public Material getMaterial() {
        return material;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public double getBasePrice() {
        return basePrice;
    }

    public double getFloorPrice() {
        return floorPrice;
    }

    public double getCeilingPrice() {
        return ceilingPrice;
    }

    public long getEquilibriumStock() {
        return equilibriumStock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getStock() {
        return stock.get();
    }

    public void setStock(long value) {
        stock.set(Math.max(0L, value));
    }

    public long addStock(int delta) {
        return stock.updateAndGet(current -> Math.max(0L, current + delta));
    }

    public void setPrices(double base, double floor, double ceiling, long equilibrium) {
        this.basePrice = base;
        this.floorPrice = floor;
        this.ceilingPrice = ceiling;
        this.equilibriumStock = Math.max(1L, equilibrium);
    }

    /**
     * A renewable item is one a player can farm without bound - crops, mob drops,
     * bamboo. For those, a non-zero floor price is an infinite money printer: the
     * curve will happily crash the price to the floor and then keep paying it
     * forever. Flagging them lets the admin command warn about it.
     */
    public boolean hasUnboundedFloor() {
        return floorPrice > 0.0d;
    }

    @Override
    public String toString() {
        return "MarketItem{" + material + ", stock=" + stock.get() + "}";
    }
}
