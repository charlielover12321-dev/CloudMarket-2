package com.cloudmarket.market;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Set;

/**
 * Shop tabs. The classifier is deliberately name-based rather than a hardcoded
 * material list: 26.2 added sulfur and cinnabar, and the next game drop will add
 * something else. A heuristic puts new materials somewhere sensible without anyone
 * editing a list, and the admin can always override a single item in
 * market-items.yml if the guess is wrong.
 */
public enum Category {

    WOOD("Wood", Material.OAK_LOG),
    ORES("Ores & Minerals", Material.RAW_IRON),
    FOOD("Food & Crops", Material.WHEAT),
    MOB_DROPS("Mob Drops", Material.ROTTEN_FLESH),
    MISC("Misc", Material.DIRT);

    private final String displayName;
    private final Material icon;

    Category(String displayName, Material icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Material getIcon() {
        return icon;
    }

    public static Category parse(String raw, Category fallback) {
        if (raw == null) {
            return fallback;
        }
        for (Category category : values()) {
            if (category.name().equalsIgnoreCase(raw)) {
                return category;
            }
        }
        return fallback;
    }

    private static final Set<String> MOB_DROP_NAMES = Set.of(
            "ROTTEN_FLESH", "BONE", "STRING", "SPIDER_EYE", "GUNPOWDER", "ENDER_PEARL",
            "BLAZE_ROD", "SLIME_BALL", "MAGMA_CREAM", "GHAST_TEAR", "PHANTOM_MEMBRANE",
            "RABBIT_HIDE", "RABBIT_FOOT", "LEATHER", "FEATHER", "INK_SAC", "GLOW_INK_SAC",
            "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "NAUTILUS_SHELL", "SHULKER_SHELL",
            "SCUTE", "TURTLE_SCUTE", "ARMADILLO_SCUTE", "HONEYCOMB", "NETHER_STAR",
            "WITHER_SKELETON_SKULL", "DRAGON_BREATH", "SADDLE", "NAME_TAG", "TRIDENT"
    );

    private static final Set<String> CROP_NAMES = Set.of(
            "WHEAT", "WHEAT_SEEDS", "BEETROOT_SEEDS", "PUMPKIN_SEEDS", "MELON_SEEDS",
            "TORCHFLOWER_SEEDS", "PITCHER_POD", "SUGAR_CANE", "CACTUS", "BAMBOO",
            "KELP", "SEA_PICKLE", "COCOA_BEANS", "NETHER_WART", "SWEET_BERRIES",
            "GLOW_BERRIES", "CHORUS_FRUIT"
    );

    private static final Set<String> MINERAL_NAMES = Set.of(
            "COAL", "DIAMOND", "EMERALD", "LAPIS_LAZULI", "REDSTONE", "QUARTZ",
            "AMETHYST_SHARD", "NETHERITE_SCRAP", "FLINT", "CLAY_BALL", "SULFUR", "CINNABAR"
    );

    /**
     * Best-effort classification for a material we have no explicit category for.
     */
    public static Category classify(Material material) {
        String name = material.name().toUpperCase(Locale.ROOT);

        if (name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE") || name.equals("BAMBOO_BLOCK")) {
            return WOOD;
        }
        if (name.endsWith("_ORE") || name.startsWith("RAW_") || MINERAL_NAMES.contains(name)
                || name.contains("SULFUR") || name.contains("CINNABAR")) {
            return ORES;
        }
        if (MOB_DROP_NAMES.contains(name)) {
            return MOB_DROPS;
        }
        if (CROP_NAMES.contains(name)) {
            return FOOD;
        }
        // isEdible() covers raw meat, fish, apples and anything edible added later.
        try {
            if (material.isEdible()) {
                return FOOD;
            }
        } catch (Throwable ignored) {
            // Extremely defensive: never let classification break plugin startup.
        }
        return MISC;
    }
}
