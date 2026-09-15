package com.cloudmarket.shops;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.storage.SqlStorage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of player shop chests, plus the purchase transaction.
 */
public final class ShopChestManager {

    public enum Result {
        OK,
        NOT_A_SHOP,
        NOT_LISTED,
        OUT_OF_STOCK,
        NOT_ENOUGH_MONEY,
        NO_INVENTORY_SPACE,
        OWN_SHOP,
        INVALID
    }

    public record Purchase(Result result, int quantity, BigDecimal total) {
        public static Purchase fail(Result result) {
            return new Purchase(result, 0, BigDecimal.ZERO);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    private final CloudMarket plugin;
    private final Map<String, ShopChest> byLocation = new ConcurrentHashMap<>();
    private final Map<Integer, ShopChest> byId = new ConcurrentHashMap<>();

    public ShopChestManager(CloudMarket plugin) {
        this.plugin = plugin;
    }

    public void load(List<SqlStorage.ChestRow> chests, List<SqlStorage.ListingRow> listings) {
        byLocation.clear();
        byId.clear();
        for (SqlStorage.ChestRow row : chests) {
            ShopChest chest = new ShopChest(row.id(), row.world(), row.x(), row.y(), row.z(), row.owner());
            byLocation.put(chest.key(), chest);
            byId.put(chest.getId(), chest);
        }
        Map<Integer, List<SqlStorage.ListingRow>> grouped = new HashMap<>();
        for (SqlStorage.ListingRow listing : listings) {
            grouped.computeIfAbsent(listing.chestId(), k -> new ArrayList<>()).add(listing);
        }
        for (Map.Entry<Integer, List<SqlStorage.ListingRow>> entry : grouped.entrySet()) {
            ShopChest chest = byId.get(entry.getKey());
            if (chest == null) {
                continue;
            }
            for (SqlStorage.ListingRow listing : entry.getValue()) {
                Material material = Material.matchMaterial(listing.material().toUpperCase(Locale.ROOT));
                if (material != null) {
                    chest.setPrice(material, listing.price());
                }
            }
        }
        plugin.getLogger().info("[CloudMarket] Loaded " + byLocation.size() + " shop chests.");
    }

    public int count() {
        return byLocation.size();
    }

    public List<ShopChest> ownedBy(UUID owner) {
        List<ShopChest> out = new ArrayList<>();
        for (ShopChest chest : byLocation.values()) {
            if (chest.getOwner().equals(owner)) {
                out.add(chest);
            }
        }
        return out;
    }

    /**
     * Find the shop registered at a block, checking both halves of a double chest.
     *
     * <p>Only one half is stored in the database, but a player can right-click
     * either. Resolving through the inventory holder means a shop registered on the
     * left half still responds when somebody clicks the right half - and, more
     * importantly, that an unregistered half cannot be used as a back door into a
     * registered chest's contents.
     */
    public ShopChest at(Block block) {
        if (block == null || !(block.getState() instanceof Chest chest)) {
            return null;
        }
        ShopChest direct = byLocation.get(ShopChest.key(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ()));
        if (direct != null) {
            return direct;
        }
        InventoryHolder holder = chest.getInventory().getHolder();
        if (holder instanceof DoubleChest doubleChest) {
            ShopChest left = fromHolder(doubleChest.getLeftSide());
            if (left != null) {
                return left;
            }
            return fromHolder(doubleChest.getRightSide());
        }
        return null;
    }

    private ShopChest fromHolder(InventoryHolder holder) {
        if (!(holder instanceof Chest chest)) {
            return null;
        }
        Location location = chest.getLocation();
        return byLocation.get(ShopChest.key(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    public boolean isShop(Block block) {
        return at(block) != null;
    }

    /** Register a chest. Returns null if it is already a shop. */
    public ShopChest register(Block block, UUID owner) {
        if (at(block) != null) {
            return null;
        }
        String world = block.getWorld().getName();
        int id;
        try {
            id = plugin.storage().insertChest(world, block.getX(), block.getY(), block.getZ(), owner);
        } catch (Exception e) {
            plugin.getLogger().warning("[CloudMarket] Could not register shop chest: " + e.getMessage());
            return null;
        }
        if (id < 0) {
            return null;
        }
        ShopChest chest = new ShopChest(id, world, block.getX(), block.getY(), block.getZ(), owner);
        byLocation.put(chest.key(), chest);
        byId.put(id, chest);
        return chest;
    }

    public void unregister(ShopChest chest) {
        byLocation.remove(chest.key());
        byId.remove(chest.getId());
        plugin.async(() -> {
            try {
                plugin.storage().deleteChest(chest.getId());
            } catch (Exception e) {
                plugin.getLogger().warning("[CloudMarket] Could not delete shop chest: " + e.getMessage());
            }
        });
    }

    public void setPrice(ShopChest chest, Material material, BigDecimal price) {
        chest.setPrice(material, price);
        plugin.async(() -> {
            try {
                plugin.storage().saveListing(chest.getId(), material.name(), price);
            } catch (Exception e) {
                plugin.getLogger().warning("[CloudMarket] Could not save listing: " + e.getMessage());
            }
        });
    }

    public void clearPrice(ShopChest chest, Material material) {
        chest.clearPrice(material);
        plugin.async(() -> {
            try {
                plugin.storage().deleteListing(chest.getId(), material.name());
            } catch (Exception e) {
                plugin.getLogger().warning("[CloudMarket] Could not delete listing: " + e.getMessage());
            }
        });
    }

    /** The live chest inventory, or null if the block is gone or unloaded. */
    public Inventory inventoryOf(ShopChest chest) {
        World world = plugin.getServer().getWorld(chest.getWorld());
        if (world == null) {
            return null;
        }
        Block block = world.getBlockAt(chest.getX(), chest.getY(), chest.getZ());
        if (!(block.getState() instanceof Chest state)) {
            return null;
        }
        return state.getInventory();
    }

    public int stockOf(ShopChest chest, Material material) {
        Inventory inventory = inventoryOf(chest);
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    public List<Material> listedMaterials(ShopChest chest) {
        List<Material> out = new ArrayList<>(chest.getPrices().keySet());
        out.sort(java.util.Comparator.comparing(Material::name));
        return out;
    }

    /**
     * Buy from a shop chest. Items come out of the physical chest and the money goes
     * straight to the owner's balance whether or not they are online.
     *
     * <p>No tax is charged here. The cloud market's tax exists to stop arbitrage
     * against an automated price; a player-to-player sale at a hand-set price has no
     * such loop to close, and taxing it would just be a fee on trading with friends.
     */
    public Purchase buy(Player buyer, ShopChest chest, Material material, int quantity) {
        if (quantity <= 0 || material == null || material.isAir()) {
            return Purchase.fail(Result.INVALID);
        }
        if (chest.getOwner().equals(buyer.getUniqueId())) {
            return Purchase.fail(Result.OWN_SHOP);
        }
        BigDecimal unit = chest.priceOf(material);
        if (unit == null) {
            return Purchase.fail(Result.NOT_LISTED);
        }
        Inventory inventory = inventoryOf(chest);
        if (inventory == null) {
            return Purchase.fail(Result.NOT_A_SHOP);
        }
        int available = stockOf(chest, material);
        if (available <= 0) {
            return Purchase.fail(Result.OUT_OF_STOCK);
        }
        int amount = Math.min(quantity, available);
        BigDecimal total = unit.multiply(BigDecimal.valueOf(amount))
                .setScale(2, java.math.RoundingMode.HALF_UP);

        if (!plugin.economy().has(buyer.getUniqueId(), total)) {
            return Purchase.fail(Result.NOT_ENOUGH_MONEY);
        }
        if (freeSpace(buyer, material) < amount) {
            return Purchase.fail(Result.NO_INVENTORY_SPACE);
        }
        if (!plugin.economy().withdraw(buyer.getUniqueId(), total)) {
            return Purchase.fail(Result.NOT_ENOUGH_MONEY);
        }

        // Take from the chest before handing anything over, so a failure here cannot
        // duplicate items.
        int removed = removeFromChest(inventory, material, amount);
        if (removed < amount) {
            // Somebody emptied the chest in between. Refund the difference.
            BigDecimal refund = unit.multiply(BigDecimal.valueOf(amount - removed))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            plugin.economy().deposit(buyer.getUniqueId(), refund);
            total = total.subtract(refund);
            amount = removed;
        }
        if (amount <= 0) {
            return Purchase.fail(Result.OUT_OF_STOCK);
        }

        plugin.economy().deposit(chest.getOwner(), total);
        give(buyer, material, amount);

        plugin.logTransaction(buyer.getUniqueId(), "SHOPCHEST_BUY", material.name(), amount,
                total, BigDecimal.ZERO, 0L, chest.getOwner().toString());

        Player owner = plugin.getServer().getPlayer(chest.getOwner());
        if (owner != null && owner.isOnline()) {
            plugin.configs().messages().send(owner, "shopchest.sold-notify", Map.of(
                    "buyer", buyer.getName(),
                    "amount", String.valueOf(amount),
                    "item", com.cloudmarket.util.Fmt.pretty(material),
                    "total", com.cloudmarket.util.Fmt.money(total),
                    "symbol", plugin.configs().currencySymbol()));
        }
        return new Purchase(Result.OK, amount, total);
    }

    private static int removeFromChest(Inventory inventory, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            remaining -= take;
            if (stack.getAmount() <= 0) {
                contents[slot] = null;
            }
        }
        inventory.setContents(contents);
        return amount - remaining;
    }

    private static int freeSpace(Player player, Material material) {
        int max = material.getMaxStackSize();
        int space = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                space += max;
            } else if (stack.getType() == material) {
                space += Math.max(0, max - stack.getAmount());
            }
        }
        return space;
    }

    private static void give(Player player, Material material, int quantity) {
        int remaining = quantity;
        int max = material.getMaxStackSize();
        while (remaining > 0) {
            int size = Math.min(max, remaining);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, size));
            remaining -= size;
            for (ItemStack stack : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }
        }
    }
}
