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
    /**
     * Items whose prices come from market-items.yml. These anchor the valuation
     * tree: every crafted price is ultimately derived from them, so they must be
     * distinguishable from the derived items that also live in {@link #items}.
     */
    private final Map<Material, MarketItem> configured = new ConcurrentHashMap<>();
    private final CraftableScanner scanner;
    private final RecipeValuation valuation;
    private final SellLimiter limiter;
    private Set<Material> whitelist = Set.of();
    private volatile boolean refuseDamaged = true;
    private Set<Material> blocked = Set.of();

    public MarketManager(CloudMarket plugin) {
        this.plugin = plugin;
        this.scanner = new CraftableScanner(plugin.getLogger());
        this.valuation = new RecipeValuation(plugin);
        this.limiter = new SellLimiter(plugin.configs().sellCapPerHour());
    }

    public RecipeValuation valuation() {
        return valuation;
    }

    /** The config-priced item for this material, or null if it is derived. */
    public MarketItem configured(Material material) {
        return configured.get(material);
    }

    public boolean isDerived(Material material) {
        return items.containsKey(material) && !configured.containsKey(material);
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
        refuseDamaged = plugin.configs().refuseDamaged();
        blocked = plugin.configs().blockedMaterials();
        limiter.setCapPerHour(plugin.configs().sellCapPerHour());

        Map<String, SqlStorage.MarketRow> saved = new HashMap<>();
        for (SqlStorage.MarketRow row : persisted) {
            saved.put(row.material().toUpperCase(Locale.ROOT), row);
        }

        items.clear();
        configured.clear();
        ConfigurationSection root = plugin.configs().marketItems().getConfigurationSection("items");
        if (root == null) {
            plugin.getLogger().warning("[CloudMarket] market-items.yml has no 'items' section; "
                    + "the global market will be empty. Run /marketadmin autoconfig to generate one.");
            return;
        }

        int skippedUnknown = 0;
        int skippedCraftable = 0;
        int skippedBlocked = 0;
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
            if (blocked.contains(material)) {
                skippedBlocked++;
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
            configured.put(material, items.get(material));
        }

        plugin.getLogger().info("[CloudMarket] Market loaded: " + items.size() + " tradable materials"
                + (skippedCraftable > 0 ? ", " + skippedCraftable + " config entries ignored as craftable" : "")
                + (skippedUnknown > 0 ? ", " + skippedUnknown + " unknown material names skipped" : "")
                + (skippedBlocked > 0 ? ", " + skippedBlocked + " blocked as unobtainable" : "") + ".");

        if (plugin.configs().craftedEnabled()) {
            buildDerivedItems(saved);
        }
        warnAboutFarmableFloors();
    }

    /**
     * Create a tradable entry for every craftable material the valuation engine can
     * price.
     *
     * <p>These carry their own stock pool, so you can only buy diorite somebody has
     * actually sold - selling diorite does not push cobblestone and quartz into
     * those pools.
     *
     * <p>The ceiling is pinned to ingredient value and the floor sits at zero. That
     * pinning is the whole safety property: the curve pays 2x base at zero stock to
     * reward selling something scarce, which for a crafted item would hand the first
     * seller double what the materials cost. Capping at ingredient value keeps the
     * "dumping crashes the price" behaviour while making craft-and-sell a loss.
     */
    private void buildDerivedItems(Map<String, SqlStorage.MarketRow> saved) {
        valuation.buildGraph();
        double multiplier = plugin.configs().craftedMultiplier();
        long equilibrium = plugin.configs().craftedEquilibrium();

        int created = 0;
        int unpriceable = 0;
        for (Material material : Material.values()) {
            if (material.isLegacy() || material.isAir() || !material.isItem()) {
                continue;
            }
            if (configured.containsKey(material) || blocked.contains(material)) {
                continue;
            }
            Double value = valuation.valueOf(material);
            if (value == null || value <= 0.0d) {
                unpriceable++;
                continue;
            }
            double ceiling = value * multiplier;

            long stock = 0L;
            SqlStorage.MarketRow row = saved.get(material.name());
            if (row != null) {
                stock = row.currentStock();
            }

            items.put(material, new MarketItem(material, Category.classify(material),
                    ceiling, 0.0d, ceiling, equilibrium, stock, true));
            created++;
        }

        plugin.getLogger().info("[CloudMarket] Crafted goods: " + created
                + " priced from their recipes, " + unpriceable
                + " could not be priced (no recipe path to a raw material).");
    }

    /**
     * Re-derive crafted prices from the current state of the raw material curves.
     *
     * <p>Must run before any crafted item is quoted. Ingredient prices move on every
     * transaction, and a crafted price computed from stale ingredient values is
     * exactly the gap a player would arbitrage.
     */
    public void refreshDerivedPrices() {
        if (!plugin.configs().craftedEnabled()) {
            return;
        }
        double multiplier = plugin.configs().craftedMultiplier();
        long equilibrium = plugin.configs().craftedEquilibrium();
        for (MarketItem item : items.values()) {
            if (configured.containsKey(item.getMaterial())) {
                continue;
            }
            Double value = valuation.valueOf(item.getMaterial());
            if (value == null || value <= 0.0d) {
                item.setEnabled(false);
                continue;
            }
            double ceiling = value * multiplier;
            item.setPrices(ceiling, 0.0d, ceiling, equilibrium);
            item.setEnabled(true);
        }
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
        // Unobtainable items are never eligible, whatever the recipe scan says.
        if (blocked.contains(material)) {
            return false;
        }
        if (scanner.isRaw(material) || whitelist.contains(material)) {
            return true;
        }
        // With recipe pricing on, a crafted material is eligible as soon as the
        // valuation engine can reach a raw material through its recipe tree. No
        // whitelist entry and no config price needed.
        return plugin.configs().craftedEnabled() && items.containsKey(material);
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
        out.sort(ItemOrder.COMPARATOR);
        return out;
    }

    /**
     * The page layout for a category: the sorted items, with null padding inserted
     * so each new material family starts on a fresh row.
     *
     * <p>Sorting alone still runs families together mid-row, so a page of tools
     * reads as one undifferentiated block. Breaking to a new row on every family
     * change turns it into visual groups - a row of wooden tools, a row of stone,
     * a row of iron - which is what makes it scannable rather than merely ordered.
     *
     * <p>The GUI and the click handler both build the layout from this method, so a
     * slot index means the same thing in both places. Computing it in only one of
     * them would make every click land on the wrong item.
     */
    public List<MarketItem> layout(Category category) {
        List<MarketItem> sorted = byCategory(category);
        List<MarketItem> out = new ArrayList<>();
        String lastFamily = null;
        for (MarketItem item : sorted) {
            String family = ItemOrder.familyOf(item.getMaterial());
            if (lastFamily != null && !family.equals(lastFamily)) {
                while (out.size() % 9 != 0) {
                    out.add(null);
                }
            }
            out.add(item);
            lastFamily = family;
        }
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
        refreshDerivedPrices();
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
        valuation.invalidate();
        plugin.economy().deposit(player.getUniqueId(), quote.net());
        plugin.economy().burn(quote.tax());
        limiter.record(player.getUniqueId(), material, amount);

        plugin.logTransaction(player.getUniqueId(), "SELL_MARKET", material.name(), amount,
                quote.net(), quote.tax(), item.getStock(), null);

        return new TradeOutcome(Result.OK, amount, quote,
                player.hasPermission("market.limit.bypass")
                        ? Integer.MAX_VALUE : limiter.remaining(player.getUniqueId(), material));
    }

    /**
     * Credit a sale whose items have already been removed from somewhere other than
     * the player's own inventory - a chest, for instance.
     *
     * <p>Split out from {@link #executeSell} because that method takes the items out
     * of the player inventory itself. Calling it after emptying a chest would try to
     * remove the same items twice and fail, or worse, take a second copy from the
     * player's own bag.
     */
    public TradeOutcome settleExternalSale(Player player, Material material, int amount) {
        if (amount <= 0) {
            return TradeOutcome.fail(Result.INVALID_ITEM);
        }
        MarketItem item = items.get(material);
        if (item == null || !item.isEnabled()) {
            return TradeOutcome.fail(Result.NOT_CONFIGURED);
        }
        refreshDerivedPrices();
        PricingEngine.Quote quote =
                PricingEngine.quoteSell(item, item.getStock(), amount, plugin.configs().taxRate());

        item.addStock(amount);
        valuation.invalidate();
        plugin.economy().deposit(player.getUniqueId(), quote.net());
        plugin.economy().burn(quote.tax());
        limiter.record(player.getUniqueId(), material, amount);
        plugin.logTransaction(player.getUniqueId(), "SELL_MARKET", material.name(), amount,
                quote.net(), quote.tax(), item.getStock(), "chest");
        return new TradeOutcome(Result.OK, amount, quote, Integer.MAX_VALUE);
    }

    /** Price a purchase without committing it. */
    public TradeOutcome quoteBuy(Player player, Material material, int quantity) {
        refreshDerivedPrices();
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
        valuation.invalidate();
        plugin.economy().burn(quote.tax());
        giveItems(player, material, quantity);

        plugin.logTransaction(player.getUniqueId(), "BUY_MARKET", material.name(), quantity,
                quote.net(), quote.tax(), item.getStock(), null);

        return new TradeOutcome(Result.OK, quantity, quote, Integer.MAX_VALUE);
    }

    // --------------------------------------------------------------- inventory

    public int countIn(PlayerInventory inventory, Material material) {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.getType() == material && isSellableStack(stack)) {
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
    public boolean isSellableStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        org.bukkit.inventory.meta.ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta == null) {
            return true;
        }
        if (meta.hasDisplayName() || meta.hasEnchants() || meta.hasLore()) {
            return false;
        }
        // Books keep their enchantments in EnchantmentStorageMeta, NOT in the
        // ordinary enchantment map - so hasEnchants() is false for a Mending book
        // and it would otherwise sell to the cloud at plain-material price,
        // destroying the only thing that made it valuable.
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storage
                && storage.hasStoredEnchants()) {
            return false;
        }
        // Damaged tools and armour. A worn diamond pickaxe still contains three
        // diamonds by recipe, so paying derived value for it would let a player mine
        // an item to the brink of breaking and still cash it out whole.
        if (refuseDamaged && meta instanceof org.bukkit.inventory.meta.Damageable damageable
                && damageable.hasDamage()) {
            return false;
        }
        return true;
    }

    private void removeFrom(PlayerInventory inventory, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material || !isSellableStack(stack)) {
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

    private int freeSpaceFor(PlayerInventory inventory, Material material) {
        int max = material.getMaxStackSize();
        int space = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                space += max;
            } else if (stack.getType() == material && isSellableStack(stack)) {
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
