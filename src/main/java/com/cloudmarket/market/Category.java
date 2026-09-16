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
    BUILDING("Building Blocks", Material.BRICKS),
    TOOLS("Tools & Armour", Material.IRON_PICKAXE),
    REDSTONE_TECH("Redstone", Material.REDSTONE_TORCH),
    DECORATION("Decoration", Material.FLOWER_POT),
    NATURAL("Natural Blocks", Material.DIRT),
    UTILITY("Utility & Storage", Material.CHEST),
    BREWING("Brewing", Material.BREWING_STAND),
    MISC("Misc", Material.STICK);

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
    private static final Set<String> TOOL_SUFFIXES = Set.of(
            "_SWORD", "_PICKAXE", "_AXE", "_SHOVEL", "_HOE",
            "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS", "_HORSE_ARMOR");

    private static final Set<String> TOOL_NAMES = Set.of(
            "BOW", "CROSSBOW", "TRIDENT", "SHIELD", "ELYTRA", "FISHING_ROD", "SHEARS",
            "FLINT_AND_STEEL", "SPYGLASS", "BRUSH", "MACE", "TURTLE_HELMET", "CARROT_ON_A_STICK",
            "WARPED_FUNGUS_ON_A_STICK", "LEAD", "NAME_TAG", "COMPASS", "CLOCK", "BUNDLE");

    private static final Set<String> REDSTONE_FRAGMENTS = Set.of(
            "REDSTONE", "PISTON", "OBSERVER", "HOPPER", "DISPENSER", "DROPPER", "COMPARATOR",
            "REPEATER", "RAIL", "LEVER", "BUTTON", "PRESSURE_PLATE", "TRIPWIRE", "TARGET",
            "DAYLIGHT_DETECTOR", "NOTE_BLOCK", "TNT", "LIGHTNING_ROD", "CALIBRATED", "SCULK_SENSOR",
            "CRAFTER", "COPPER_BULB");

    private static final Set<String> BUILDING_SUFFIXES = Set.of(
            "_STAIRS", "_SLAB", "_WALL", "_FENCE", "_FENCE_GATE", "_PLANKS", "_BRICKS",
            "_DOOR", "_TRAPDOOR", "_PILLAR", "_TILES", "_GLASS", "_GLASS_PANE");

    private static final Set<String> BUILDING_FRAGMENTS = Set.of(
            "CONCRETE", "TERRACOTTA", "COBBLESTONE", "DEEPSLATE", "ANDESITE", "GRANITE",
            "DIORITE", "BLACKSTONE", "PRISMARINE", "PURPUR",
            "SANDSTONE", "QUARTZ_BLOCK", "MUD_BRICK", "NETHER_BRICK", "COPPER_BLOCK",
            "SCAFFOLDING", "BRICK");

    private static final Set<String> NATURAL_EXACT = Set.of(
            "STONE", "SMOOTH_STONE", "COBBLESTONE", "MOSSY_COBBLESTONE", "DEEPSLATE",
            "COBBLED_DEEPSLATE", "GRANITE", "DIORITE", "ANDESITE", "TUFF", "CALCITE",
            "BASALT", "SMOOTH_BASALT", "BLACKSTONE", "GRAVEL", "SAND", "RED_SAND",
            "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "CLAY", "MUD", "END_STONE",
            "NETHERRACK", "SOUL_SAND", "SOUL_SOIL", "OBSIDIAN", "CRYING_OBSIDIAN",
            "MAGMA_BLOCK", "SNOW_BLOCK", "SNOWBALL", "ICE", "PACKED_ICE", "BLUE_ICE",
            "MOSS_BLOCK", "GRASS_BLOCK", "PODZOL", "MYCELIUM", "POINTED_DRIPSTONE",
            "DRIPSTONE_BLOCK", "GLOWSTONE", "SCULK", "FLINT");

    private static final Set<String> NATURAL_FRAGMENTS = Set.of(
            "DIRT", "SAND", "GRAVEL", "CLAY", "ICE", "SNOW", "NETHERRACK", "END_STONE",
            "SOUL_SAND", "SOUL_SOIL", "MUD", "PODZOL", "MYCELIUM", "GRASS_BLOCK",
            "MAGMA_BLOCK", "OBSIDIAN", "TUFF", "CALCITE", "DRIPSTONE", "BASALT",
            "GLOWSTONE", "SCULK", "ROOTS", "NYLIUM");

    private static final Set<String> UTILITY_FRAGMENTS = Set.of(
            "CHEST", "BARREL", "FURNACE", "SMOKER", "CRAFTING_TABLE", "ANVIL",
            "ENCHANTING_TABLE", "CAULDRON", "BUCKET", "MINECART", "BOAT", "LADDER",
            "GRINDSTONE", "SMITHING_TABLE", "STONECUTTER", "LOOM", "COMPOSTER",
            "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "BEEHIVE", "BEE_NEST", "LECTERN",
            "BOOKSHELF", "BOOK", "PAPER", "MAP", "SHULKER_BOX", "SADDLE", "ARMOR_STAND");

    private static final Set<String> BREWING_FRAGMENTS = Set.of(
            "POTION", "BREWING_STAND", "BLAZE_POWDER", "FERMENTED_SPIDER_EYE",
            "GLISTERING_MELON", "GLASS_BOTTLE", "DRAGON_BREATH", "TIPPED_ARROW",
            "EXPERIENCE_BOTTLE");

    private static final Set<String> DECORATION_FRAGMENTS = Set.of(
            "BANNER", "CARPET", "BED", "CANDLE", "FLOWER", "PAINTING", "ITEM_FRAME",
            "SIGN", "TORCH", "LANTERN", "POT", "HEAD", "SKULL", "WOOL", "DYE",
            "GLAZED", "CHAIN", "BELL", "CAMPFIRE", "SAPLING", "LEAVES", "MOSS", "VINE");

    /**
     * Best-effort classification for a material we have no explicit category for.
     *
     * <p>Order matters. Tools are checked before building blocks so a stone axe does
     * not land under stone, and redstone components before decoration so a redstone
     * torch does not land with the torches. Anything that falls through all of it
     * ends up in Misc, which is the honest answer for a genuinely odd item.
     */
    /**
     * True for COAL_BLOCK, IRON_BLOCK, RAW_GOLD_BLOCK and friends.
     *
     * <p>These kept landing in Misc: they are not ores, not in the mineral list, and
     * match no building suffix, so they fell through everything. Strip the _BLOCK
     * and ask whether what is left is a mineral.
     */
    private static boolean isMineralBlock(String name) {
        if (!name.endsWith("_BLOCK")) {
            return false;
        }
        String base = name.substring(0, name.length() - "_BLOCK".length());
        return MINERAL_NAMES.contains(base) || base.startsWith("RAW_")
                || base.equals("IRON") || base.equals("GOLD") || base.equals("COPPER")
                || base.equals("NETHERITE") || base.equals("EMERALD") || base.equals("LAPIS");
    }

    public static Category classify(Material material) {
        String name = material.name().toUpperCase(Locale.ROOT);

        // Anything with durability is a tool, weapon or piece of armour. This is
        // what the name lists could never keep up with - 26.2's spears and nautilus
        // armour match no suffix I could have written in advance, and the next game
        // drop will add more. Durability is the property that actually defines the
        // category, so ask the game rather than guessing from the name.
        try {
            if (material.getMaxDurability() > 0) {
                return TOOLS;
            }
        } catch (Throwable ignored) {
            // Fall through to the name lists.
        }

        for (String suffix : TOOL_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return TOOLS;
            }
        }
        if (TOOL_NAMES.contains(name)) {
            return TOOLS;
        }

        if (name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE") || name.equals("BAMBOO_BLOCK")) {
            return WOOD;
        }
        if (name.endsWith("_ORE") || name.startsWith("RAW_") || MINERAL_NAMES.contains(name)
                || isMineralBlock(name) || name.contains("SULFUR") || name.contains("CINNABAR")) {
            return ORES;
        }
        if (MOB_DROP_NAMES.contains(name)) {
            return MOB_DROPS;
        }
        if (CROP_NAMES.contains(name)) {
            return FOOD;
        }
        try {
            if (material.isEdible()) {
                return FOOD;
            }
        } catch (Throwable ignored) {
            // Never let classification break startup.
        }

        for (String fragment : BREWING_FRAGMENTS) {
            if (name.contains(fragment)) {
                return BREWING;
            }
        }
        for (String fragment : REDSTONE_FRAGMENTS) {
            if (name.contains(fragment)) {
                return REDSTONE_TECH;
            }
        }
        for (String fragment : UTILITY_FRAGMENTS) {
            if (name.contains(fragment)) {
                return UTILITY;
            }
        }
        // Natural terrain is checked before building blocks so tuff, calcite and
        // basalt file as things you dig up rather than things you build with.
        if (NATURAL_EXACT.contains(name)) {
            return NATURAL;
        }
        for (String fragment : NATURAL_FRAGMENTS) {
            if (name.contains(fragment)) {
                return NATURAL;
            }
        }
        for (String suffix : BUILDING_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return BUILDING;
            }
        }
        for (String fragment : BUILDING_FRAGMENTS) {
            if (name.contains(fragment)) {
                return BUILDING;
            }
        }
        for (String fragment : DECORATION_FRAGMENTS) {
            if (name.contains(fragment)) {
                return DECORATION;
            }
        }
        return MISC;
    }
}
