package com.cloudmarket.market;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Two lists the automatic pricing cannot work out for itself.
 *
 * <h2>Unobtainable items</h2>
 * Some materials are items, and are therefore eligible, but cannot be got in
 * survival at all - bedrock, barriers, command blocks, end portal frames. A market
 * entry for them is at best clutter and at worst a laundering route if an admin or
 * a buggy plugin ever leaks one into circulation. They are barred outright.
 *
 * <p>Note this list only needs materials that <em>are</em> items. Things like water,
 * fire and piston heads never reach here, because the market already skips anything
 * where {@code Material.isItem()} is false.
 *
 * <h2>Rare survival items</h2>
 * The autoconfig heuristic prices by category, which lands a music disc and a lump
 * of dirt in the same bucket - both are Misc, so both come out around half a coin.
 * That is badly wrong for anything rare and uncraftable, where difficulty of
 * acquisition, not material composition, is what the price should reflect. Those
 * get explicit values here.
 *
 * <p>Rare items are priced with a narrow band rather than a truly flat price: the
 * floor sits at 70% of base and the ceiling at 130%, so the price is stable and
 * predictable but still drifts down if somebody finds a way to supply them in bulk.
 * A genuinely flat price on something renewable - and most of these are renewable,
 * however slowly - would be an unbounded money source.
 */
public final class ItemRarity {

    private ItemRarity() {
    }

    /** Fraction of base price the floor sits at for rare items. */
    public static final double RARE_FLOOR_FRACTION = 0.70d;

    /** Fraction of base price the ceiling sits at for rare items. */
    public static final double RARE_CEILING_FRACTION = 1.30d;

    /** Rare items move slowly, so the curve should barely budge at normal volumes. */
    public static final long RARE_EQUILIBRIUM = 64L;

    /**
     * Items that exist only in creative mode, only in world generation, or only as
     * a block state. Never tradable on the cloud market.
     */
    private static final Set<String> UNOBTAINABLE = Set.of(
            // Creative and operator only.
            "BEDROCK", "BARRIER", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
            "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
            "COMMAND_BLOCK_MINECART", "DEBUG_STICK", "LIGHT", "KNOWLEDGE_BOOK",

            // Cannot be picked up, even with Silk Touch.
            "END_PORTAL_FRAME", "BUDDING_AMETHYST", "REINFORCED_DEEPSLATE",
            "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL", "TRIAL_SPAWNER", "VAULT",
            "PETRIFIED_OAK_SLAB", "FROGSPAWN", "CHORUS_PLANT",

            // Block states that drop something else when broken.
            "FARMLAND", "DIRT_PATH",

            // Silverfish blocks. Tradable only as a grief tool.
            "INFESTED_STONE", "INFESTED_COBBLESTONE", "INFESTED_STONE_BRICKS",
            "INFESTED_MOSSY_STONE_BRICKS", "INFESTED_CRACKED_STONE_BRICKS",
            "INFESTED_CHISELED_STONE_BRICKS", "INFESTED_DEEPSLATE",

            // Auction-only. These are not unobtainable - they are unique, carry
            // data the cloud market throws away, or are worth far more than any
            // single price could express. Barring them here keeps them OFF /shop
            // while leaving /auction completely free, which is the right venue for
            // a Mending book or a dragon egg anyway.
            "ENCHANTED_BOOK", "DRAGON_EGG", "DRAGON_HEAD", "ELYTRA", "HEAVY_CORE",
            "SPAWNER", "NETHER_STAR", "TOTEM_OF_UNDYING", "TRIDENT", "SNIFFER_EGG",
            "ENCHANTED_GOLDEN_APPLE", "HEART_OF_THE_SEA", "STICK"
    );

    /**
     * The rarest music discs - mob-drop-only or structure-loot-only - as opposed to
     * the common ones a creeper-killed skeleton hands out.
     */
    private static final Set<String> RARE_DISCS = Set.of(
            "MUSIC_DISC_PIGSTEP", "MUSIC_DISC_OTHERSIDE", "MUSIC_DISC_RELIC",
            "MUSIC_DISC_5", "MUSIC_DISC_CREATOR", "MUSIC_DISC_CREATOR_MUSIC_BOX",
            "MUSIC_DISC_PRECIPICE", "MUSIC_DISC_LAVA_CHICKEN", "MUSIC_DISC_TEARS"
    );

    /**
     * Spawn egg prices, tiered by how hard the mob is to reach rather than by
     * anything about the egg itself.
     *
     * <p>A single flat price would be absurd across this range: a chicken and a
     * warden are the same item type and nothing like the same achievement. The
     * boss entries are included in case a plugin on your server provides eggs
     * vanilla does not - if the material does not exist, it is simply skipped.
     */
    private static final Set<String> BOSS_EGGS = Set.of("ENDER_DRAGON", "WARDEN");

    private static final Set<String> WITHER_TIER = Set.of("WITHER", "ELDER_GUARDIAN");

    private static final Set<String> RARE_MOBS = Set.of(
            "WITHER_SKELETON", "EVOKER", "VINDICATOR", "RAVAGER", "SHULKER", "GHAST",
            "HAPPY_GHAST", "ZOGLIN", "PIGLIN_BRUTE", "ILLUSIONER", "GIANT", "SNIFFER",
            "ALLAY", "CAMEL", "IRON_GOLEM", "CREAKING");

    private static final Set<String> UNCOMMON_MOBS = Set.of(
            "ENDERMAN", "WITCH", "BLAZE", "GUARDIAN", "PILLAGER", "HOGLIN", "PIGLIN",
            "MAGMA_CUBE", "PHANTOM", "CAVE_SPIDER", "SILVERFISH", "ENDERMITE", "VEX",
            "BREEZE", "BOGGED", "DROWNED", "HUSK", "STRAY", "SKELETON_HORSE",
            "ZOMBIE_HORSE", "MULE", "LLAMA", "TRADER_LLAMA", "PANDA", "POLAR_BEAR",
            "TURTLE", "DOLPHIN", "AXOLOTL", "GLOW_SQUID", "GOAT", "FROG", "TADPOLE",
            "ARMADILLO", "WANDERING_TRADER", "VILLAGER", "ZOMBIE_VILLAGER", "STRIDER",
            "PARROT", "OCELOT", "FOX");

    /** Common mobs: everything you trip over in the first hour. */
    private static final double COMMON_EGG_PRICE = 50.0d;

    public static java.util.OptionalDouble spawnEggPrice(String materialName) {
        String name = materialName.toUpperCase(Locale.ROOT);
        if (!name.endsWith("_SPAWN_EGG")) {
            return java.util.OptionalDouble.empty();
        }
        String mob = name.substring(0, name.length() - "_SPAWN_EGG".length());

        if (mob.equals("ENDER_DRAGON")) {
            return java.util.OptionalDouble.of(10_000.0d);
        }
        if (BOSS_EGGS.contains(mob)) {
            return java.util.OptionalDouble.of(7_500.0d);
        }
        if (WITHER_TIER.contains(mob)) {
            return java.util.OptionalDouble.of(5_000.0d);
        }
        if (RARE_MOBS.contains(mob)) {
            return java.util.OptionalDouble.of(1_200.0d);
        }
        if (UNCOMMON_MOBS.contains(mob)) {
            return java.util.OptionalDouble.of(400.0d);
        }
        return java.util.OptionalDouble.of(COMMON_EGG_PRICE);
    }

    public static boolean isUnobtainable(Material material) {
        return UNOBTAINABLE.contains(material.name().toUpperCase(Locale.ROOT));
    }

    /** Names of everything barred, for the config file comment and admin output. */
    public static List<String> unobtainableNames() {
        return UNOBTAINABLE.stream().sorted().toList();
    }

    /**
     * A sensible base price for a rare uncraftable item, or empty if the ordinary
     * category heuristic should handle it.
     *
     * <p>These are opening values, not balance. Difficulty of acquisition varies
     * enormously between servers - a spawn egg means something different where
     * spawners can be mined - so treat them as a starting point.
     */
    public static OptionalDouble rarePrice(Material material) {
        String name = material.name().toUpperCase(Locale.ROOT);

        // One per world, and only by pushing it off the bedrock.
        if (name.equals("DRAGON_EGG")) {
            return OptionalDouble.of(100_000.0d);
        }
        if (name.equals("DRAGON_HEAD")) {
            return OptionalDouble.of(25_000.0d);
        }
        if (name.equals("SPAWNER")) {
            return OptionalDouble.of(5_000.0d);
        }
        if (name.equals("HEAVY_CORE")) {
            return OptionalDouble.of(3_000.0d);
        }
        if (name.equals("ELYTRA")) {
            return OptionalDouble.of(3_000.0d);
        }
        if (name.equals("NETHER_STAR") || name.equals("ENCHANTED_GOLDEN_APPLE")) {
            return OptionalDouble.of(2_500.0d);
        }
        if (name.equals("TRIDENT") || name.equals("SNIFFER_EGG")) {
            return OptionalDouble.of(2_000.0d);
        }
        if (name.endsWith("_SPAWN_EGG")) {
            return spawnEggPrice(name);
        }
        if (name.equals("HEART_OF_THE_SEA") || name.equals("WITHER_SKELETON_SKULL")) {
            return OptionalDouble.of(1_500.0d);
        }
        if (name.equals("TOTEM_OF_UNDYING")
                || name.equals("NETHERITE_UPGRADE_SMITHING_TEMPLATE")) {
            return OptionalDouble.of(1_200.0d);
        }
        if (RARE_DISCS.contains(name)) {
            return OptionalDouble.of(900.0d);
        }
        if (name.endsWith("_HEAD") || name.endsWith("_SKULL")) {
            return OptionalDouble.of(800.0d);
        }
        if (name.endsWith("_SMITHING_TEMPLATE")) {
            return OptionalDouble.of(600.0d);
        }
        if (name.equals("OMINOUS_TRIAL_KEY") || name.equals("BELL")) {
            return OptionalDouble.of(500.0d);
        }
        if (name.equals("ECHO_SHARD")) {
            return OptionalDouble.of(400.0d);
        }
        if (name.startsWith("MUSIC_DISC")) {
            return OptionalDouble.of(350.0d);
        }
        if (name.equals("NAME_TAG") || name.equals("OMINOUS_BOTTLE")
                || name.equals("TRIAL_KEY")) {
            return OptionalDouble.of(300.0d);
        }
        if (name.equals("SADDLE")) {
            return OptionalDouble.of(250.0d);
        }
        return OptionalDouble.empty();
    }

    public static boolean isRare(Material material) {
        return rarePrice(material).isPresent();
    }
}
