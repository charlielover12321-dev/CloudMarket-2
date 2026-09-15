package com.cloudmarket.blackmarket;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.storage.SqlStorage;
import com.cloudmarket.util.ItemCodec;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The black market: player-set prices on arbitrary items, including the ones the
 * cloud market refuses.
 *
 * <p>Unlike the cloud market there is no curve and no derived value here - a seller
 * names a price per unit and buyers take it or leave it. And unlike shop chests
 * there is no physical container, so the items live in escrow inside the listing.
 */
public final class BlackMarketManager {

    public enum Result {
        OK,
        NOT_FOUND,
        EXPIRED,
        OUT_OF_STOCK,
        OWN_LISTING,
        NOT_ENOUGH_MONEY,
        NO_INVENTORY_SPACE,
        SLOT_LIMIT,
        INVALID,
        NOT_YOURS,
        STORAGE_ERROR
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
    private final Map<Integer, Listing> listings = new ConcurrentHashMap<>();

    public BlackMarketManager(CloudMarket plugin) {
        this.plugin = plugin;
    }

    public void load(List<SqlStorage.ListingData> rows) {
        listings.clear();
        int unreadable = 0;
        for (SqlStorage.ListingData row : rows) {
            ItemStack template = ItemCodec.decode(row.itemData());
            if (template == null) {
                // One corrupt row must not take the whole market down.
                unreadable++;
                continue;
            }
            listings.put(row.id(), new Listing(row.id(), row.seller(), template,
                    row.quantity(), row.price(), row.listedAt(), row.expiresAt()));
        }
        plugin.getLogger().info("[CloudMarket] Black market: " + listings.size() + " listings loaded"
                + (unreadable > 0 ? ", " + unreadable + " could not be decoded and were skipped" : "")
                + ".");
    }

    public Listing get(int id) {
        return listings.get(id);
    }

    /** Everything currently on sale, newest first. */
    public List<Listing> active() {
        List<Listing> out = new ArrayList<>();
        for (Listing listing : listings.values()) {
            if (listing.isActive()) {
                out.add(listing);
            }
        }
        out.sort(Comparator.comparingLong(Listing::getListedAt).reversed());
        return out;
    }

    /** A player's own listings, including expired ones awaiting collection. */
    public List<Listing> of(UUID seller) {
        List<Listing> out = new ArrayList<>();
        for (Listing listing : listings.values()) {
            if (listing.getSeller().equals(seller) && listing.isReclaimable()) {
                out.add(listing);
            }
        }
        out.sort(Comparator.comparingLong(Listing::getListedAt).reversed());
        return out;
    }

    /**
     * Slots a player is using. Expired-but-uncollected listings count, which nudges
     * sellers to collect rather than leaving dead listings parked forever.
     */
    public int slotsUsed(UUID seller) {
        return of(seller).size();
    }

    public int slotLimit() {
        return plugin.configs().auctionSlots();
    }

    /**
     * List the stack in the player's main hand. Takes the items immediately.
     *
     * @param unitPrice price per single unit, not for the whole stack
     */
    public Result list(Player player, BigDecimal unitPrice) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir() || hand.getAmount() <= 0) {
            return Result.INVALID;
        }
        if (unitPrice == null || unitPrice.signum() <= 0) {
            return Result.INVALID;
        }
        if (slotsUsed(player.getUniqueId()) >= slotLimit()) {
            return Result.SLOT_LIMIT;
        }

        ItemStack template = hand.clone();
        int quantity = template.getAmount();
        template.setAmount(1);

        String encoded = ItemCodec.encode(template);
        if (encoded == null) {
            return Result.STORAGE_ERROR;
        }

        long now = System.currentTimeMillis();
        long expires = now + plugin.configs().auctionDurationDays() * 86_400_000L;

        int id;
        try {
            id = plugin.storage().insertListing(player.getUniqueId(), encoded,
                    ItemCodec.describe(template), quantity, unitPrice, now, expires);
        } catch (Exception e) {
            plugin.getLogger().warning("[CloudMarket] Could not save listing: " + e.getMessage());
            return Result.STORAGE_ERROR;
        }
        if (id < 0) {
            return Result.STORAGE_ERROR;
        }

        // Take the items only after the listing is safely persisted. If the write
        // had failed after removing them, the player would be out a dragon egg with
        // nothing to show for it.
        player.getInventory().setItemInMainHand(null);
        listings.put(id, new Listing(id, player.getUniqueId(), template, quantity,
                unitPrice, now, expires));

        plugin.logTransaction(player.getUniqueId(), "AUCTION_LIST", template.getType().name(),
                quantity, unitPrice.multiply(BigDecimal.valueOf(quantity)), BigDecimal.ZERO, 0L, null);
        return Result.OK;
    }

    /**
     * Buy some or all of a listing. Full price for whatever quantity is taken - no
     * instalments, no partial payment.
     */
    public Purchase buy(Player buyer, int listingId, int quantity) {
        Listing listing = listings.get(listingId);
        if (listing == null) {
            return Purchase.fail(Result.NOT_FOUND);
        }
        if (listing.getSeller().equals(buyer.getUniqueId())) {
            return Purchase.fail(Result.OWN_LISTING);
        }
        if (listing.isExpired()) {
            return Purchase.fail(Result.EXPIRED);
        }
        if (quantity <= 0) {
            return Purchase.fail(Result.INVALID);
        }

        int amount;
        BigDecimal total;
        // Serialised per listing so two buyers cannot both claim the last spawner.
        synchronized (listing) {
            if (listing.getRemaining() <= 0) {
                return Purchase.fail(Result.OUT_OF_STOCK);
            }
            amount = Math.min(quantity, listing.getRemaining());
            total = listing.priceFor(amount);

            if (!plugin.economy().has(buyer.getUniqueId(), total)) {
                return Purchase.fail(Result.NOT_ENOUGH_MONEY);
            }
            if (freeSpace(buyer, listing.getTemplate()) < amount) {
                return Purchase.fail(Result.NO_INVENTORY_SPACE);
            }
            if (!plugin.economy().withdraw(buyer.getUniqueId(), total)) {
                return Purchase.fail(Result.NOT_ENOUGH_MONEY);
            }
            listing.setRemaining(listing.getRemaining() - amount);
        }

        // The seller is paid in full, with no cut taken.
        plugin.economy().deposit(listing.getSeller(), total);
        give(buyer, listing, amount);
        persist(listing);

        plugin.logTransaction(buyer.getUniqueId(), "BLACKMARKET_BUY",
                listing.getTemplate().getType().name(), amount, total, BigDecimal.ZERO, 0L,
                listing.getSeller().toString());

        Player seller = plugin.getServer().getPlayer(listing.getSeller());
        if (seller != null && seller.isOnline()) {
            plugin.configs().messages().send(seller, "auction.sold-notify", Map.of(
                    "buyer", buyer.getName(),
                    "amount", String.valueOf(amount),
                    "item", ItemCodec.describe(listing.getTemplate()),
                    "total", com.cloudmarket.util.Fmt.money(total),
                    "symbol", plugin.configs().currencySymbol()));
        }
        return new Purchase(Result.OK, amount, total);
    }

    /** Pull a listing and return whatever is left to the seller. */
    public Result cancel(Player player, int listingId) {
        Listing listing = listings.get(listingId);
        if (listing == null) {
            return Result.NOT_FOUND;
        }
        if (!listing.getSeller().equals(player.getUniqueId())
                && !player.hasPermission("market.auction.admin")) {
            return Result.NOT_YOURS;
        }
        int amount;
        synchronized (listing) {
            amount = listing.getRemaining();
            if (amount <= 0) {
                return Result.OUT_OF_STOCK;
            }
            if (freeSpace(player, listing.getTemplate()) < amount) {
                return Result.NO_INVENTORY_SPACE;
            }
            listing.setRemaining(0);
        }
        give(player, listing, amount);
        remove(listing);
        return Result.OK;
    }

    /**
     * Listings of this player that have timed out but still hold items.
     *
     * <p>Expired items are not pushed anywhere. There is no way to hand items to an
     * offline player, and a separate mailbox would be a second system doing what the
     * listing already does - so the listing simply drops off the market and stays
     * collectable from /auction mine.
     */
    public int expiredAwaitingCollection(UUID seller) {
        int count = 0;
        for (Listing listing : listings.values()) {
            if (listing.getSeller().equals(seller) && listing.isExpired()
                    && listing.getRemaining() > 0) {
                count++;
            }
        }
        return count;
    }

    private void persist(Listing listing) {
        plugin.async(() -> {
            try {
                if (listing.getRemaining() <= 0) {
                    plugin.storage().deleteListingRow(listing.getId());
                } else {
                    plugin.storage().updateListingQuantity(listing.getId(), listing.getRemaining());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[CloudMarket] Could not update listing: " + e.getMessage());
            }
        });
        if (listing.getRemaining() <= 0) {
            listings.remove(listing.getId());
        }
    }

    private void remove(Listing listing) {
        listings.remove(listing.getId());
        plugin.async(() -> {
            try {
                plugin.storage().deleteListingRow(listing.getId());
            } catch (Exception e) {
                plugin.getLogger().warning("[CloudMarket] Could not delete listing: " + e.getMessage());
            }
        });
    }

    private static int freeSpace(Player player, ItemStack template) {
        int max = template.getMaxStackSize();
        int space = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                space += max;
            } else if (stack.isSimilar(template)) {
                space += Math.max(0, max - stack.getAmount());
            }
        }
        return space;
    }

    private static void give(Player player, Listing listing, int quantity) {
        int remaining = quantity;
        int max = listing.getTemplate().getMaxStackSize();
        while (remaining > 0) {
            int size = Math.min(max, remaining);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(listing.copyOf(size));
            remaining -= size;
            for (ItemStack stack : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }
        }
    }
}
