package com.cloudmarket.ui;

import com.cloudmarket.market.Category;
import com.cloudmarket.shops.ShopChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as belonging to CloudMarket, and carries the state of the view.
 *
 * <p>Using a holder rather than comparing inventory titles matters for Bedrock:
 * Geyser does not round-trip a Java inventory title byte-for-byte, so title
 * matching silently stops recognising a Bedrock player's own open GUI and their
 * clicks fall through to vanilla inventory handling.
 */
public final class MarketHolder implements InventoryHolder {

    public enum View {
        CATEGORIES,
        ITEMS,
        SHOP_CHEST,
        BLACK_MARKET,
        MY_LISTINGS
    }

    private final View view;
    private final Category category;
    private final int page;
    private final ShopChest chest;
    private Inventory inventory;

    private MarketHolder(View view, Category category, int page, ShopChest chest) {
        this.view = view;
        this.category = category;
        this.page = page;
        this.chest = chest;
    }

    public static MarketHolder categories() {
        return new MarketHolder(View.CATEGORIES, null, 0, null);
    }

    public static MarketHolder items(Category category, int page) {
        return new MarketHolder(View.ITEMS, category, page, null);
    }

    public static MarketHolder shopChest(ShopChest chest) {
        return new MarketHolder(View.SHOP_CHEST, null, 0, chest);
    }

    public static MarketHolder blackMarket(int page) {
        return new MarketHolder(View.BLACK_MARKET, null, page, null);
    }

    public static MarketHolder myListings(int page) {
        return new MarketHolder(View.MY_LISTINGS, null, page, null);
    }

    public View getView() {
        return view;
    }

    public Category getCategory() {
        return category;
    }

    public int getPage() {
        return page;
    }

    public ShopChest getChest() {
        return chest;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
