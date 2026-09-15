package com.cloudmarket.commands;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.market.MarketItem;
import com.cloudmarket.market.MarketManager;
import com.cloudmarket.util.Fmt;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /shop, /buy and /sell. */
public final class MarketCommands implements CommandExecutor, TabCompleter {

    private final CloudMarket plugin;

    public MarketCommands(CloudMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.configs().messages().send(sender, "general.players-only");
            return true;
        }
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "shop" -> shop(player);
            case "buy" -> buy(player, args);
            case "sell" -> sell(player, args);
            default -> false;
        };
    }

    private boolean shop(Player player) {
        plugin.gui().openCategories(player);
        return true;
    }

    private boolean buy(Player player, String[] args) {
        if (args.length < 1) {
            plugin.configs().messages().send(player, "market.buy-usage");
            return true;
        }
        Material material = Material.matchMaterial(args[0].toUpperCase(Locale.ROOT));
        if (material == null) {
            plugin.configs().messages().send(player, "general.unknown-item", Map.of("item", args[0]));
            return true;
        }
        int amount = 1;
        if (args.length >= 2) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                plugin.configs().messages().send(player, "input.not-a-number");
                return true;
            }
        }
        if (amount <= 0) {
            plugin.configs().messages().send(player, "input.not-a-number");
            return true;
        }

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
        return true;
    }

    private boolean sell(Player player, String[] args) {
        String mode = args.length == 0 ? "hand" : args[0].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "hand" -> sellHand(player);
            case "hotbar" -> sellSlots(player, 0, 8, "market.sold-hotbar-summary");
            case "all", "inventory", "inv" -> sellSlots(player, 0, 35, "market.sold-all-summary");
            default -> plugin.configs().messages().send(player, "market.sell-usage");
        }
        return true;
    }

    private void sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            plugin.configs().messages().send(player, "market.empty-hand");
            return;
        }
        if (!plugin.market().isSellableStack(hand)) {
            plugin.configs().messages().send(player, "market.modified-item");
            return;
        }
        Material material = hand.getType();
        int amount = hand.getAmount();

        MarketManager.TradeOutcome outcome = plugin.market().executeSell(player, material, amount);
        reportSell(player, material, outcome);
    }

    private void reportSell(Player player, Material material, MarketManager.TradeOutcome outcome) {
        String symbol = plugin.configs().currencySymbol();
        switch (outcome.result()) {
            case OK -> plugin.configs().messages().send(player, "market.sold", Map.of(
                    "amount", String.valueOf(outcome.quantity()),
                    "item", Fmt.pretty(material),
                    "total", Fmt.money(outcome.quote().net()),
                    "tax", Fmt.money(outcome.quote().tax()),
                    "each", Fmt.money(outcome.quote().unitAverage()),
                    "symbol", symbol));
            case NOT_SELLABLE_CRAFTABLE -> plugin.configs().messages().send(player,
                    "market.craftable-blocked", Map.of("item", Fmt.pretty(material)));
            case NOT_CONFIGURED -> plugin.configs().messages().send(player, "market.not-configured",
                    Map.of("item", Fmt.pretty(material)));
            case DISABLED -> plugin.configs().messages().send(player, "market.disabled",
                    Map.of("item", Fmt.pretty(material)));
            case RATE_LIMITED -> plugin.configs().messages().send(player, "market.rate-limited",
                    Map.of("item", Fmt.pretty(material)));
            default -> plugin.configs().messages().send(player, "market.nothing-sold");
        }
    }

    /**
     * Sell every sellable stack across a slot range, then report one summary rather
     * than a wall of per-stack messages.
     *
     * <p>Quantities are totalled per material before selling. Selling a material in
     * several separate batches would walk it down the curve once per stack and pay
     * less than selling the same items in one batch, which would be a confusing
     * penalty for having the items split across slots.
     */
    private void sellSlots(Player player, int from, int to, String summaryKey) {
        Map<Material, Integer> totals = new LinkedHashMap<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = from; slot <= to && slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir() || !plugin.market().isSellableStack(stack)) {
                continue;
            }
            if (!plugin.market().isTradable(stack.getType())) {
                continue;
            }
            totals.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }

        if (totals.isEmpty()) {
            plugin.configs().messages().send(player, "market.nothing-sold");
            return;
        }

        BigDecimal grandTotal = BigDecimal.ZERO;
        int lines = 0;
        for (Map.Entry<Material, Integer> entry : totals.entrySet()) {
            MarketManager.TradeOutcome outcome =
                    plugin.market().executeSell(player, entry.getKey(), entry.getValue());
            if (!outcome.ok()) {
                continue;
            }
            grandTotal = grandTotal.add(outcome.quote().net());
            lines++;
            player.sendMessage(plugin.configs().messages().bare("market.sold-line", Map.of(
                    "amount", String.valueOf(outcome.quantity()),
                    "item", Fmt.pretty(entry.getKey()),
                    "total", Fmt.money(outcome.quote().net()),
                    "symbol", plugin.configs().currencySymbol())));
        }

        if (lines == 0) {
            plugin.configs().messages().send(player, "market.nothing-sold");
            return;
        }
        plugin.configs().messages().send(player, summaryKey, Map.of(
                "stacks", String.valueOf(lines),
                "total", Fmt.money(grandTotal),
                "symbol", plugin.configs().currencySymbol()));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("sell") && args.length == 1) {
            return filter(List.of("hand", "hotbar", "all"), args[0]);
        }
        if (name.equals("buy") && args.length == 1) {
            List<String> names = new ArrayList<>();
            for (MarketItem item : plugin.market().items().values()) {
                if (item.isEnabled() && item.getStock() > 0) {
                    names.add(item.getMaterial().name().toLowerCase(Locale.ROOT));
                }
            }
            return filter(names, args[0]);
        }
        return List.of();
    }

    static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
            if (out.size() >= 60) {
                break;
            }
        }
        return out;
    }
}
