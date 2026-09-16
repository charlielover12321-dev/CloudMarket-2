package com.cloudmarket.ui;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.market.Category;
import com.cloudmarket.market.MarketItem;
import com.cloudmarket.market.MarketManager;
import com.cloudmarket.blackmarket.BlackMarketManager;
import com.cloudmarket.blackmarket.Listing;
import com.cloudmarket.shops.ShopChest;
import com.cloudmarket.shops.ShopChestManager;
import com.cloudmarket.util.Fmt;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Turns clicks in a CloudMarket GUI into transactions.
 *
 * <p>Every click in one of our inventories is cancelled unconditionally and up
 * front. These are menus, not containers, and an uncancelled click would let a
 * player pull the display icons out and keep them - which for a market GUI means
 * free diamonds.
 */
public final class GuiListener implements Listener {

    private final CloudMarket plugin;

    public GuiListener(CloudMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MarketHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MarketHolder holder)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Clicks in the player's own inventory while a menu is open are already
        // cancelled above; nothing further to do with them.
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }

        switch (holder.getView()) {
            case CATEGORIES -> handleCategories(player, event.getSlot());
            case ITEMS -> handleItems(player, holder, event.getSlot(), event.getClick());
            case SHOP_CHEST -> handleShopChest(player, holder, event.getSlot(), event.getClick(),
                    top.getSize());
            case BLACK_MARKET -> handleBlackMarket(player, holder, event.getSlot(), event.getClick());
            case MY_LISTINGS -> handleMyListings(player, holder, event.getSlot());
        }
    }

    /**
     * Run something on the next tick.
     *
     * <p>Opening or closing an inventory from inside {@link InventoryClickEvent} is
     * unsafe: the server is still part-way through processing the click against the
     * inventory that is about to be replaced. Doing it inline leaves the client and
     * server disagreeing about which window is open, which shows up as a ghost item
     * stuck to the cursor or a menu that will not close. Deferring a single tick
     * lets the click finish first.
     */
    private void later(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    private void handleCategories(Player player, int slot) {
        int[] slots = GuiManager.CATEGORY_SLOTS;
        Category[] categories = Category.values();
        for (int index = 0; index < slots.length && index < categories.length; index++) {
            if (slots[index] == slot) {
                Category category = categories[index];
                later(() -> plugin.gui().openCategory(player, category, 0));
                return;
            }
        }
    }

    private void handleItems(Player player, MarketHolder holder, int slot, ClickType click) {
        if (slot == 45) {
            later(() -> plugin.gui().openCategories(player));
            return;
        }
        if (slot == 48) {
            later(() -> plugin.gui().openCategory(player, holder.getCategory(), holder.getPage() - 1));
            return;
        }
        if (slot == 50) {
            later(() -> plugin.gui().openCategory(player, holder.getCategory(), holder.getPage() + 1));
            return;
        }
        if (slot >= 45) {
            return;
        }

        // Must be the same padded layout the GUI drew from. Using the unpadded
        // list here would offset every index past the first family break and buy
        // the wrong item.
        List<MarketItem> all = plugin.market().layout(holder.getCategory());
        int index = holder.getPage() * 45 + slot;
        if (index < 0 || index >= all.size()) {
            return;
        }
        MarketItem item = all.get(index);
        if (item == null) {
            return;
        }

        // Bedrock has no shift-click or right-click inside a container: Geyser
        // delivers every tap as a plain left-click. A three-way click scheme
        // therefore collapses to "buy 1" and nothing else for Bedrock players, so
        // any tap opens the quantity form instead - which is the better interaction
        // on a controller or a touchscreen regardless.
        boolean bedrock = plugin.bedrock().isBedrock(player);
        if (bedrock || click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            int max = (int) Math.min(item.getStock(), 2304L);
            if (max <= 0) {
                plugin.configs().messages().send(player, "market.no-stock",
                        Map.of("item", Fmt.pretty(item.getMaterial())));
                return;
            }
            later(() -> {
                player.closeInventory();
                plugin.bedrock().promptForNumber(player, "Buy " + Fmt.pretty(item.getMaterial()),
                        "How many would you like to buy?", 1, max,
                        amount -> buyMarket(player, item.getMaterial(), amount));
            });
            return;
        }

        int amount = click.isShiftClick() ? item.getMaterial().getMaxStackSize() : 1;
        buyMarket(player, item.getMaterial(), amount);
        later(() -> {
            // Refresh so the price and stock on the icon reflect the trade, but only
            // if the player is still looking at one of our menus - they may have
            // closed it during the tick we waited.
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MarketHolder) {
                plugin.gui().openCategory(player, holder.getCategory(), holder.getPage());
            }
        });
    }

    private void buyMarket(Player player, Material material, int amount) {
        MarketManager.TradeOutcome outcome = plugin.market().executeBuy(player, material, amount);
        String symbol = plugin.configs().currencySymbol();
        switch (outcome.result()) {
            case OK -> plugin.configs().messages().send(player, "market.bought", Map.of(
                    "amount", String.valueOf(outcome.quantity()),
                    "item", Fmt.pretty(material),
                    "total", Fmt.money(outcome.quote().net()),
                    "tax", Fmt.money(outcome.quote().tax()),
                    "symbol", symbol));
            case NOT_ENOUGH_STOCK -> plugin.configs().messages().send(player, "market.no-stock",
                    Map.of("item", Fmt.pretty(material)));
            case NOT_ENOUGH_MONEY -> plugin.configs().messages().send(player, "market.cannot-afford");
            case NO_INVENTORY_SPACE -> plugin.configs().messages().send(player, "market.inventory-full");
            case DISABLED -> plugin.configs().messages().send(player, "market.disabled",
                    Map.of("item", Fmt.pretty(material)));
            default -> plugin.configs().messages().send(player, "market.not-configured",
                    Map.of("item", Fmt.pretty(material)));
        }
    }

    private void handleShopChest(Player player, MarketHolder holder, int slot, ClickType click,
                                 int inventorySize) {
        ShopChest chest = holder.getChest();
        if (chest == null) {
            return;
        }
        // The bottom row is the footer (balance icon), not listings. Without this
        // check a click there would index straight into the listings array, so on a
        // shop with enough items the balance icon would silently buy whatever
        // happened to sit at that index.
        int contentSlots = Math.max(0, inventorySize - 9);
        if (slot < 0 || slot >= contentSlots) {
            return;
        }
        List<ShopChest.Offer> listed = plugin.shopChests().listedOffers(chest);
        if (slot >= listed.size()) {
            return;
        }
        ShopChest.Offer offer = listed.get(slot);

        if (chest.getOwner().equals(player.getUniqueId())) {
            plugin.configs().messages().send(player, "shopchest.own-shop");
            return;
        }

        boolean bedrock = plugin.bedrock().isBedrock(player);
        if (bedrock || click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            int available = plugin.shopChests().stockOf(chest, offer);
            if (available <= 0) {
                plugin.configs().messages().send(player, "shopchest.out-of-stock",
                        Map.of("item", com.cloudmarket.util.ItemCodec.describe(offer.icon(1))));
                return;
            }
            later(() -> {
                player.closeInventory();
                plugin.bedrock().promptForNumber(player,
                        "Buy " + com.cloudmarket.util.ItemCodec.describe(offer.icon(1)),
                        "How many would you like to buy?", 1, available,
                        amount -> buyChest(player, chest, offer, amount));
            });
            return;
        }

        int amount = click.isShiftClick() ? offer.icon(1).getMaxStackSize() : 1;
        buyChest(player, chest, offer, amount);
        later(() -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MarketHolder) {
                plugin.gui().openShopChest(player, chest, false);
            }
        });
    }

    private void handleBlackMarket(Player player, MarketHolder holder, int slot, ClickType click) {
        if (slot == 48) {
            later(() -> plugin.gui().openBlackMarket(player, holder.getPage() - 1));
            return;
        }
        if (slot == 50) {
            later(() -> plugin.gui().openBlackMarket(player, holder.getPage() + 1));
            return;
        }
        if (slot >= 45) {
            return;
        }
        List<Listing> all = plugin.blackMarket().active();
        int index = holder.getPage() * 45 + slot;
        if (index < 0 || index >= all.size()) {
            return;
        }
        Listing listing = all.get(index);
        if (listing.getSeller().equals(player.getUniqueId())) {
            plugin.configs().messages().send(player, "auction.own-listing");
            return;
        }

        boolean bedrock = plugin.bedrock().isBedrock(player);
        if (bedrock || click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            int max = listing.getRemaining();
            later(() -> {
                player.closeInventory();
                plugin.bedrock().promptForNumber(player,
                        "Buy " + com.cloudmarket.util.ItemCodec.describe(listing.getTemplate()),
                        "How many? " + plugin.configs().currencySymbol()
                                + Fmt.money(listing.getUnitPrice()) + " each", 1, max,
                        amount -> buyListing(player, listing.getId(), amount));
            });
            return;
        }

        int amount = click.isShiftClick() ? listing.getRemaining() : 1;
        buyListing(player, listing.getId(), amount);
        later(() -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MarketHolder) {
                plugin.gui().openBlackMarket(player, holder.getPage());
            }
        });
    }

    private void buyListing(Player player, int listingId, int amount) {
        BlackMarketManager.Purchase purchase = plugin.blackMarket().buy(player, listingId, amount);
        String symbol = plugin.configs().currencySymbol();
        Listing listing = plugin.blackMarket().get(listingId);
        String name = listing == null ? "item"
                : com.cloudmarket.util.ItemCodec.describe(listing.getTemplate());
        switch (purchase.result()) {
            case OK -> plugin.configs().messages().send(player, "auction.bought", Map.of(
                    "amount", String.valueOf(purchase.quantity()),
                    "item", name,
                    "total", Fmt.money(purchase.total()),
                    "symbol", symbol));
            case OUT_OF_STOCK, NOT_FOUND -> plugin.configs().messages()
                    .send(player, "auction.gone");
            case EXPIRED -> plugin.configs().messages().send(player, "auction.expired");
            case OWN_LISTING -> plugin.configs().messages().send(player, "auction.own-listing");
            case NOT_ENOUGH_MONEY -> plugin.configs().messages().send(player, "market.cannot-afford");
            case NO_INVENTORY_SPACE -> plugin.configs().messages().send(player, "market.inventory-full");
            default -> plugin.configs().messages().send(player, "auction.gone");
        }
    }

    private void handleMyListings(Player player, MarketHolder holder, int slot) {
        if (slot == 48) {
            later(() -> plugin.gui().openMyListings(player, holder.getPage() - 1));
            return;
        }
        if (slot == 50) {
            later(() -> plugin.gui().openMyListings(player, holder.getPage() + 1));
            return;
        }
        if (slot >= 45) {
            return;
        }
        List<Listing> mine = plugin.blackMarket().of(player.getUniqueId());
        int index = holder.getPage() * 45 + slot;
        if (index < 0 || index >= mine.size()) {
            return;
        }
        Listing listing = mine.get(index);
        BlackMarketManager.Result result = plugin.blackMarket().cancel(player, listing.getId());
        switch (result) {
            case OK -> plugin.configs().messages().send(player, "auction.pulled", Map.of(
                    "item", com.cloudmarket.util.ItemCodec.describe(listing.getTemplate())));
            case NO_INVENTORY_SPACE -> plugin.configs().messages()
                    .send(player, "market.inventory-full");
            case NOT_YOURS -> plugin.configs().messages().send(player, "auction.not-yours");
            default -> plugin.configs().messages().send(player, "auction.gone");
        }
        later(() -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MarketHolder) {
                plugin.gui().openMyListings(player, holder.getPage());
            }
        });
    }

    private void buyChest(Player player, ShopChest chest, ShopChest.Offer offer, int amount) {
        ShopChestManager.Purchase purchase =
                plugin.shopChests().buy(player, chest, offer.key(), amount);
        String symbol = plugin.configs().currencySymbol();
        String material = com.cloudmarket.util.ItemCodec.describe(offer.icon(1));
        switch (purchase.result()) {
            case OK -> plugin.configs().messages().send(player, "shopchest.bought", Map.of(
                    "amount", String.valueOf(purchase.quantity()),
                    "item", material,
                    "total", Fmt.money(purchase.total()),
                    "owner", plugin.economy().nameOf(chest.getOwner()),
                    "symbol", symbol));
            case OUT_OF_STOCK -> plugin.configs().messages().send(player, "shopchest.out-of-stock",
                    Map.of("item", material));
            case NOT_ENOUGH_MONEY -> plugin.configs().messages().send(player, "market.cannot-afford");
            case NO_INVENTORY_SPACE -> plugin.configs().messages().send(player, "market.inventory-full");
            case OWN_SHOP -> plugin.configs().messages().send(player, "shopchest.own-shop");
            default -> plugin.configs().messages().send(player, "shopchest.not-listed",
                    Map.of("item", material));
        }
    }
}
