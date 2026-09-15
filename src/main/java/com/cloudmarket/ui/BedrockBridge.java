package com.cloudmarket.ui;

import com.cloudmarket.CloudMarket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Everything Geyser/Floodgate-specific, isolated behind one class.
 *
 * <h2>Why isolate it</h2>
 * Floodgate is a soft dependency. If a class that directly references
 * {@code FloodgateApi} gets loaded on a server without Floodgate installed, the JVM
 * throws {@link NoClassDefFoundError} at class-load time - not at call time - and
 * takes the whole plugin down. Keeping every Floodgate type inside
 * {@link FloodgateHooks}, which is only ever loaded after a plugin-presence check,
 * means CloudMarket runs unchanged on a Java-only server.
 *
 * <h2>What actually needs special handling</h2>
 * Chest-style inventories translate to Bedrock fine, so the shop GUIs work for
 * everyone with no branching. Text and number entry is the part that does not:
 * anvil-rename and sign-edit inputs, the usual Java tricks for "type an amount",
 * are unreliable-to-broken through Geyser. So any prompt for a number or a price
 * routes to a native Bedrock form for Floodgate players and to a chat prompt for
 * Java players.
 */
public final class BedrockBridge {

    private final CloudMarket plugin;
    private boolean floodgatePresent;
    private String usernamePrefix = "";

    public BedrockBridge(CloudMarket plugin) {
        this.plugin = plugin;
    }

    public void initialise() {
        floodgatePresent = Bukkit.getPluginManager().getPlugin("floodgate") != null
                && Bukkit.getPluginManager().isPluginEnabled("floodgate");
        if (floodgatePresent) {
            try {
                usernamePrefix = FloodgateHooks.readUsernamePrefix();
                plugin.getLogger().info("[CloudMarket] Floodgate detected. Bedrock players will get "
                        + "native forms for number entry (username prefix: '" + usernamePrefix + "').");
            } catch (Throwable t) {
                floodgatePresent = false;
                plugin.getLogger().warning("[CloudMarket] Floodgate is installed but its API could not be "
                        + "reached (" + t.getClass().getSimpleName() + "). Falling back to chat prompts "
                        + "for all players.");
            }
        }
    }

    public boolean isFloodgatePresent() {
        return floodgatePresent;
    }

    public String getUsernamePrefix() {
        return usernamePrefix;
    }

    /** True if this player is connected through Geyser. */
    public boolean isBedrock(UUID uuid) {
        if (!floodgatePresent) {
            return false;
        }
        try {
            return FloodgateHooks.isFloodgatePlayer(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isBedrock(Player player) {
        return isBedrock(player.getUniqueId());
    }

    /**
     * Ask the player for a whole number. Bedrock players get a native input form;
     * Java players get a chat prompt. {@code onResult} is always invoked on the
     * main thread, or not at all if the player cancels.
     */
    public void promptForNumber(Player player, String title, String label, int min, int max,
                                Consumer<Integer> onResult) {
        if (plugin.configs().useBedrockForms() && isBedrock(player)) {
            try {
                FloodgateHooks.sendNumberForm(plugin, player, title, label, min, max, onResult);
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("[CloudMarket] Bedrock form failed, using chat prompt: "
                        + t.getMessage());
            }
        }
        plugin.prompts().beginNumberPrompt(player, label, min, max, onResult);
    }

    /** As above, but for a decimal price. */
    public void promptForPrice(Player player, String title, String label, Consumer<java.math.BigDecimal> onResult) {
        if (plugin.configs().useBedrockForms() && isBedrock(player)) {
            try {
                FloodgateHooks.sendPriceForm(plugin, player, title, label, onResult);
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("[CloudMarket] Bedrock form failed, using chat prompt: "
                        + t.getMessage());
            }
        }
        plugin.prompts().beginPricePrompt(player, label, onResult);
    }
}
