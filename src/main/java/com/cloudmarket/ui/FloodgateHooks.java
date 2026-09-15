package com.cloudmarket.ui;

import com.cloudmarket.CloudMarket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The only class in CloudMarket that names a Floodgate type.
 *
 * <p>It is loaded lazily, and only after {@link BedrockBridge} has confirmed the
 * Floodgate plugin is enabled. Nothing here may be referenced from a class that
 * loads unconditionally.
 *
 * <p>Cumulus (the form library) ships inside the Floodgate jar, so it needs no
 * separate dependency and must not be shaded in - shading it would put a second
 * copy of the classes on the classpath.
 */
final class FloodgateHooks {

    private FloodgateHooks() {
    }

    static String readUsernamePrefix() {
        String prefix = FloodgateApi.getInstance().getPlayerPrefix();
        return prefix == null ? "" : prefix;
    }

    static boolean isFloodgatePlayer(UUID uuid) {
        return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
    }

    static void sendNumberForm(CloudMarket plugin, Player player, String title, String label,
                               int min, int max, Consumer<Integer> onResult) {
        CustomForm form = CustomForm.builder()
                .title(title)
                .label(label + "\n(" + min + " - " + max + ")")
                .input("Amount", String.valueOf(min), String.valueOf(min))
                .closedOrInvalidResultHandler(response -> {
                    // Player dismissed the form. Nothing to do.
                })
                .validResultHandler(response -> {
                    String raw = response.asInput(1);
                    int value;
                    try {
                        value = Integer.parseInt(raw == null ? "" : raw.trim());
                    } catch (NumberFormatException e) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                plugin.configs().messages().send(player, "input.not-a-number"));
                        return;
                    }
                    int clamped = Math.max(min, Math.min(max, value));
                    // Forms are handled off the main thread; hop back before touching
                    // inventories, balances or anything else in the Bukkit API.
                    Bukkit.getScheduler().runTask(plugin, () -> onResult.accept(clamped));
                })
                .build();
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }

    static void sendPriceForm(CloudMarket plugin, Player player, String title, String label,
                              Consumer<BigDecimal> onResult) {
        CustomForm form = CustomForm.builder()
                .title(title)
                .label(label)
                .input("Price", "0.00", "")
                .closedOrInvalidResultHandler(response -> {
                })
                .validResultHandler(response -> {
                    String raw = response.asInput(1);
                    BigDecimal value;
                    try {
                        value = new BigDecimal(raw == null ? "" : raw.trim())
                                .setScale(2, RoundingMode.HALF_UP);
                    } catch (NumberFormatException e) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                plugin.configs().messages().send(player, "input.not-a-number"));
                        return;
                    }
                    if (value.signum() <= 0) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                plugin.configs().messages().send(player, "input.not-a-number"));
                        return;
                    }
                    BigDecimal finalValue = value;
                    Bukkit.getScheduler().runTask(plugin, () -> onResult.accept(finalValue));
                })
                .build();
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }
}
