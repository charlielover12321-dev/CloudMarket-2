package com.cloudmarket.ui;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.market.Category;
import com.cloudmarket.market.MarketItem;
import com.cloudmarket.market.PricingEngine;
import com.cloudmarket.blackmarket.Listing;
import com.cloudmarket.shops.ShopChest;
import com.cloudmarket.util.Fmt;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds every inventory screen the plugin shows.
 *
 * <p>All screens are plain chest inventories. Geyser translates those to Bedrock
 * container UIs without help, so one code path serves both editions. The only place
 * the editions diverge is free-text entry, which is routed through
 * {@link BedrockBridge}.
 */
public final class GuiManager {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int CONTENT_SLOTS = 45;

    private final CloudMarket plugin;

    public GuiManager(CloudMarket plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------- global market

    /**
     * Slots the category tiles occupy, in order. Two centred rows, which leaves
     * room for more categories than currently exist without another layout change.
     */
    public static final int[] CATEGORY_SLOTS =
            {10, 11, 12, 13, 14, 15, 16,
             19, 20, 21, 22, 23, 24, 25,
             28, 29, 30, 31, 32, 33, 34};

    public void openCategories(Player player) {
        plugin.market().refreshDerivedPrices();
        MarketHolder holder = MarketHolder.categories();
        Inventory inventory = Bukkit.createInventory(holder, 45,
                plugin.configs().messages().bare("gui.market-title", Map.of()));
        holder.setInventory(inventory);

        Map<Category, Integer> counts = plugin.market().categoryCounts();
        Category[] categories = Category.values();
        for (int index = 0; index < categories.length && index < CATEGORY_SLOTS.length; index++) {
            Category category = categories[index];
            inventory.setItem(CATEGORY_SLOTS[index], icon(category.getIcon(),
                    "&b&l" + category.getDisplayName(),
                    List.of("&7" + counts.getOrDefault(category, 0) + " items available",
                            "",
                            "&eClick to browse")));
        }
        inventory.setItem(40, balanceIcon(player));
        player.openInventory(inventory);
    }

    public void openCategory(Player player, Category category, int page) {
        // Crafted prices move whenever an ingredient's stock does, so re-derive
        // before painting the screen rather than showing a stale number the player
        // would then be charged a different amount for.
        plugin.market().refreshDerivedPrices();
        // Padded layout: null entries are deliberate row breaks between families.
        List<MarketItem> all = plugin.market().layout(category);
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) CONTENT_SLOTS));
        int safePage = Math.max(0, Math.min(page, pages - 1));

        MarketHolder holder = MarketHolder.items(category, safePage);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.configs().messages().bare("gui.category-title", Map.of(
                        "category", category.getDisplayName(),
                        "page", String.valueOf(safePage + 1),
                        "pages", String.valueOf(pages))));
        holder.setInventory(inventory);

        int start = safePage * CONTENT_SLOTS;
        for (int offset = 0; offset < CONTENT_SLOTS && start + offset < all.size(); offset++) {
            MarketItem entry = all.get(start + offset);
            if (entry == null) {
                continue;
            }
            inventory.setItem(offset, marketIcon(player, entry));
        }

        inventory.setItem(45, icon(Material.ARROW, "&e&lBack", List.of("&7Return to categories")));
        if (safePage > 0) {
            inventory.setItem(48, icon(Material.PAPER, "&e&lPrevious page", List.of()));
        }
        if (safePage < pages - 1) {
            inventory.setItem(50, icon(Material.PAPER, "&e&lNext page", List.of()));
        }
        inventory.setItem(49, balanceIcon(player));
        player.openInventory(inventory);
    }

    /**
     * One market item, showing what it would cost to buy one and what the player
     * would be paid for one right now. Both figures come from the same batch pricer
     * used by the real transaction, so the number on the icon is the number the
     * player gets rather than an approximation from the spot curve.
     */
    private ItemStack marketIcon(Player player, MarketItem item) {
        long stock = item.getStock();
        BigDecimal spot = PricingEngine.spotPrice(item, stock);
        String symbol = plugin.configs().currencySymbol();

        List<String> lore = new ArrayList<>();
        lore.add("&7Stock in the cloud: &f" + Fmt.count(stock));
        lore.add("&7Current price: &a" + symbol + Fmt.money(spot));
        lore.add("");

        if (stock > 0) {
            PricingEngine.Quote buyOne =
                    PricingEngine.quoteBuy(item, stock, 1, plugin.configs().taxRate());
            lore.add("&7Buy 1 for &c" + symbol + Fmt.money(buyOne.net()));
            int stackSize = Math.min(item.getMaterial().getMaxStackSize(), (int) Math.min(stock, 64L));
            if (stackSize > 1) {
                PricingEngine.Quote buyStack =
                        PricingEngine.quoteBuy(item, stock, stackSize, plugin.configs().taxRate());
                lore.add("&7Buy " + stackSize + " for &c" + symbol + Fmt.money(buyStack.net()));
            }
        } else {
            lore.add("&8Nobody has sold any of this yet.");
        }

        PricingEngine.Quote sellOne =
                PricingEngine.quoteSell(item, stock, 1, plugin.configs().taxRate());
        lore.add("&7Sell 1 for &a" + symbol + Fmt.money(sellOne.net()));
        lore.add("");
        lore.add("&eLeft-click &7buy one");
        lore.add("&eShift-left &7buy a stack");
        lore.add("&eRight-click &7choose an amount");

        if (plugin.market().limiter().isEnabled() && !player.hasPermission("market.limit.bypass")) {
            int remaining = plugin.market().limiter()
                    .remaining(player.getUniqueId(), item.getMaterial());
            lore.add("");
            lore.add("&8You may sell " + Fmt.count(remaining) + " more this hour.");
        }

        return icon(item.getMaterial(), "&f&l" + Fmt.pretty(item.getMaterial()), lore);
    }

    // ------------------------------------------------------------ black market

    /**
     * Browse everything on sale.
     *
     * <p>Each icon is the real listed item, so an enchanted pickaxe shows its
     * enchantment glint and a spawner shows whatever your spawner plugin wrote onto
     * it. The stack amount is set to the remaining quantity, which is what puts the
     * live count in the corner of the icon - it updates itself as people buy.
     */
    public void openBlackMarket(Player player, int page) {
        List<Listing> all = plugin.blackMarket().active();
        renderListings(player, MarketHolder.blackMarket(page), all, page,
                plugin.configs().messages().bare("gui.blackmarket-title", Map.of()), false);
    }

    /** A seller's own listings, including expired ones waiting to be collected. */
    public void openMyListings(Player player, int page) {
        List<Listing> mine = plugin.blackMarket().of(player.getUniqueId());
        renderListings(player, MarketHolder.myListings(page), mine, page,
                plugin.configs().messages().bare("gui.mylistings-title", Map.of(
                        "used", String.valueOf(mine.size()),
                        "limit", String.valueOf(plugin.blackMarket().slotLimit()))), true);
    }

    private void renderListings(Player player, MarketHolder holder, List<Listing> all, int page,
                                Component title, boolean owned) {
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) CONTENT_SLOTS));
        int safePage = Math.max(0, Math.min(page, pages - 1));
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title);
        holder.setInventory(inventory);

        String symbol = plugin.configs().currencySymbol();
        int start = safePage * CONTENT_SLOTS;
        for (int offset = 0; offset < CONTENT_SLOTS && start + offset < all.size(); offset++) {
            Listing listing = all.get(start + offset);
            List<String> lore = new ArrayList<>();
            // Spell the enchantments out rather than relying on the client to render
            // them from NBT. On a book especially, the enchantment IS the item, and a
            // buyer should never have to guess what they are paying for.
            List<String> enchants =
                    com.cloudmarket.util.ItemCodec.enchantmentLines(listing.getTemplate());
            if (!enchants.isEmpty()) {
                for (String line : enchants) {
                    lore.add("&b" + line);
                }
                lore.add("");
            }
            lore.add("&7Price: &a" + symbol + Fmt.money(listing.getUnitPrice()) + " &7each");
            lore.add("&7Remaining: &f" + Fmt.count(listing.getRemaining()));
            lore.add("&7All of it: &a" + symbol + Fmt.money(listing.totalValue()));
            lore.add("");
            if (owned) {
                lore.add(listing.isExpired()
                        ? "&cExpired - click to collect"
                        : "&7Expires in &f" + listing.daysRemaining() + " &7days");
                lore.add("&eClick &7to pull this listing and take the items back");
            } else {
                lore.add("&7Seller: &f" + plugin.economy().nameOf(listing.getSeller()));
                lore.add("&7Expires in &f" + listing.daysRemaining() + " &7days");
                lore.add("");
                lore.add("&eLeft-click &7buy one");
                lore.add("&eShift-left &7buy the lot");
                lore.add("&eRight-click &7choose an amount");
            }
            // The real item, at the remaining count, so the number in the corner of
            // the icon is the live stock.
            inventory.setItem(offset, decorate(listing.copyOf(
                    Math.min(listing.getRemaining(), listing.getTemplate().getMaxStackSize())), lore));
        }

        if (all.isEmpty()) {
            inventory.setItem(22, icon(Material.BARRIER,
                    owned ? "&cYou have nothing listed" : "&cNothing for sale right now",
                    owned ? List.of("&7Hold an item and run", "&e/auction hand <price>")
                            : List.of("&7Check back later.")));
        }

        if (safePage > 0) {
            inventory.setItem(48, icon(Material.PAPER, "&e&lPrevious page", List.of()));
        }
        if (safePage < pages - 1) {
            inventory.setItem(50, icon(Material.PAPER, "&e&lNext page", List.of()));
        }
        inventory.setItem(49, balanceIcon(player));
        player.openInventory(inventory);
    }

    /**
     * Add lore to a real item without touching anything else about it. Used for
     * listing icons, where replacing the stack would lose the enchantments and NBT
     * that are the whole reason the item is worth buying.
     */
    private ItemStack decorate(ItemStack stack, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<Component> lines = new ArrayList<>();
            if (meta.hasLore() && meta.lore() != null) {
                lines.addAll(meta.lore());
                lines.add(Component.empty());
            }
            for (String line : lore) {
                lines.add(Fmt.color(line));
            }
            meta.lore(lines);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ------------------------------------------------------------- shop chests

    /**
     * @param preview true when the owner (or an admin) is looking at their own shop;
     *                the buy hints are replaced with a reminder about how to restock
     */
    public void openShopChest(Player player, ShopChest chest, boolean preview) {
        List<ShopChest.Offer> listed = plugin.shopChests().listedOffers(chest);
        MarketHolder holder = MarketHolder.shopChest(chest);
        int rows = Math.max(3, Math.min(6, (int) Math.ceil(listed.size() / 9.0d) + 1));
        Inventory inventory = Bukkit.createInventory(holder, rows * 9,
                plugin.configs().messages().bare("gui.shopchest-title", Map.of(
                        "owner", plugin.economy().nameOf(chest.getOwner()))));
        holder.setInventory(inventory);

        String symbol = plugin.configs().currencySymbol();
        int slot = 0;
        for (ShopChest.Offer offer : listed) {
            if (slot >= (rows - 1) * 9) {
                break;
            }
            int stock = plugin.shopChests().stockOf(chest, offer);
            List<String> lore = new ArrayList<>();
            // The real item is the icon, so enchantments, custom names and NBT all
            // show. Previously this built a fresh stack from the material, which is
            // why every enchanted book looked identical and blank.
            List<String> enchants =
                    com.cloudmarket.util.ItemCodec.enchantmentLines(offer.icon(1));
            if (!enchants.isEmpty()) {
                for (String line : enchants) {
                    lore.add("&b" + line);
                }
                lore.add("");
            }
            lore.add("&7Price: &a" + symbol + Fmt.money(offer.price()) + " &7each");
            lore.add("&7In stock: &f" + Fmt.count(stock));
            lore.add("");
            if (preview) {
                lore.add("&8This is your shop.");
                lore.add("&8Sneak + right-click the chest to restock.");
            } else if (stock <= 0) {
                lore.add("&cOut of stock.");
            } else {
                lore.add("&eLeft-click &7buy one");
                lore.add("&eShift-left &7buy a stack");
                lore.add("&eRight-click &7choose an amount");
            }
            inventory.setItem(slot++, decorate(offer.icon(Math.max(1, stock)), lore));
        }

        if (listed.isEmpty()) {
            inventory.setItem(inventory.getSize() / 2, icon(Material.BARRIER, "&cNothing for sale",
                    preview
                            ? List.of("&7Hold an item and run", "&e/shopchest additem <price>")
                            : List.of("&7The owner has not listed anything yet.")));
        }

        inventory.setItem(inventory.getSize() - 5, balanceIcon(player));
        player.openInventory(inventory);
    }

    // ------------------------------------------------------------------ pieces

    private ItemStack balanceIcon(Player player) {
        return icon(Material.SUNFLOWER, "&6&lYour balance",
                List.of("&f" + plugin.configs().currencySymbol()
                        + Fmt.money(plugin.economy().getBalance(player.getUniqueId()))));
    }

    /**
     * Build a display icon. Falls back to a barrier if the material cannot exist as
     * an item, which keeps a config typo or a block-only material from throwing
     * while a player has the menu open.
     */
    private ItemStack icon(Material material, String name, List<String> lore) {
        Material safe = material;
        if (safe == null || safe.isAir() || !safe.isItem()) {
            safe = Material.BARRIER;
        }
        ItemStack stack = new ItemStack(safe);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Fmt.color(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Fmt.color(line));
            }
            meta.lore(lines);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
