package com.cloudmarket.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Serialises an {@link ItemStack} to a Base64 string and back.
 *
 * <p>Everywhere else in CloudMarket an item is just a {@code Material} and a count,
 * which is why the cloud market refuses anything enchanted, renamed or
 * data-bearing. The black market cannot work that way: the whole point is trading a
 * Mending pickaxe or a spawner, and those carry NBT that a material name throws
 * away.
 *
 * <p>Bukkit's own object streams are used rather than hand-rolled NBT handling,
 * because they track the server's internal item format. A caveat comes with that:
 * the encoding is tied to the game version that produced it. Items listed on 26.2
 * deserialise fine on 26.2, but a future update that changes item format could in
 * principle leave old listings unreadable. Decoding failures are handled as a
 * skipped listing rather than an exception, so one bad row never takes the market
 * down, and the audit log keeps a record of what was there.
 */
public final class ItemCodec {

    private ItemCodec() {
    }

    /** Encode a stack. Amount is preserved, but callers normally store templates of 1. */
    public static String encode(ItemStack stack) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(stack);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    /** Decode a stack, or null if the data is unreadable. */
    public static ItemStack decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try (ByteArrayInputStream bytes =
                     new ByteArrayInputStream(Base64.getDecoder().decode(encoded));
             BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
            Object read = in.readObject();
            return read instanceof ItemStack stack ? stack : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A human-readable label for a stack: its custom name if it has one, otherwise
     * the prettified material name. Stored alongside the encoded data so listings
     * can still be identified in logs and admin tools even if decoding ever fails.
     */
    public static String describe(ItemStack stack) {
        if (stack == null) {
            return "Unknown";
        }
        try {
            if (stack.hasItemMeta() && stack.getItemMeta() != null
                    && stack.getItemMeta().hasDisplayName()) {
                return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(stack.getItemMeta().displayName());
            }
        } catch (Throwable ignored) {
            // Fall through to the material name.
        }
        return Fmt.pretty(stack.getType());
    }
}
