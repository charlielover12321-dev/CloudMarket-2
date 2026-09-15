package com.cloudmarket.shops;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player-owned shop chest.
 *
 * <p>There is deliberately no stock field. The chest's real inventory <em>is</em>
 * the stock: if twelve mending books are in the chest, twelve are purchasable. That
 * removes an entire class of desync bug, and it means an owner restocking by hand
 * needs no command to tell the plugin about it.
 *
 * <p>This system shares nothing with the global cloud market - not stock, not
 * pricing, not the raw-material rule. A player may list any item at any price.
 */
public final class ShopChest {

    private final int id;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final UUID owner;
    private final Map<Material, BigDecimal> prices = new ConcurrentHashMap<>();

    public ShopChest(int id, String world, int x, int y, int z, UUID owner) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.owner = owner;
    }

    public int getId() {
        return id;
    }

    public String getWorld() {
        return world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public UUID getOwner() {
        return owner;
    }

    public Map<Material, BigDecimal> getPrices() {
        return prices;
    }

    public BigDecimal priceOf(Material material) {
        return prices.get(material);
    }

    public void setPrice(Material material, BigDecimal price) {
        prices.put(material, price);
    }

    public void clearPrice(Material material) {
        prices.remove(material);
    }

    public Location toLocation(World resolved) {
        return new Location(resolved, x, y, z);
    }

    public static String key(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }

    public String key() {
        return key(world, x, y, z);
    }
}
