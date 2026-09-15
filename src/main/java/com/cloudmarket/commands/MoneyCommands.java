package com.cloudmarket.commands;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.storage.SqlStorage;
import com.cloudmarket.util.Fmt;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** /pay, /balance and /leaderboard. */
public final class MoneyCommands implements CommandExecutor, TabCompleter {

    private record PendingPay(UUID target, BigDecimal amount, long expiry) {
    }

    private final CloudMarket plugin;
    private final Map<UUID, PendingPay> pendingPayments = new ConcurrentHashMap<>();

    public MoneyCommands(CloudMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "pay" -> pay(sender, args);
            case "balance" -> balance(sender, args);
            case "leaderboard" -> leaderboard(sender);
            default -> false;
        };
    }

    private boolean pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.configs().messages().send(sender, "general.players-only");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            return confirmPay(player);
        }
        if (args.length < 2) {
            plugin.configs().messages().send(player, "pay.usage");
            return true;
        }

        Optional<UUID> target = plugin.economy().resolve(args[0]);
        if (target.isEmpty()) {
            plugin.configs().messages().send(player, "general.unknown-player",
                    Map.of("player", args[0]));
            return true;
        }
        UUID to = target.get();
        if (to.equals(player.getUniqueId())) {
            plugin.configs().messages().send(player, "pay.self");
            return true;
        }

        BigDecimal amount = Fmt.parseAmount(args[1]);
        if (amount == null) {
            plugin.configs().messages().send(player, "pay.bad-amount");
            return true;
        }
        BigDecimal minimum = plugin.configs().payMinimum();
        if (amount.compareTo(minimum) < 0) {
            plugin.configs().messages().send(player, "pay.below-minimum", Map.of(
                    "minimum", Fmt.money(minimum),
                    "symbol", plugin.configs().currencySymbol()));
            return true;
        }
        if (!plugin.economy().has(player.getUniqueId(), amount)) {
            plugin.configs().messages().send(player, "pay.cannot-afford");
            return true;
        }

        if (amount.compareTo(plugin.configs().payConfirmThreshold()) > 0) {
            pendingPayments.put(player.getUniqueId(), new PendingPay(to, amount,
                    System.currentTimeMillis() + plugin.configs().payConfirmSeconds() * 1000L));
            plugin.configs().messages().send(player, "pay.confirm-prompt", Map.of(
                    "amount", Fmt.money(amount),
                    "player", plugin.economy().nameOf(to),
                    "seconds", String.valueOf(plugin.configs().payConfirmSeconds()),
                    "symbol", plugin.configs().currencySymbol()));
            return true;
        }

        complete(player, to, amount);
        return true;
    }

    private boolean confirmPay(Player player) {
        PendingPay pending = pendingPayments.remove(player.getUniqueId());
        if (pending == null || pending.expiry() < System.currentTimeMillis()) {
            plugin.configs().messages().send(player, "pay.nothing-to-confirm");
            return true;
        }
        complete(player, pending.target(), pending.amount());
        return true;
    }

    private void complete(Player player, UUID to, BigDecimal amount) {
        if (!plugin.economy().transfer(player.getUniqueId(), to, amount)) {
            plugin.configs().messages().send(player, "pay.cannot-afford");
            return;
        }
        String symbol = plugin.configs().currencySymbol();
        plugin.configs().messages().send(player, "pay.sent", Map.of(
                "amount", Fmt.money(amount),
                "player", plugin.economy().nameOf(to),
                "symbol", symbol));

        Player receiver = plugin.getServer().getPlayer(to);
        if (receiver != null && receiver.isOnline()) {
            plugin.configs().messages().send(receiver, "pay.received", Map.of(
                    "amount", Fmt.money(amount),
                    "player", player.getName(),
                    "symbol", symbol));
        }
        plugin.logTransaction(player.getUniqueId(), "PAY", null, 0, amount,
                BigDecimal.ZERO, 0L, to.toString());
    }

    private boolean balance(CommandSender sender, String[] args) {
        String symbol = plugin.configs().currencySymbol();
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.configs().messages().send(sender, "general.players-only");
                return true;
            }
            plugin.configs().messages().send(player, "balance.own", Map.of(
                    "balance", Fmt.money(plugin.economy().getBalance(player.getUniqueId())),
                    "symbol", symbol));
            return true;
        }
        if (!sender.hasPermission("market.balance.others")) {
            plugin.configs().messages().send(sender, "general.no-permission");
            return true;
        }
        Optional<UUID> target = plugin.economy().resolve(args[0]);
        if (target.isEmpty()) {
            plugin.configs().messages().send(sender, "general.unknown-player", Map.of("player", args[0]));
            return true;
        }
        plugin.configs().messages().send(sender, "balance.other", Map.of(
                "player", plugin.economy().nameOf(target.get()),
                "balance", Fmt.money(plugin.economy().getBalance(target.get())),
                "symbol", symbol));
        return true;
    }

    private boolean leaderboard(CommandSender sender) {
        List<SqlStorage.BalanceRow> top = plugin.leaderboard();
        if (top.isEmpty()) {
            plugin.configs().messages().send(sender, "leaderboard.empty");
            return true;
        }
        sender.sendMessage(plugin.configs().messages().bare("leaderboard.header", Map.of()));
        int rank = 1;
        for (SqlStorage.BalanceRow row : top) {
            sender.sendMessage(plugin.configs().messages().bare("leaderboard.line", Map.of(
                    "rank", String.valueOf(rank++),
                    "player", row.name() == null ? plugin.economy().nameOf(row.uuid()) : row.name(),
                    "balance", Fmt.money(row.balance()),
                    "symbol", plugin.configs().currencySymbol())));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ((name.equals("pay") || name.equals("balance")) && args.length == 1) {
            return MarketCommands.filter(plugin.economy().knownNames(), args[0]);
        }
        return List.of();
    }
}
