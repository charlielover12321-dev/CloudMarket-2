package com.cloudmarket.util;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serialises an {@link ItemStack} to a Base64 string and back, and describes what
 * it actually is.
 *
 * <h2>Why NBT rather than Bukkit's object stream</h2>
 * The obvious approach - {@code BukkitObjectOutputStream} - goes through the legacy
 * {@code ConfigurationSerializable} path, and since the data-components rework that
 * path silently loses data. Stored enchantments on books are the textbook casualty:
 * the book round-trips, the enchantment does not, and you get back a blank Enchanted
 * Book with no error logged anywhere. Paper's own javadoc calls the old route
 * "bukkits dangerous serialization system".
 *
 * <p>{@link ItemStack#serializeAsBytes()} writes real NBT with a DataVersion on the
 * root, so {@link ItemStack#deserializeBytes(byte[])} can run the game's built-in
 * data converter on load. That fixes the enchantment loss, and also means items
 * listed today survive a future Minecraft update rather than becoming unreadable.
 *
 * <h2>Rows written by the old codec</h2>
 * Anything stored by the previous version is still in the database in the legacy
 * format. {@link #decode} tries NBT first and falls back to the object stream, so
 * those listings still open - but whatever that format already discarded is gone,
 * and re-listing is the only way to get it back.
 */
public final class ItemCodec {

    private ItemCodec() {
    }

    public static String encode(ItemStack stack) {
        try {
            return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
        } catch (Throwable t) {
            return null;
        }
    }

    public static ItemStack decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(raw);
        } catch (Throwable nbtFailed) {
            // Written by the old lossy codec. Still readable, but already missing
            // whatever that format dropped on the way in.
            try (ByteArrayInputStream bytes = new ByteArrayInputStream(raw);
                 BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
                Object read = in.readObject();
                return read instanceof ItemStack stack ? stack : null;
            } catch (Exception legacyFailed) {
                return null;
            }
        }
    }

    /**
     * Enchantments on a stack, book or otherwise.
     *
     * <p>Books keep theirs in {@link EnchantmentStorageMeta} rather than in the
     * normal enchantment map, which is why a book with Mending reports no ordinary
     * enchantments at all and has to be handled separately.
     */
    public static Map<String, Integer> enchantmentsOf(ItemStack stack) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (stack == null || !stack.hasItemMeta()) {
            return out;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return out;
        }
        try {
            if (meta instanceof EnchantmentStorageMeta storage) {
                for (Map.Entry<Enchantment, Integer> entry : storage.getStoredEnchants().entrySet()) {
                    out.put(nameOf(entry.getKey()), entry.getValue());
                }
            }
            for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                out.put(nameOf(entry.getKey()), entry.getValue());
            }
        } catch (Throwable ignored) {
            // A display concern must never break a transaction.
        }
        return out;
    }

    private static String nameOf(Enchantment enchantment) {
        try {
            return Fmt.pretty(enchantment.getKey().getKey().toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return "Unknown";
        }
    }

    /** "Sharpness V", or just "Mending" where the enchantment has a single level. */
    public static List<String> enchantmentLines(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : enchantmentsOf(stack).entrySet()) {
            int level = entry.getValue();
            lines.add(level <= 1 ? entry.getKey() : entry.getKey() + " " + roman(level));
        }
        return lines;
    }

    private static String roman(int value) {
        if (value < 1 || value > 10) {
            return String.valueOf(value);
        }
        return new String[]{"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"}[value - 1];
    }

    /**
     * A readable label for a stack. Custom name if it has one, otherwise the
     * material - and for anything enchanted, the enchantments appended, because
     * "Enchanted Book" tells a buyer nothing about what they are buying.
     */
    public static String describe(ItemStack stack) {
        if (stack == null) {
            return "Unknown";
        }
        String base = null;
        try {
            if (stack.hasItemMeta() && stack.getItemMeta() != null
                    && stack.getItemMeta().hasDisplayName()) {
                base = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(stack.getItemMeta().displayName());
            }
        } catch (Throwable ignored) {
            // Fall through to the material name.
        }
        if (base == null || base.isEmpty()) {
            base = Fmt.pretty(stack.getType());
        }

        List<String> enchants = enchantmentLines(stack);
        if (enchants.isEmpty()) {
            return base;
        }
        return base + " (" + String.join(", ", enchants) + ")";
    }
}
