package com.cloudmarket.commands;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.blackmarket.BlackMarketManager;
import com.cloudmarket.util.Fmt;
import com.cloudmarket.util.ItemCodec;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** /auction and /blackmarket. */
public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private record Pending(ItemStack snapshot, BigDecimal unitPrice, int quantity, long expiry) {
    }

    private final CloudMarket plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public AuctionCommand(CloudMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.configs().messages().send(sender, "general.players-only");
            return true;
        }
        if (command.getName().equalsIgnoreCase("blackmarket")) {
            plugin.gui().openBlackMarket(player, 0);
            return true;
        }
        if (args.length == 0) {
            plugin.configs().messages().send(player, "auction.usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "hand" -> listHand(player, args);
            case "confirm" -> confirm(player);
            case "cancel" -> plugin.configs().messages().send(player, "auction.cancel-hint");
            case "mine", "list" -> plugin.gui().openMyListings(player, 0);
            default -> plugin.configs().messages().send(player, "auction.usage");
        }
        return true;
    }

    private void listHand(Player player, String[] args) {
        if (args.length < 2) {
            plugin.configs().messages().send(player, "auction.usage");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            plugin.configs().messages().send(player, "auction.empty-hand");
            return;
        }

        BigDecimal unitPrice = Fmt.parseAmount(args[1]);
        if (unitPrice == null) {
            plugin.configs().messages().send(player, "auction.bad-price");
            return;
        }
        BigDecimal max = plugin.configs().auctionMaxPrice();
        if (unitPrice.compareTo(max) > 0) {
            plugin.configs().messages().send(player, "auction.price-too-high", Map.of(
                    "max", Fmt.money(max), "symbol", plugin.configs().currencySymbol()));
            return;
        }

        int used = plugin.blackMarket().slotsUsed(player.getUniqueId());
        int limit = plugin.blackMarket().slotLimit();
        if (used >= limit) {
            plugin.configs().messages().send(player, "auction.slot-limit", Map.of(
                    "used", String.valueOf(used), "limit", String.valueOf(limit)));
            return;
        }

        int quantity = hand.getAmount();
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);

        // Always confirm. The price is per unit, so a stack multiplies whatever the
        // player typed, and a misplaced zero on a dragon egg is unrecoverable once
        // somebody buys it. Showing the total is the part that catches the mistake.
        pending.put(player.getUniqueId(), new Pending(hand.clone(), unitPrice, quantity,
                System.currentTimeMillis() + plugin.configs().auctionConfirmSeconds() * 1000L));

        plugin.configs().messages().send(player, "auction.confirm-prompt", Map.of(
                "amount", String.valueOf(quantity),
                "item", ItemCodec.describe(hand),
                "each", Fmt.money(unitPrice),
                "total", Fmt.money(total),
                "days", String.valueOf(plugin.configs().auctionDurationDays()),
                "seconds", String.valueOf(plugin.configs().auctionConfirmSeconds()),
                "symbol", plugin.configs().currencySymbol()));
    }

    private void confirm(Player player) {
        Pending waiting = pending.remove(player.getUniqueId());
        if (waiting == null || waiting.expiry() < System.currentTimeMillis()) {
            plugin.configs().messages().send(player, "auction.nothing-to-confirm");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        // The player may have swapped, dropped or used the item while deciding.
        // Listing whatever is in their hand now would sell the wrong thing.
        if (hand == null || !hand.isSimilar(waiting.snapshot())
                || hand.getAmount() != waiting.quantity()) {
            plugin.configs().messages().send(player, "auction.item-changed");
            return;
        }

        BlackMarketManager.Result result = plugin.blackMarket().list(player, waiting.unitPrice());
        switch (result) {
            case OK -> plugin.configs().messages().send(player, "auction.listed", Map.of(
                    "amount", String.valueOf(waiting.quantity()),
                    "item", ItemCodec.describe(waiting.snapshot()),
                    "each", Fmt.money(waiting.unitPrice()),
                    "total", Fmt.money(waiting.unitPrice()
                            .multiply(BigDecimal.valueOf(waiting.quantity()))),
                    "symbol", plugin.configs().currencySymbol()));
            case SLOT_LIMIT -> plugin.configs().messages().send(player, "auction.slot-limit", Map.of(
                    "used", String.valueOf(plugin.blackMarket().slotsUsed(player.getUniqueId())),
                    "limit", String.valueOf(plugin.blackMarket().slotLimit())));
            case STORAGE_ERROR -> plugin.configs().messages().send(player, "auction.storage-error");
            default -> plugin.configs().messages().send(player, "auction.empty-hand");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("auction") && args.length == 1) {
            return MarketCommands.filter(List.of("hand", "mine", "confirm"), args[0]);
        }
        return List.of();
    }
}
