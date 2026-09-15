package com.cloudmarket.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class Fmt {

    private static final DecimalFormat MONEY =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DecimalFormat COUNT =
            new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));

    private Fmt() {
    }

    /** Parse a '&'-prefixed legacy colour string into a component. */
    public static Component color(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand()
                .deserialize(legacy == null ? "" : legacy)
                // Vanilla italicises custom item names by default; this cancels it.
                .decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                        net.kyori.adventure.text.format.TextDecoration.State.FALSE);
    }

    public static String money(BigDecimal amount) {
        return MONEY.format(amount == null ? BigDecimal.ZERO : amount);
    }

    public static String money(double amount) {
        return MONEY.format(amount);
    }

    public static String count(long value) {
        return COUNT.format(value);
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** "SPRUCE_LOG" to "Spruce Log". */
    public static String pretty(Material material) {
        return pretty(material.name());
    }

    public static String pretty(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    /**
     * Parse a user-supplied amount. Rejects anything that is not a positive,
     * finite number with at most two decimal places, which keeps fractional-cent
     * rounding exploits out of /pay entirely.
     */
    public static BigDecimal parseAmount(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw.replace(",", ""));
            if (value.signum() <= 0 || value.scale() > 2) {
                return null;
            }
            return value.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
