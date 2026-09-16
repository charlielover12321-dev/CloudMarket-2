package com.cloudmarket.shops;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

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
    private final Map<String, Offer> offers = new ConcurrentHashMap<>();

    /**
     * One priced item variant in this chest.
     *
     * @param key      stable identifier from ItemCodec.keyOf
     * @param material the material, for stock counting and display fallback
     * @param template the exact item, or null for a legacy material-wide listing
     *                 that matches any item of that type
     * @param price    per unit
     */
    public record Offer(String key, Material material, ItemStack template, BigDecimal price) {

        /** True if this listing matches the given stack. */
        public boolean matches(ItemStack stack) {
            if (stack == null || stack.getType() != material) {
                return false;
            }
            // A legacy listing has no template and deliberately matches the whole
            // material, which is the old behaviour for rows written before variants.
            return template == null || stack.isSimilar(template);
        }

        /** A display copy: the real item where we have one, else a plain stack. */
        public ItemStack icon(int amount) {
            ItemStack copy = template != null ? template.clone() : new ItemStack(material);
            copy.setAmount(Math.max(1, Math.min(amount, copy.getMaxStackSize())));
            return copy;
        }
    }

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

    public Map<String, Offer> getOffers() {
        return offers;
    }

    public Offer offerFor(String key) {
        return offers.get(key);
    }

    public void setOffer(Offer offer) {
        offers.put(offer.key(), offer);
    }

    public void clearOffer(String key) {
        offers.remove(key);
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
