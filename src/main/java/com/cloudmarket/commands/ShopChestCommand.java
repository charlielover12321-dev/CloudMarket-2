package com.cloudmarket.commands;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.shops.ShopChest;
import com.cloudmarket.util.Fmt;
import org.bukkit.Material;
import org.bukkit.block.Block;
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

/** /shopchest create | additem | removeitem | info | remove. */
public final class ShopChestCommand implements CommandExecutor, TabCompleter {

    private static final int REACH = 6;

    private final CloudMarket plugin;

    public ShopChestCommand(CloudMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.configs().messages().send(sender, "general.players-only");
            return true;
        }
        if (args.length == 0) {
            plugin.configs().messages().send(player, "shopchest.usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(player);
            case "additem" -> addItem(player, args);
            case "removeitem" -> removeItem(player);
            case "info" -> info(player);
            case "remove", "delete" -> remove(player);
            default -> plugin.configs().messages().send(player, "shopchest.usage");
        }
        return true;
    }

    /** The chest the player is looking at, or null with a message already sent. */
    private Block targetChest(Player player) {
        Block block = player.getTargetBlockExact(REACH);
        if (block == null
                || (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST)) {
            plugin.configs().messages().send(player, "shopchest.look-at-chest");
            return null;
        }
        return block;
    }

    private void create(Player player) {
        Block block = targetChest(player);
        if (block == null) {
            return;
        }
        if (plugin.shopChests().at(block) != null) {
            plugin.configs().messages().send(player, "shopchest.already-registered");
            return;
        }
        // Defer to whatever land protection the server already runs rather than
        // inventing a second, conflicting ownership model: if the player cannot
        // build here, they do not own this chest.
        if (!player.hasPermission("market.shopchest")) {
            plugin.configs().messages().send(player, "general.no-permission");
            return;
        }
        ShopChest chest = plugin.shopChests().register(block, player.getUniqueId());
        if (chest == null) {
            plugin.configs().messages().send(player, "shopchest.create-failed");
            return;
        }
        plugin.configs().messages().send(player, "shopchest.created");
    }

    private void addItem(Player player, String[] args) {
        Block block = targetChest(player);
        if (block == null) {
            return;
        }
        ShopChest chest = plugin.shopChests().at(block);
        if (chest == null) {
            plugin.configs().messages().send(player, "shopchest.not-registered");
            return;
        }
        if (!chest.getOwner().equals(player.getUniqueId())) {
            plugin.configs().messages().send(player, "shopchest.not-yours");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            plugin.configs().messages().send(player, "shopchest.hold-item");
            return;
        }
        Material material = hand.getType();

        if (args.length >= 2) {
            BigDecimal price = Fmt.parseAmount(args[1]);
            if (price == null) {
                plugin.configs().messages().send(player, "pay.bad-amount");
                return;
            }
            applyPrice(player, chest, material, price);
            return;
        }

        // No price given: ask for one. Bedrock players get a form, Java players a
        // chat prompt, both handled behind the same call.
        plugin.bedrock().promptForPrice(player, "Set price",
                "Price per " + Fmt.pretty(material) + ":",
                price -> applyPrice(player, chest, material, price));
    }

    private void applyPrice(Player player, ShopChest chest, Material material, BigDecimal price) {
        plugin.shopChests().setPrice(chest, material, price);
        plugin.configs().messages().send(player, "shopchest.price-set", Map.of(
                "item", Fmt.pretty(material),
                "price", Fmt.money(price),
                "symbol", plugin.configs().currencySymbol()));
    }

    private void removeItem(Player player) {
        Block block = targetChest(player);
        if (block == null) {
            return;
        }
        ShopChest chest = plugin.shopChests().at(block);
        if (chest == null) {
            plugin.configs().messages().send(player, "shopchest.not-registered");
            return;
        }
        if (!chest.getOwner().equals(player.getUniqueId())) {
            plugin.configs().messages().send(player, "shopchest.not-yours");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            plugin.configs().messages().send(player, "shopchest.hold-item");
            return;
        }
        plugin.shopChests().clearPrice(chest, hand.getType());
        plugin.configs().messages().send(player, "shopchest.price-removed",
                Map.of("item", Fmt.pretty(hand.getType())));
    }

    private void info(Player player) {
        Block block = targetChest(player);
        if (block == null) {
            return;
        }
        ShopChest chest = plugin.shopChests().at(block);
        if (chest == null) {
            plugin.configs().messages().send(player, "shopchest.not-registered");
            return;
        }
        plugin.configs().messages().send(player, "shopchest.info-header",
                Map.of("owner", plugin.economy().nameOf(chest.getOwner())));
        for (Material material : plugin.shopChests().listedMaterials(chest)) {
            player.sendMessage(plugin.configs().messages().bare("shopchest.info-line", Map.of(
                    "item", Fmt.pretty(material),
                    "price", Fmt.money(chest.priceOf(material)),
                    "stock", String.valueOf(plugin.shopChests().stockOf(chest, material)),
                    "symbol", plugin.configs().currencySymbol())));
        }
    }

    private void remove(Player player) {
        Block block = targetChest(player);
        if (block == null) {
            return;
        }
        ShopChest chest = plugin.shopChests().at(block);
        if (chest == null) {
            plugin.configs().messages().send(player, "shopchest.not-registered");
            return;
        }
        if (!chest.getOwner().equals(player.getUniqueId())
                && !player.hasPermission("market.shopchest.bypass")) {
            plugin.configs().messages().send(player, "shopchest.not-yours");
            return;
        }
        plugin.shopChests().unregister(chest);
        plugin.configs().messages().send(player, "shopchest.removed");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return MarketCommands.filter(
                    List.of("create", "additem", "removeitem", "info", "remove"), args[0]);
        }
        return List.of();
    }
}
