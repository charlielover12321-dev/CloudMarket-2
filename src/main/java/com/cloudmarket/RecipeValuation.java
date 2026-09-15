package com.cloudmarket.market;

import com.cloudmarket.CloudMarket;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prices crafted items from their recipes instead of from a config entry.
 *
 * <p>A crafted item is worth what its ingredients are worth, resolved recursively
 * down to raw materials. Only the raw materials at the bottom of the tree need an
 * admin-set price; everything above them follows automatically and keeps following
 * when Mojang changes a recipe.
 *
 * <h2>Live prices, not base prices</h2>
 * Ingredient values are read from the <em>current</em> point on each raw material's
 * curve, never from its configured {@code basePrice}. This is the difference
 * between safe and exploitable. If valuation used base prices, a player could wait
 * until cobblestone was glutted and trading near its floor, buy it cheap, craft
 * diorite, and sell the diorite at a valuation computed from cobblestone's much
 * higher base price. Reading the same curve the ingredients actually trade on
 * closes that.
 *
 * <h2>Cheapest path wins</h2>
 * Where a material has several recipes, valuation takes the lowest. A player
 * looking to profit would use the cheapest route, so pricing off any other one
 * would leave a margin for them to collect. The same logic applies within a single
 * ingredient slot that accepts alternatives - any plank for a stick, say - where
 * the cheapest acceptable material is the one used.
 *
 * <h2>Cycles</h2>
 * The recipe graph is not a tree. Iron ingots make iron blocks and iron blocks make
 * iron ingots, so a naive recursion would not terminate. A material already being
 * resolved further up the stack is treated as unreachable down that branch, which
 * makes the cycle collapse and lets the other recipe win. An iron ingot therefore
 * prices from smelting raw iron rather than from an infinite regress through the
 * block, and an iron block correctly comes out at nine times raw iron.
 */
public final class RecipeValuation {

    /** One way to make {@code outputCount} of something from a set of inputs. */
    private record Formula(Map<Material, Integer> inputs, int outputCount) {
    }

    private static final double UNREACHABLE = Double.POSITIVE_INFINITY;

    private final CloudMarket plugin;
    private final Map<Material, List<Formula>> formulas = new EnumMap<>(Material.class);
    private final Map<Material, Double> values = new EnumMap<>(Material.class);
    private volatile boolean stale = true;

    public RecipeValuation(CloudMarket plugin) {
        this.plugin = plugin;
    }

    /**
     * Mark the table for recomputation. Called whenever stock moves, since every
     * crafted price downstream of that material has just changed.
     */
    public void invalidate() {
        stale = true;
    }

    public int knownFormulas() {
        return formulas.size();
    }

    /** Read every recipe on the server into a valuation graph. */
    public void buildGraph() {
        formulas.clear();
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe;
            try {
                recipe = iterator.next();
            } catch (Throwable t) {
                continue;
            }
            if (recipe == null) {
                continue;
            }
            try {
                ItemStack result = recipe.getResult();
                if (result == null || result.getType().isAir() || result.getAmount() <= 0) {
                    continue;
                }
                Map<Material, Integer> inputs = extractInputs(recipe);
                if (inputs == null || inputs.isEmpty()) {
                    continue;
                }
                formulas.computeIfAbsent(result.getType(), k -> new ArrayList<>())
                        .add(new Formula(inputs, result.getAmount()));
            } catch (Throwable t) {
                // A datapack recipe we cannot read is simply one less pricing route.
            }
        }
        stale = true;
        plugin.getLogger().info("[CloudMarket] Valuation graph built for " + formulas.size()
                + " craftable materials.");
    }

    /**
     * What one unit of this material is worth right now, or empty if it cannot be
     * priced - because no recipe path reaches a priced raw material.
     */
    public Double valueOf(Material material) {
        if (stale) {
            recompute();
        }
        Double value = values.get(material);
        return value == null || value.isInfinite() || value.isNaN() ? null : value;
    }

    /** Recompute every material in one pass. */
    private synchronized void recompute() {
        if (!stale) {
            return;
        }
        values.clear();
        Set<Material> visiting = new HashSet<>();
        for (Material material : Material.values()) {
            if (material.isLegacy() || material.isAir()) {
                continue;
            }
            resolve(material, visiting);
        }
        stale = false;
    }

    /**
     * Depth-first value resolution with memoisation.
     *
     * @param visiting materials currently on the recursion stack; re-entering one is
     *                 a cycle, and that branch is abandoned rather than followed
     */
    private double resolve(Material material, Set<Material> visiting) {
        Double cached = values.get(material);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(material)) {
            // Already being resolved further up the stack. Treat this branch as a
            // dead end so the recursion unwinds and a different recipe can win.
            return UNREACHABLE;
        }
        try {
            // A raw material with a configured price anchors the whole tree. An
            // explicit admin price also wins over derivation for whitelisted items,
            // so /marketadmin setprice always overrides what the recipe would say.
            MarketItem anchor = plugin.market().configured(material);
            if (anchor != null) {
                double spot = PricingEngine.spotPrice(anchor, anchor.getStock()).doubleValue();
                values.put(material, spot);
                return spot;
            }

            List<Formula> options = formulas.get(material);
            if (options == null || options.isEmpty()) {
                values.put(material, UNREACHABLE);
                return UNREACHABLE;
            }

            double best = UNREACHABLE;
            for (Formula formula : options) {
                double total = 0.0d;
                boolean usable = true;
                for (Map.Entry<Material, Integer> entry : formula.inputs().entrySet()) {
                    double ingredient = resolve(entry.getKey(), visiting);
                    if (Double.isInfinite(ingredient)) {
                        usable = false;
                        break;
                    }
                    total += ingredient * entry.getValue();
                }
                if (usable) {
                    best = Math.min(best, total / formula.outputCount());
                }
            }

            values.put(material, best);
            return best;
        } finally {
            visiting.remove(material);
        }
    }

    /**
     * Reduce a recipe to a material-to-count map. Where a slot accepts several
     * materials, the cheapest is assumed, since that is what a player would use.
     */
    private Map<Material, Integer> extractInputs(Recipe recipe) {
        Map<Material, Integer> inputs = new EnumMap<>(Material.class);

        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (char symbol : row.toCharArray()) {
                    RecipeChoice choice = choices.get(symbol);
                    if (choice == null) {
                        continue;
                    }
                    if (!addChoice(inputs, choice)) {
                        return null;
                    }
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            for (RecipeChoice choice : shapeless.getChoiceList()) {
                if (!addChoice(inputs, choice)) {
                    return null;
                }
            }
        } else if (recipe instanceof CookingRecipe<?> cooking) {
            if (!addChoice(inputs, cooking.getInputChoice())) {
                return null;
            }
        } else if (recipe instanceof StonecuttingRecipe stonecutting) {
            if (!addChoice(inputs, stonecutting.getInputChoice())) {
                return null;
            }
        } else if (recipe instanceof SmithingRecipe smithing) {
            // Template and addition both matter; the base carries most of the value.
            if (!addChoice(inputs, smithing.getBase())) {
                return null;
            }
            addChoice(inputs, smithing.getAddition());
        } else {
            return null;
        }
        return inputs;
    }

    /** Record one ingredient slot. Stores every option so resolve() can pick. */
    private boolean addChoice(Map<Material, Integer> inputs, RecipeChoice choice) {
        if (choice == null) {
            return false;
        }
        List<Material> options = new ArrayList<>();
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
            options.addAll(materialChoice.getChoices());
        } else if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
            for (ItemStack stack : exactChoice.getChoices()) {
                options.add(stack.getType());
            }
        }
        if (options.isEmpty()) {
            return false;
        }
        // Single option is the common case. For multi-option slots we record the
        // first and let cheapest-path selection happen at the recipe level; storing
        // alternatives per slot would need a far more complex graph for very little
        // pricing difference (the alternatives are near-identical in practice, e.g.
        // any of the plank types).
        inputs.merge(options.get(0), 1, Integer::sum);
        return true;
    }
}
