package com.cloudmarket.ui;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.market.Category;
import com.cloudmarket.market.MarketItem;
import com.cloudmarket.market.MarketManager;
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
        int[] slots = {10, 11, 12, 13, 14};
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

        List<MarketItem> all = plugin.market().byCategory(holder.getCategory());
        int index = holder.getPage() * 45 + slot;
        if (index < 0 || index >= all.size()) {
            return;
        }
        MarketItem item = all.get(index);

        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
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
        List<Material> listed = plugin.shopChests().listedMaterials(chest);
        if (slot >= listed.size()) {
            return;
        }
        Material material = listed.get(slot);

        if (chest.getOwner().equals(player.getUniqueId())) {
            plugin.configs().messages().send(player, "shopchest.own-shop");
            return;
        }

        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            int available = plugin.shopChests().stockOf(chest, material);
            if (available <= 0) {
                plugin.configs().messages().send(player, "shopchest.out-of-stock",
                        Map.of("item", Fmt.pretty(material)));
                return;
            }
            later(() -> {
                player.closeInventory();
                plugin.bedrock().promptForNumber(player, "Buy " + Fmt.pretty(material),
                        "How many would you like to buy?", 1, available,
                        amount -> buyChest(player, chest, material, amount));
            });
            return;
        }

        int amount = click.isShiftClick() ? material.getMaxStackSize() : 1;
        buyChest(player, chest, material, amount);
        later(() -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MarketHolder) {
                plugin.gui().openShopChest(player, chest, false);
            }
        });
    }

    private void buyChest(Player player, ShopChest chest, Material material, int amount) {
        ShopChestManager.Purchase purchase = plugin.shopChests().buy(player, chest, material, amount);
        String symbol = plugin.configs().currencySymbol();
        switch (purchase.result()) {
            case OK -> plugin.configs().messages().send(player, "shopchest.bought", Map.of(
                    "amount", String.valueOf(purchase.quantity()),
                    "item", Fmt.pretty(material),
                    "total", Fmt.money(purchase.total()),
                    "owner", plugin.economy().nameOf(chest.getOwner()),
                    "symbol", symbol));
            case OUT_OF_STOCK -> plugin.configs().messages().send(player, "shopchest.out-of-stock",
                    Map.of("item", Fmt.pretty(material)));
            case NOT_ENOUGH_MONEY -> plugin.configs().messages().send(player, "market.cannot-afford");
            case NO_INVENTORY_SPACE -> plugin.configs().messages().send(player, "market.inventory-full");
            case OWN_SHOP -> plugin.configs().messages().send(player, "shopchest.own-shop");
            default -> plugin.configs().messages().send(player, "shopchest.not-listed",
                    Map.of("item", Fmt.pretty(material)));
        }
    }
}
