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
    SPAWN_EGGS("Spawn Eggs", Material.PIG_SPAWN_EGG),
    MUSIC("Music Discs", Material.JUKEBOX),
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
            "SCAFFOLDING", "BRICK", "COPPER", "CHISELED", "CUT_", "POLISHED", "SMOOTH_",
            "RESIN", "IRON_BARS", "GLASS_PANE", "LANTERN", "CHAIN", "GRATE", "BULB");

    private static final Set<String> FLOWERS = Set.of(
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "OXEYE_DAISY",
            "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE", "SUNFLOWER", "LILAC",
            "ROSE_BUSH", "PEONY", "TORCHFLOWER", "PITCHER_PLANT", "SPORE_BLOSSOM",
            "PINK_PETALS", "WILDFLOWERS", "LEAF_LITTER", "CACTUS_FLOWER", "FIREFLY_BUSH",
            "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP", "LILY_PAD");

    private static final Set<String> PLANTS = Set.of(
            "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "SEAGRASS", "TALL_SEAGRASS",
            "DEAD_BUSH", "BUSH", "VINE", "WEEPING_VINES", "TWISTING_VINES", "GLOW_LICHEN",
            "HANGING_ROOTS", "BIG_DRIPLEAF", "SMALL_DRIPLEAF", "AZALEA", "FLOWERING_AZALEA");

    /** Block forms of food and farm produce. */
    private static final Set<String> FOOD_BLOCKS = Set.of(
            "HAY_BLOCK", "DRIED_KELP_BLOCK", "MELON", "PUMPKIN", "CARVED_PUMPKIN",
            "JACK_O_LANTERN", "CAKE", "HONEY_BLOCK", "HONEYCOMB_BLOCK",
            "NETHER_WART_BLOCK", "WARPED_WART_BLOCK");

    /** Organic and mob-derived blocks that are neither built nor mined. */
    private static final Set<String> ORGANIC_BLOCKS = Set.of(
            "SPONGE", "WET_SPONGE", "MUSHROOM_STEM", "BROWN_MUSHROOM_BLOCK",
            "RED_MUSHROOM_BLOCK", "BROWN_MUSHROOM", "RED_MUSHROOM", "SHROOMLIGHT",
            "BONE_BLOCK", "SLIME_BLOCK", "OCHRE_FROGLIGHT", "VERDANT_FROGLIGHT",
            "PEARLESCENT_FROGLIGHT", "TURTLE_EGG", "COBWEB");

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
            "HARNESS", "BUNDLE", "MOJANG_BANNER_PATTERN",
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

        if (name.endsWith("_SPAWN_EGG")) {
            return SPAWN_EGGS;
        }
        if (name.startsWith("MUSIC_DISC") || name.equals("JUKEBOX")
                || name.equals("NOTE_BLOCK") || name.equals("DISC_FRAGMENT_5")) {
            return MUSIC;
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
        // Worked metal counts as ore-adjacent. Without the ingot and nugget cases,
        // COPPER_INGOT falls through to the building rules below and files itself
        // next to copper stairs.
        if (name.endsWith("_ORE") || name.startsWith("RAW_") || name.endsWith("_INGOT")
                || name.endsWith("_NUGGET") || name.endsWith("_SCRAP")
                || MINERAL_NAMES.contains(name) || isMineralBlock(name)
                || name.contains("AMETHYST") || name.contains("SULFUR")
                || name.contains("CINNABAR")) {
            return ORES;
        }
        if (MOB_DROP_NAMES.contains(name) || name.endsWith("_EGG")
                || name.equals("EGG") || name.endsWith("_SHELL")) {
            return MOB_DROPS;
        }
        if (CROP_NAMES.contains(name) || FOOD_BLOCKS.contains(name)) {
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
        if (ORGANIC_BLOCKS.contains(name)) {
            return NATURAL;
        }
        if (FLOWERS.contains(name)) {
            return DECORATION;
        }
        if (PLANTS.contains(name) || name.contains("CORAL")) {
            return NATURAL;
        }
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
