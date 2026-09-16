package com.cloudmarket.commands;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.market.Category;
import com.cloudmarket.market.MarketItem;
import com.cloudmarket.util.Fmt;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** /marketadmin setprice | stock | whitelist | autoconfig | info | reload. */
public final class MarketAdminCommand implements CommandExecutor, TabCompleter {

    private final CloudMarket plugin;

    public MarketAdminCommand(CloudMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            plugin.configs().messages().send(sender, "admin.usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setprice" -> setPrice(sender, args);
            case "stock" -> setStock(sender, args);
            case "whitelist" -> whitelist(sender, args);
            case "autoconfig" -> autoConfig(sender, args);
            case "reprice" -> reprice(sender);
            case "info" -> info(sender, args);
            case "reload" -> reload(sender);
            default -> plugin.configs().messages().send(sender, "admin.usage");
        }
        return true;
    }

    private void setPrice(CommandSender sender, String[] args) {
        if (!sender.hasPermission("market.admin.setprice")) {
            plugin.configs().messages().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 6) {
            plugin.configs().messages().send(sender, "admin.setprice-usage");
            return;
        }
        Material material = Material.matchMaterial(args[1].toUpperCase(Locale.ROOT));
        if (material == null) {
            plugin.configs().messages().send(sender, "general.unknown-item", Map.of("item", args[1]));
            return;
        }
        if (!plugin.market().isEligible(material)) {
            plugin.configs().messages().send(sender, "admin.craftable-needs-whitelist",
                    Map.of("item", Fmt.pretty(material)));
            return;
        }
        double base;
        double floor;
        double ceiling;
        long equilibrium;
        try {
            base = Double.parseDouble(args[2]);
            floor = Double.parseDouble(args[3]);
            ceiling = Double.parseDouble(args[4]);
            equilibrium = Long.parseLong(args[5]);
        } catch (NumberFormatException e) {
            plugin.configs().messages().send(sender, "input.not-a-number");
            return;
        }
        if (base <= 0 || equilibrium <= 0 || ceiling < base || floor > base) {
            plugin.configs().messages().send(sender, "admin.bad-price-range");
            return;
        }

        FileConfiguration config = plugin.configs().marketItems();
        String path = "items." + material.name();
        config.set(path + ".basePrice", base);
        config.set(path + ".floorPrice", floor);
        config.set(path + ".ceilingPrice", ceiling);
        config.set(path + ".equilibriumStock", equilibrium);
        if (config.getString(path + ".category") == null) {
            config.set(path + ".category", Category.classify(material).name());
        }
        config.set(path + ".enabled", true);
        plugin.configs().saveMarketItems();

        MarketItem existing = plugin.market().get(material);
        if (existing != null) {
            existing.setPrices(base, floor, ceiling, equilibrium);
            existing.setEnabled(true);
        } else {
            plugin.reloadMarket();
        }

        plugin.configs().messages().send(sender, "admin.price-set", Map.of(
                "item", Fmt.pretty(material),
                "base", Fmt.money(base),
                "floor", Fmt.money(floor),
                "ceiling", Fmt.money(ceiling),
                "equilibrium", String.valueOf(equilibrium),
                "symbol", plugin.configs().currencySymbol()));

        if (floor > 0 && isFarmable(material)) {
            plugin.configs().messages().send(sender, "admin.farmable-floor-warning",
                    Map.of("item", Fmt.pretty(material)));
        }
    }

    /**
     * Crops and mob drops can be produced without limit by a farm, so a floor price
     * above zero on one of them is an uncapped money source no matter how the curve
     * is tuned.
     */
    private boolean isFarmable(Material material) {
        Category category = Category.classify(material);
        return category == Category.FOOD || category == Category.MOB_DROPS;
    }

    private void setStock(CommandSender sender, String[] args) {
        if (!sender.hasPermission("market.admin.stock")) {
            plugin.configs().messages().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 3) {
            plugin.configs().messages().send(sender, "admin.stock-usage");
            return;
        }
        Material material = Material.matchMaterial(args[1].toUpperCase(Locale.ROOT));
        if (material == null) {
            plugin.configs().messages().send(sender, "general.unknown-item", Map.of("item", args[1]));
            return;
        }
        MarketItem item = plugin.market().get(material);
        if (item == null) {
            plugin.configs().messages().send(sender, "market.not-configured",
                    Map.of("item", Fmt.pretty(material)));
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            plugin.configs().messages().send(sender, "input.not-a-number");
            return;
        }
        item.setStock(amount);
        plugin.market().valuation().invalidate();
        plugin.configs().messages().send(sender, "admin.stock-set", Map.of(
                "item", Fmt.pretty(material),
                "amount", Fmt.count(item.getStock())));
    }

    private void whitelist(CommandSender sender, String[] args) {
        if (!sender.hasPermission("market.admin.whitelist")) {
            plugin.configs().messages().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.configs().messages().send(sender, "admin.whitelist-usage");
            return;
        }
        Material material = Material.matchMaterial(args[1].toUpperCase(Locale.ROOT));
        if (material == null) {
            plugin.configs().messages().send(sender, "general.unknown-item", Map.of("item", args[1]));
            return;
        }
        plugin.configs().addWhitelist(material);
        plugin.reloadMarket();
        plugin.configs().messages().send(sender, "admin.whitelisted",
                Map.of("item", Fmt.pretty(material)));
    }

    /**
     * Write config entries for every raw material the server knows about that does
     * not already have one.
     *
     * <p>This exists because the set of materials is a moving target. Rather than
     * shipping a hand-maintained list that goes stale every game drop, the plugin
     * reads the live registry and derives a starting price from the material's own
     * properties. 26.2's sulfur and cinnabar get picked up with no edit from anyone.
     *
     * <p>Entries are written with {@code enabled: false} so nothing goes live at a
     * guessed price - an admin reviews the file, adjusts, and flips the ones they
     * want on. Pass {@code confirm enable} to skip that step.
     */
    private void autoConfig(CommandSender sender, String[] args) {
        if (!sender.hasPermission("market.admin.autoconfig")) {
            plugin.configs().messages().send(sender, "general.no-permission");
            return;
        }
        boolean enable = args.length >= 2 && args[1].equalsIgnoreCase("enable");

        FileConfiguration config = plugin.configs().marketItems();
        ConfigurationSection root = config.getConfigurationSection("items");
        if (root == null) {
            root = config.createSection("items");
        }

        int added = 0;
        for (Material material : Material.values()) {
            if (material.isAir() || !material.isItem() || material.isLegacy()) {
                continue;
            }
            if (!plugin.market().isEligible(material)) {
                continue;
            }
            if (root.isConfigurationSection(material.name())) {
                continue;
            }
            Category category = Category.classify(material);
            double base = suggestBasePrice(material, category);
            boolean rare = ItemRarity.isRare(material);
            double floor = rare ? round(base * ItemRarity.RARE_FLOOR_FRACTION)
                    : (isFarmable(material) ? 0.0d : round(base * 0.2d));
            double ceiling = rare ? round(base * ItemRarity.RARE_CEILING_FRACTION)
                    : round(base * 2.0d);
            long equilibrium = rare ? ItemRarity.RARE_EQUILIBRIUM : suggestEquilibrium(category);

            String path = "items." + material.name();
            config.set(path + ".category", category.name());
            config.set(path + ".basePrice", base);
            config.set(path + ".floorPrice", floor);
            config.set(path + ".ceilingPrice", ceiling);
            config.set(path + ".equilibriumStock", equilibrium);
            config.set(path + ".enabled", enable);
            added++;
        }

        plugin.configs().saveMarketItems();
        plugin.reloadMarket();
        plugin.configs().messages().send(sender, "admin.autoconfig-done", Map.of(
                "added", String.valueOf(added),
                "state", enable ? "enabled" : "disabled"));
    }

    /**
     * A rough opening price based on how hard the material is to obtain. These are
     * starting points for an admin to tune, not a balanced economy - no automatic
     * heuristic can know what your server's progression feels like.
     */
    private double suggestBasePrice(Material material, Category category) {
        // Rarity beats composition. A music disc is made of nothing in particular
        // and is worth a great deal; the category heuristic cannot know that.
        java.util.OptionalDouble rare = ItemRarity.rarePrice(material);
        if (rare.isPresent()) {
            return rare.getAsDouble();
        }
        String name = material.name();
        if (name.contains("NETHERITE") || name.equals("NETHER_STAR") || name.contains("DRAGON")) {
            return 500.0d;
        }
        if (name.contains("DIAMOND") || name.contains("EMERALD") || name.contains("ANCIENT_DEBRIS")) {
            return 60.0d;
        }
        if (name.startsWith("RAW_GOLD") || name.contains("LAPIS") || name.contains("AMETHYST")) {
            return 12.0d;
        }
        if (name.startsWith("RAW_") || name.equals("COAL") || name.equals("REDSTONE")
                || name.equals("QUARTZ")) {
            return 6.0d;
        }
        return switch (category) {
            case ORES -> 5.0d;
            case WOOD -> 1.5d;
            case MOB_DROPS -> 3.0d;
            case TOOLS -> 8.0d;
            case REDSTONE_TECH -> 4.0d;
            case BUILDING -> 1.0d;
            case DECORATION -> 1.0d;
            case UTILITY -> 2.0d;
            case BREWING -> 3.0d;
            case NATURAL -> 0.4d;
            case FOOD -> 1.0d;
            case MISC -> 0.5d;
        };
    }

    private long suggestEquilibrium(Category category) {
        return switch (category) {
            case WOOD, MISC, BUILDING, DECORATION, NATURAL -> 8192L;
            case FOOD -> 6144L;
            case MOB_DROPS, REDSTONE_TECH, UTILITY, BREWING -> 3072L;
            case ORES, TOOLS -> 2048L;
        };
    }

    private static double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    /**
     * Re-price rare items that already have a config entry.
     *
     * <p>autoconfig deliberately skips materials that already exist in the file, so
     * it cannot fix a music disc written at 0.50 by an earlier run. This walks the
     * rare list only and rewrites those entries, leaving every other price alone -
     * a blanket re-price would throw away hand-tuning, which is usually the more
     * valuable thing in the file.
     */
    private void reprice(CommandSender sender) {
        if (!sender.hasPermission("market.admin.autoconfig")) {
            plugin.configs().messages().send(sender, "general.no-permission");
            return;
        }
        FileConfiguration config = plugin.configs().marketItems();
        ConfigurationSection root = config.getConfigurationSection("items");
        if (root == null) {
            plugin.configs().messages().send(sender, "admin.nothing-to-reprice");
            return;
        }

        int updated = 0;
        for (Material material : Material.values()) {
            if (material.isLegacy() || !ItemRarity.isRare(material)) {
                continue;
            }
            if (!root.isConfigurationSection(material.name())) {
                continue;
            }
            double base = ItemRarity.rarePrice(material).orElse(0.0d);
            if (base <= 0.0d) {
                continue;
            }
            String path = "items." + material.name();
            config.set(path + ".basePrice", base);
            config.set(path + ".floorPrice", round(base * ItemRarity.RARE_FLOOR_FRACTION));
            config.set(path + ".ceilingPrice", round(base * ItemRarity.RARE_CEILING_FRACTION));
            config.set(path + ".equilibriumStock", ItemRarity.RARE_EQUILIBRIUM);
            updated++;
        }

        plugin.configs().saveMarketItems();
        plugin.reloadMarket();
        plugin.configs().messages().send(sender, "admin.repriced",
                Map.of("count", String.valueOf(updated)));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Material material = Material.matchMaterial(args[1].toUpperCase(Locale.ROOT));
            if (material == null) {
                plugin.configs().messages().send(sender, "general.unknown-item",
                        Map.of("item", args[1]));
                return;
            }
            MarketItem item = plugin.market().get(material);
            if (item == null) {
                boolean eligible = plugin.market().isEligible(material);
                plugin.configs().messages().send(sender, eligible
                                ? "market.not-configured" : "market.craftable-blocked",
                        Map.of("item", Fmt.pretty(material)));
                return;
            }
            plugin.configs().messages().send(sender, "admin.item-info", Map.of(
                    "item", Fmt.pretty(material),
                    "category", item.getCategory().getDisplayName(),
                    "base", Fmt.money(item.getBasePrice()),
                    "floor", Fmt.money(item.getFloorPrice()),
                    "ceiling", Fmt.money(item.getCeilingPrice()),
                    "equilibrium", Fmt.count(item.getEquilibriumStock()),
                    "stock", Fmt.count(item.getStock()),
                    "symbol", plugin.configs().currencySymbol()));
            return;
        }

        plugin.configs().messages().send(sender, "admin.summary", Map.of(
                "items", String.valueOf(plugin.market().items().size()),
                "barred", String.valueOf(plugin.market().scanner().getExcluded().size()),
                "rescued", String.valueOf(plugin.market().scanner().getRescuedByNeutralityRule().size()),
                "recipes", String.valueOf(plugin.market().scanner().getRecipesScanned()),
                "chests", String.valueOf(plugin.shopChests().count()),
                "supply", Fmt.money(plugin.economy().totalSupply()),
                "burned", Fmt.money(plugin.economy().totalBurned()),
                "symbol", plugin.configs().currencySymbol()));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("market.admin.reload")) {
            plugin.configs().messages().send(sender, "general.no-permission");
            return;
        }
        plugin.configs().loadAll();
        plugin.reloadMarket();
        plugin.configs().messages().send(sender, "admin.reloaded");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return MarketCommands.filter(
                    List.of("setprice", "stock", "whitelist", "autoconfig", "reprice", "info", "reload"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("autoconfig")) {
                return MarketCommands.filter(List.of("enable"), args[1]);
            }
            if (sub.equals("setprice") || sub.equals("stock") || sub.equals("info")) {
                List<String> names = new ArrayList<>();
                for (MarketItem item : plugin.market().items().values()) {
                    names.add(item.getMaterial().name().toLowerCase(Locale.ROOT));
                }
                return MarketCommands.filter(names, args[1]);
            }
            if (sub.equals("whitelist")) {
                // Only barred materials are worth offering here.
                TreeSet<String> names = new TreeSet<>();
                for (Material material : plugin.market().scanner().getExcluded()) {
                    names.add(material.name().toLowerCase(Locale.ROOT));
                }
                return MarketCommands.filter(new ArrayList<>(names), args[1]);
            }
        }
        return List.of();
    }
}
