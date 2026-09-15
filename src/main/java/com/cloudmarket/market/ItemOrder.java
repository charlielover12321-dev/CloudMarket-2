package com.cloudmarket.market;

import org.bukkit.Material;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Sorts shop pages the way a player expects to read them.
 *
 * <p>Alphabetical order scatters related items across pages: acacia planks land
 * next to amethyst, and a diamond pickaxe sits nowhere near a diamond sword. What
 * people actually navigate by is <em>family first, form second</em> - all the
 * wooden tools together in tier order, all the oak blocks together, all the iron
 * things together.
 *
 * <h2>How a name is read</h2>
 * Two ranks are pulled out of the material name:
 *
 * <ul>
 *   <li><b>Family</b> - the material or tier: WOODEN, STONE, IRON, OAK, SPRUCE,
 *       RED, COPPER. Taken from {@link #FAMILIES} by first match, so the list order
 *       <em>is</em> the display order and also resolves ambiguity. DEEPSLATE_IRON_ORE
 *       contains both DEEPSLATE and IRON; IRON appears earlier in the list, so the
 *       ore files under iron rather than under deepslate, which is where someone
 *       looking for iron would expect it.</li>
 *   <li><b>Form</b> - what the thing is: SWORD, PICKAXE, PLANKS, STAIRS, SLAB.
 *       Matched on the end of the name, longest token first, so PICKAXE wins over
 *       AXE rather than every pickaxe filing as an axe.</li>
 * </ul>
 *
 * <p>Anything unrecognised sorts to the end of its category alphabetically, which
 * keeps new materials from a future game drop from landing in the middle of a tidy
 * run of tools.
 */
public final class ItemOrder {

    private ItemOrder() {
    }

    /**
     * Families in display order. Tool and armour tiers come first so they win
     * against material names appearing later, then metals, then wood, stone, copper
     * and finally the dye colours.
     */
    private static final List<String> FAMILIES = List.of(
            // Tool tiers and metals share names - IRON is both a pickaxe tier and an
            // ore - so they live in one interleaved list rather than two. The order
            // below reads correctly from either direction: a Tools page shows
            // wooden, stone, gold, iron, diamond, netherite, while an Ores page
            // shows coal, copper, gold, iron, redstone, lapis, emerald, diamond.
            "WOODEN", "STONE", "COAL", "COPPER", "CHAINMAIL", "GOLDEN", "GOLD",
            "IRON", "REDSTONE", "LAPIS", "EMERALD", "QUARTZ", "AMETHYST",
            "DIAMOND", "NETHERITE", "ANCIENT_DEBRIS", "LEATHER", "TURTLE",

            // Wood species. Compound names first so DARK_OAK does not match OAK.
            "DARK_OAK", "PALE_OAK", "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA",
            "MANGROVE", "CHERRY", "BAMBOO", "CRIMSON", "WARPED",

            // Stone and terrain families. Compound names before their bases.
            "COBBLED_DEEPSLATE", "POLISHED_DEEPSLATE", "DEEPSLATE",
            "MOSSY_COBBLESTONE", "COBBLESTONE", "SMOOTH_STONE",
            "POLISHED_GRANITE", "GRANITE", "POLISHED_DIORITE", "DIORITE",
            "POLISHED_ANDESITE", "ANDESITE", "POLISHED_BLACKSTONE", "BLACKSTONE",
            "POLISHED_BASALT", "BASALT", "TUFF", "CALCITE", "DRIPSTONE",
            "RED_SANDSTONE", "SANDSTONE", "PRISMARINE", "PURPUR", "NETHER_BRICK",
            "END_STONE", "TERRACOTTA", "CONCRETE", "MUD",

            // Weathering stages for copper blocks.
            "WAXED", "OXIDIZED", "WEATHERED", "EXPOSED",

            // Dye colours, in the vanilla order players see in a loom.
            "WHITE", "LIGHT_GRAY", "GRAY", "BLACK", "BROWN", "RED", "ORANGE",
            "YELLOW", "LIME", "GREEN", "CYAN", "LIGHT_BLUE", "BLUE", "PURPLE",
            "MAGENTA", "PINK"
    );

    /**
     * Forms in display order, matched against the end of the name. Sorted longest
     * first at match time so PICKAXE beats AXE and FENCE_GATE beats GATE.
     */
    private static final List<String> FORMS = List.of(
            // Tools, then armour top to bottom, the order they sit in an inventory.
            "SWORD", "PICKAXE", "AXE", "SHOVEL", "HOE",
            "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "HORSE_ARMOR",

            // Raw to refined.
            "ORE", "RAW", "INGOT", "NUGGET", "SCRAP", "SHARD", "DUST", "BLOCK",

            // Wood forms, from the tree outward.
            "SAPLING", "LOG", "WOOD", "LEAVES", "PLANKS",
            "STAIRS", "SLAB", "FENCE", "FENCE_GATE", "DOOR", "TRAPDOOR",
            "SIGN", "HANGING_SIGN", "BUTTON", "PRESSURE_PLATE", "BOAT", "CHEST_BOAT",

            // Worked stone.
            "BRICKS", "BRICK", "TILES", "WALL", "PILLAR",

            // Coloured things.
            "WOOL", "CARPET", "BED", "BANNER", "CANDLE", "GLASS", "GLASS_PANE",
            "SHULKER_BOX", "DYE"
    );

    /** The family this material belongs to, or an empty string if unrecognised. */
    public static String familyOf(Material material) {
        String name = material.name().toUpperCase(Locale.ROOT);
        // Stripped logs belong with their species, not in a family of their own.
        if (name.startsWith("STRIPPED_")) {
            name = name.substring("STRIPPED_".length());
        }
        for (String family : FAMILIES) {
            if (contains(name, family)) {
                return family;
            }
        }
        return "";
    }

    private static int familyRank(Material material) {
        String family = familyOf(material);
        if (family.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return FAMILIES.indexOf(family);
    }

    private static int formRank(Material material) {
        String name = material.name().toUpperCase(Locale.ROOT);

        // Ranks are spaced by ten so a bare material - COAL, DIAMOND, EMERALD -
        // can slot between RAW and INGOT. Without this it matches no form at all
        // and sorts to the very end, putting plain coal after the coal block.
        if (name.equals(familyOf(material))) {
            return FORMS.indexOf("RAW") * 10 + 5;
        }

        int best = Integer.MAX_VALUE;
        int bestLength = -1;
        for (int index = 0; index < FORMS.size(); index++) {
            String form = FORMS.get(index);
            boolean matches = name.equals(form) || name.endsWith("_" + form)
                    || (form.equals("RAW") && name.startsWith("RAW_"));
            // Longest match wins, so PICKAXE is not swallowed by AXE.
            if (matches && form.length() > bestLength) {
                best = index * 10;
                bestLength = form.length();
            }
        }
        return best;
    }

    /** Whole-token containment, so IRON matches IRON_ORE but not IRONWOOD. */
    private static boolean contains(String name, String token) {
        return name.equals(token)
                || name.startsWith(token + "_")
                || name.endsWith("_" + token)
                || name.contains("_" + token + "_");
    }

    /**
     * Family, then form, then name. The final name comparison only breaks ties
     * between two items of the same family and form, so it never scatters a group.
     */
    public static final Comparator<MarketItem> COMPARATOR = Comparator
            .comparingInt((MarketItem item) -> familyRank(item.getMaterial()))
            .thenComparingInt(item -> formRank(item.getMaterial()))
            .thenComparing(item -> item.getMaterial().name());
}
