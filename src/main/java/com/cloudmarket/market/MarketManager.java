package com.cloudmarket.market;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.storage.SqlStorage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The global cloud market: a single shared stock pool with automatic pricing.
 *
 * <p>Eligibility is decided in three layers, in order:
 * <ol>
 *   <li>The recipe scan bars anything with a value-add recipe (see
 *       {@link CraftableScanner}).</li>
 *   <li>craftable-overrides.yml lets an admin opt specific barred items back in.</li>
 *   <li>market-items.yml must then give the item a price, or it still will not
 *       trade - an item with no price is simply absent from the market.</li>
 * </ol>
 */
public final class MarketManager {

    /** Outcome of an attempted trade. */
    public enum Result {
        OK,
        NOT_SELLABLE_CRAFTABLE,
        NOT_CONFIGURED,
        DISABLED,
        NOT_ENOUGH_STOCK,
        NOT_ENOUGH_MONEY,
        NO_INVENTORY_SPACE,
        RATE_LIMITED,
        INVALID_ITEM
    }

    public record TradeOutcome(Result result, int quantity, PricingEngine.Quote quote, int limitRemaining) {
        public static TradeOutcome fail(Result result) {
            return new TradeOutcome(result, 0, null, 0);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    private final CloudMarket plugin;
    private final Map<Material, MarketItem> items = new ConcurrentHashMap<>();
    private final CraftableScanner scanner;
    private final SellLimiter limiter;
    private Set<Material> whitelist = Set.of();

    public MarketManager(CloudMarket plugin) {
        this.plugin = plugin;
        this.scanner = new CraftableScanner(plugin.getLogger());
        this.limiter = new SellLimiter(plugin.configs().sellCapPerHour());
    }

    public CraftableScanner scanner() {
        return scanner;
    }

    public SellLimiter limiter() {
        return limiter;
    }

    public Map<Material, MarketItem> items() {
        return items;
    }

    public MarketItem get(Material material) {
        return items.get(material);
    }

    /**
     * Rebuild the whole market from the recipe registry, the config and the saved
     * stock levels. Safe to call again on /marketadmin reload.
     *
     * @param persisted stock and price rows already in the database, which win over
     *                  config for {@code current_stock} only - prices are config's
     *                  job so an admin editing the yml sees their change take effect
     */
    public void rebuild(List<SqlStorage.MarketRow> persisted) {
        scanner.scan();
        whitelist = plugin.configs().whitelistedCraftables();
        limiter.setCapPerHour(plugin.configs().sellCapPerHour());

        Map<String, SqlStorage.MarketRow> saved = new HashMap<>();
        for (SqlStorage.MarketRow row : persisted) {
            saved.put(row.material().toUpperCase(Locale.ROOT), row);
        }

        items.clear();
        ConfigurationSection root = plugin.configs().marketItems().getConfigurationSection("items");
        if (root == null) {
            plugin.getLogger().warning("[CloudMarket] market-items.yml has no 'items' section; "
                    + "the global market will be empty. Run /marketadmin autoconfig to generate one.");
            return;
        }

        int skippedUnknown = 0;
        int skippedCraftable = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (material == null) {
                // Unknown material names are skipped with a warning rather than
                // throwing, so a config written for a different game version still
                // loads the entries that are valid.
                plugin.getLogger().warning("[CloudMarket] market-items.yml references unknown material '"
                        + key + "'; skipping.");
                skippedUnknown++;
                continue;
            }
            if (!isEligible(material)) {
                skippedCraftable++;
                continue;
            }

            double base = section.getDouble("basePrice", 1.0d);
            double floor = section.getDouble("floorPrice", 0.0d);
            double ceiling = section.getDouble("ceilingPrice", base * 2.0d);
            long equilibrium = section.getLong("equilibriumStock", 2048L);
            boolean enabled = section.getBoolean("enabled", true);
            Category category = Category.parse(section.getString("category"), Category.classify(material));

            long stock = section.getLong("initialStock", 0L);
            SqlStorage.MarketRow row = saved.get(material.name());
            if (row != null) {
                stock = row.currentStock();
            }

            if (ceiling < base) {
                plugin.getLogger().warning("[CloudMarket] " + material + " has ceilingPrice below basePrice; "
                        + "raising the ceiling to the base price.");
                ceiling = base;
            }
            if (floor > base) {
                plugin.getLogger().warning("[CloudMarket] " + material + " has floorPrice above basePrice; "
                        + "lowering the floor to the base price.");
                floor = base;
            }

            items.put(material, new MarketItem(material, category, base, floor, ceiling,
                    equilibrium, stock, enabled));
        }

        plugin.getLogger().info("[CloudMarket] Market loaded: " + items.size() + " tradable materials"
                + (skippedCraftable > 0 ? ", " + skippedCraftable + " config entries ignored as craftable" : "")
                + (skippedUnknown > 0 ? ", " + skippedUnknown + " unknown material names skipped" : "") + ".");
        warnAboutFarmableFloors();
    }

    /**
     * A non-zero floor price on an infinitely farmable item is an unbounded money
     * source: the curve will crash the price to the floor and then keep paying it
     * out forever. The curve cannot save you here - only a zero floor or a sell cap
     * can - so it is worth saying out loud at startup.
     */
    private void warnAboutFarmableFloors() {
        List<String> offenders = new ArrayList<>();
        for (MarketItem item : items.values()) {
            if (!item.hasUnboundedFloor()) {
                continue;
            }
            Category category = item.getCategory();
            if (category == Category.FOOD || category == Category.MOB_DROPS) {
                offenders.add(item.getMaterial().name());
            }
        }
        if (!offenders.isEmpty() && !limiter.isEnabled()) {
            plugin.getLogger().warning("[CloudMarket] These farmable items have a floor price above zero "
                    + "and no sell cap is configured, which makes them an unlimited money source: "
                    + String.join(", ", offenders.subList(0, Math.min(8, offenders.size())))
                    + (offenders.size() > 8 ? " (+" + (offenders.size() - 8) + " more)" : "")
                    + ". Set floorPrice: 0 for these, or set limits.sell-per-item-per-hour in economy.yml.");
        }
    }

    /** True if the raw-material rule and the override list allow this material. */
    public boolean isEligible(Material material) {
        return scanner.isRaw(material) || whitelist.contains(material);
    }

    public boolean isTradable(Material material) {
        MarketItem item = items.get(material);
        return item != null && item.isEnabled();
    }

    public List<MarketItem> byCategory(Category category) {
        List<MarketItem> out = new ArrayList<>();
        for (MarketItem item : items.values()) {
            if (item.getCategory() == category && item.isEnabled()) {
                out.add(item);
            }
        }
        out.sort(Comparator.comparing(item -> item.getMaterial().name()));
        return out;
    }

    public Map<Category, Integer> categoryCounts() {
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            counts.put(category, 0);
        }
        for (MarketItem item : items.values()) {
            if (item.isEnabled()) {
                counts.merge(item.getCategory(), 1, Integer::sum);
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------ trading

    /** Price a sale without committing it, for confirmation screens. */
    public TradeOutcome quoteSell(Player player, Material material, int quantity) {
        if (material == null || material.isAir() || quantity <= 0) {
            return TradeOutcome.fail(Result.INVALID_ITEM);
        }
        if (!isEligible(material)) {
            return TradeOutcome.fail(Result.NOT_SELLABLE_CRAFTABLE);
        }
        MarketItem item = items.get(material);
        if (item == null) {
            return TradeOutcome.fail(Result.NOT_CONFIGURED);
        }
        if (!item.isEnabled()) {
            return TradeOutcome.fail(Result.DISABLED);
        }

        int allowed = quantity;
        if (!player.hasPermission("market.limit.bypass")) {
            int remaining = limiter.remaining(player.getUniqueId(), material);
            if (remaining <= 0) {
                return new TradeOutcome(Result.RATE_LIMITED, 0, null, 0);
            }
            allowed = Math.min(quantity, remaining);
        }

        PricingEngine.Quote quote =
                PricingEngine.quoteSell(item, item.getStock(), allowed, plugin.configs().taxRate());
        return new TradeOutcome(Result.OK, allowed, quote,
                player.hasPermission("market.limit.bypass")
                        ? Integer.MAX_VALUE : limiter.remaining(player.getUniqueId(), material));
    }

    /**
     * Sell items from a player's inventory. Removes exactly the quantity priced,
     * credits the player and burns the tax.
     */
    public TradeOutcome executeSell(Player player, Material material, int quantity) {
        TradeOutcome quoted = quoteSell(player, material, quantity);
        if (!quoted.ok()) {
            return quoted;
        }
        int amount = quoted.quantity();
        int available = countIn(player.getInventory(), material);
        if (available < amount) {
            amount = available;
        }
        if (amount <= 0) {
            return TradeOutcome.fail(Result.INVALID_ITEM);
        }

        MarketItem item = items.get(material);
        // Re-price against the live stock, because the quote above may be a few
        // ticks stale if the player sat on a confirmation screen.
        PricingEngine.Quote quote =
                PricingEngine.quoteSell(item, item.getStock(), amount, plugin.configs().taxRate());

        removeFrom(player.getInventory(), material, amount);
        item.addStock(amount);
        plugin.economy().deposit(player.getUniqueId(), quote.net());
        plugin.economy().burn(quote.tax());
        limiter.record(player.getUniqueId(), material, amount);

        plugin.logTransaction(player.getUniqueId(), "SELL_MARKET", material.name(), amount,
                quote.net(), quote.tax(), item.getStock(), null);

        return new TradeOutcome(Result.OK, amount, quote,
                player.hasPermission("market.limit.bypass")
                        ? Integer.MAX_VALUE : limiter.remaining(player.getUniqueId(), material));
    }

    /** Price a purchase without committing it. */
    public TradeOutcome quoteBuy(Player player, Material material, int quantity) {
        if (material == null || material.isAir() || quantity <= 0) {
            return TradeOutcome.fail(Result.INVALID_ITEM);
        }
        MarketItem item = items.get(material);
        if (item == null) {
            return TradeOutcome.fail(Result.NOT_CONFIGURED);
        }
        if (!item.isEnabled()) {
            return TradeOutcome.fail(Result.DISABLED);
        }
        // Stock cannot go negative, which is what enforces "you can only buy what
        // somebody has already sold" - no separate rule needed.
        if (item.getStock() < quantity) {
            return TradeOutcome.fail(Result.NOT_ENOUGH_STOCK);
        }
        PricingEngine.Quote quote =
                PricingEngine.quoteBuy(item, item.getStock(), quantity, plugin.configs().taxRate());
        return new TradeOutcome(Result.OK, quantity, quote, Integer.MAX_VALUE);
    }

    public TradeOutcome executeBuy(Player player, Material material, int quantity) {
        TradeOutcome quoted = quoteBuy(player, material, quantity);
        if (!quoted.ok()) {
            return quoted;
        }
        MarketItem item = items.get(material);
        PricingEngine.Quote quote =
                PricingEngine.quoteBuy(item, item.getStock(), quantity, plugin.configs().taxRate());

        if (!plugin.economy().has(player.getUniqueId(), quote.net())) {
            return TradeOutcome.fail(Result.NOT_ENOUGH_MONEY);
        }
        if (freeSpaceFor(player.getInventory(), material) < quantity) {
            return TradeOutcome.fail(Result.NO_INVENTORY_SPACE);
        }
        if (!plugin.economy().withdraw(player.getUniqueId(), quote.net())) {
            return TradeOutcome.fail(Result.NOT_ENOUGH_MONEY);
        }

        item.addStock(-quantity);
        plugin.economy().burn(quote.tax());
        giveItems(player, material, quantity);

        plugin.logTransaction(player.getUniqueId(), "BUY_MARKET", material.name(), quantity,
                quote.net(), quote.tax(), item.getStock(), null);

        return new TradeOutcome(Result.OK, quantity, quote, Integer.MAX_VALUE);
    }

    // --------------------------------------------------------------- inventory

    public static int countIn(PlayerInventory inventory, Material material) {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.getType() == material && isPlainStack(stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Only vanilla, unmodified stacks may be sold to the cloud. An enchanted,
     * renamed or otherwise data-bearing item is worth more than its material and
     * selling it at material price would destroy value the player did not intend
     * to give up.
     */
    public static boolean isPlainStack(ItemStack stack) {
        return !stack.hasItemMeta() || stack.getItemMeta() == null
                || (!stack.getItemMeta().hasDisplayName()
                && !stack.getItemMeta().hasEnchants()
                && !stack.getItemMeta().hasLore());
    }

    private static void removeFrom(PlayerInventory inventory, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material || !isPlainStack(stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            remaining -= take;
            if (stack.getAmount() <= 0) {
                contents[slot] = null;
            }
        }
        inventory.setStorageContents(contents);
    }

    private static int freeSpaceFor(PlayerInventory inventory, Material material) {
        int max = material.getMaxStackSize();
        int space = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                space += max;
            } else if (stack.getType() == material && isPlainStack(stack)) {
                space += Math.max(0, max - stack.getAmount());
            }
        }
        return space;
    }

    private static void giveItems(Player player, Material material, int quantity) {
        int remaining = quantity;
        int max = material.getMaxStackSize();
        while (remaining > 0) {
            int size = Math.min(max, remaining);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, size));
            remaining -= size;
            // Space was checked beforehand, but drop anything that still will not
            // fit rather than quietly deleting a purchase the player paid for.
            for (ItemStack stack : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }
        }
    }

    // ------------------------------------------------------------- persistence

    public List<SqlStorage.MarketRow> snapshot() {
        List<SqlStorage.MarketRow> out = new ArrayList<>();
        for (MarketItem item : items.values()) {
            out.add(new SqlStorage.MarketRow(item.getMaterial().name(), item.getCategory().name(),
                    item.getBasePrice(), item.getFloorPrice(), item.getCeilingPrice(),
                    item.getEquilibriumStock(), item.getStock(), item.isEnabled()));
        }
        return out;
    }

    public void clearLimits(UUID player) {
        limiter.clear(player);
    }
}
