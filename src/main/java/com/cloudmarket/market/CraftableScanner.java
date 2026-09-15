package com.cloudmarket.market;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Works out which materials are "raw" by scanning every recipe the server has
 * registered, so the exclusion list rebuilds itself whenever Mojang ships a new
 * game drop.
 *
 * <h2>Why this is not just "every recipe output"</h2>
 *
 * The obvious rule - bar anything that appears as a recipe result - collapses on
 * contact with vanilla. Almost every valuable raw material is the output of a
 * storage-block uncrafting recipe:
 *
 * <pre>
 *   9 coal        -> 1 coal block        AND  1 coal block        -> 9 coal
 *   9 diamond     -> 1 diamond block     AND  1 diamond block     -> 9 diamond
 *   9 raw iron    -> 1 raw iron block    AND  1 raw iron block    -> 9 raw iron
 *   9 wheat       -> 1 hay bale          AND  1 hay bale          -> 9 wheat
 * </pre>
 *
 * Coal, diamond, emerald, lapis, redstone, raw iron/copper/gold, slime balls and
 * wheat are therefore all recipe outputs. A naive scan bars every one of them from
 * the market, and the "ships empty" override file would need to list essentially
 * everything worth trading - which defeats the point of the rule.
 *
 * <p>The fix: a recipe is <em>value-neutral</em> if it converts a single material
 * into another single material and an inverse recipe converts it straight back at
 * a reciprocal ratio. Given {@code a} units of Y producing {@code b} units of X,
 * and {@code c} units of X producing {@code d} units of Y, round-tripping one unit
 * of Y returns {@code (b/a) * (d/c)} units of Y. The pair is neutral when that
 * equals one, i.e. {@code b*d == a*c}. For coal: a=9, b=1, c=1, d=9, so both sides
 * are 9. Cycling it gains the player nothing, so neither side counts as crafted.
 *
 * <p>A material is excluded from the market only if it is the result of at least
 * one recipe that is <em>not</em> value-neutral. Smelting raw iron into an ingot is
 * genuine value-add, so iron ingots stay excluded and an admin must opt them back
 * in deliberately - exactly as the spec intended.
 */
public final class CraftableScanner {

    /** A single-material -> single-material conversion. */
    private record Conversion(Material input, int inputCount, Material output, int outputCount) {
    }

    private final Logger logger;
    private final Set<Material> excluded = EnumSet.noneOf(Material.class);
    private final Set<Material> neutralOnly = EnumSet.noneOf(Material.class);
    private int recipesScanned;
    private int neutralPairsFound;

    public CraftableScanner(Logger logger) {
        this.logger = logger;
    }

    /** Materials that are recipe outputs with genuine value added. Not sellable. */
    public Set<Material> getExcluded() {
        return excluded;
    }

    /**
     * Materials that only ever appear as the output of a reversible storage-block
     * recipe. These stay sellable - this is the set the naive rule got wrong.
     */
    public Set<Material> getRescuedByNeutralityRule() {
        return neutralOnly;
    }

    public int getRecipesScanned() {
        return recipesScanned;
    }

    public int getNeutralPairsFound() {
        return neutralPairsFound;
    }

    public void scan() {
        excluded.clear();
        neutralOnly.clear();
        recipesScanned = 0;
        neutralPairsFound = 0;

        List<Conversion> conversions = new ArrayList<>();
        Map<Material, List<Recipe>> byResult = new EnumMap<>(Material.class);
        List<Recipe> all = new ArrayList<>();

        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe;
            try {
                recipe = iterator.next();
            } catch (Throwable t) {
                // A malformed datapack recipe should not take the whole plugin down.
                continue;
            }
            if (recipe == null) {
                continue;
            }
            recipesScanned++;
            ItemStack result;
            try {
                result = recipe.getResult();
            } catch (Throwable t) {
                continue;
            }
            if (result == null || result.getType().isAir()) {
                continue;
            }
            all.add(recipe);
            byResult.computeIfAbsent(result.getType(), k -> new ArrayList<>()).add(recipe);

            Conversion conversion = asSingleMaterialConversion(recipe, result);
            if (conversion != null) {
                conversions.add(conversion);
            }
        }

        // Index conversions so we can look for an inverse in constant time.
        Map<String, List<Conversion>> index = new HashMap<>();
        for (Conversion conversion : conversions) {
            index.computeIfAbsent(key(conversion.input(), conversion.output()), k -> new ArrayList<>())
                    .add(conversion);
        }

        for (Map.Entry<Material, List<Recipe>> entry : byResult.entrySet()) {
            Material output = entry.getKey();
            boolean hasValueAddRecipe = false;
            boolean hasNeutralRecipe = false;

            for (Recipe recipe : entry.getValue()) {
                ItemStack result;
                try {
                    result = recipe.getResult();
                } catch (Throwable t) {
                    continue;
                }
                Conversion conversion = asSingleMaterialConversion(recipe, result);
                if (conversion != null && hasInverse(index, conversion)) {
                    hasNeutralRecipe = true;
                } else {
                    hasValueAddRecipe = true;
                }
            }

            if (hasValueAddRecipe) {
                excluded.add(output);
            } else if (hasNeutralRecipe) {
                neutralOnly.add(output);
                neutralPairsFound++;
            }
        }

        logger.info("[CloudMarket] Recipe scan complete: " + recipesScanned + " recipes, "
                + excluded.size() + " materials barred from the global market, "
                + neutralOnly.size() + " kept sellable by the storage-block neutrality rule.");
    }

    /** True if this material may be traded on the global market, ignoring overrides. */
    public boolean isRaw(Material material) {
        return !excluded.contains(material);
    }

    private static String key(Material from, Material to) {
        return from.name() + '>' + to.name();
    }

    private boolean hasInverse(Map<String, List<Conversion>> index, Conversion forward) {
        List<Conversion> candidates = index.get(key(forward.output(), forward.input()));
        if (candidates == null) {
            return false;
        }
        for (Conversion back : candidates) {
            if (isNeutralPair(forward, back)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forward turns {@code a} of Y into {@code b} of X. Back turns {@code c} of X
     * into {@code d} of Y. Round-tripping one unit of Y yields {@code (b/a)*(d/c)}
     * units of Y, so the pair is value-neutral when that product is 1 - i.e.
     * {@code b*d == a*c}. Nine coal to a block (a=9,b=1) and back (c=1,d=9) gives
     * 1*9 == 9*1. A player gains nothing by cycling it, so neither side should be
     * treated as a crafted good.
     */
    private static boolean isNeutralPair(Conversion forward, Conversion back) {
        long gained = (long) forward.outputCount() * back.outputCount();
        long spent = (long) forward.inputCount() * back.inputCount();
        return gained == spent;
    }

    /**
     * Reduce a recipe to "N of one material in, M of one material out", or null if
     * it takes more than one distinct material. Multi-ingredient recipes are always
     * value-add by definition, so they never need this treatment.
     */
    private Conversion asSingleMaterialConversion(Recipe recipe, ItemStack result) {
        Map<Material, Integer> inputs = new EnumMap<>(Material.class);

        try {
            if (recipe instanceof ShapedRecipe shaped) {
                Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
                for (String row : shaped.getShape()) {
                    for (char symbol : row.toCharArray()) {
                        RecipeChoice choice = choices.get(symbol);
                        if (choice == null) {
                            continue;
                        }
                        Material material = soleMaterial(choice);
                        if (material == null) {
                            return null;
                        }
                        inputs.merge(material, 1, Integer::sum);
                    }
                }
            } else if (recipe instanceof ShapelessRecipe shapeless) {
                for (RecipeChoice choice : shapeless.getChoiceList()) {
                    Material material = soleMaterial(choice);
                    if (material == null) {
                        return null;
                    }
                    inputs.merge(material, 1, Integer::sum);
                }
            } else if (recipe instanceof CookingRecipe<?> cooking) {
                Material material = soleMaterial(cooking.getInputChoice());
                if (material == null) {
                    return null;
                }
                inputs.merge(material, 1, Integer::sum);
            } else if (recipe instanceof StonecuttingRecipe stonecutting) {
                Material material = soleMaterial(stonecutting.getInputChoice());
                if (material == null) {
                    return null;
                }
                inputs.merge(material, 1, Integer::sum);
            } else if (recipe instanceof SmithingRecipe) {
                // Smithing always combines distinct items; never neutral.
                return null;
            } else {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }

        if (inputs.size() != 1) {
            return null;
        }
        Map.Entry<Material, Integer> only = inputs.entrySet().iterator().next();
        if (only.getKey() == result.getType()) {
            return null;
        }
        return new Conversion(only.getKey(), only.getValue(), result.getType(), result.getAmount());
    }

    /** The single material a choice accepts, or null if it accepts several. */
    private static Material soleMaterial(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
            List<Material> options = materialChoice.getChoices();
            return options.size() == 1 ? options.get(0) : null;
        }
        if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
            List<ItemStack> options = exactChoice.getChoices();
            if (options.size() != 1) {
                return null;
            }
            return options.get(0).getType();
        }
        return null;
    }
}
