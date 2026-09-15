package com.cloudmarket.config;

import com.cloudmarket.util.Fmt;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;

/**
 * All player-facing strings, loaded from messages.yml. Placeholders are plain
 * {name} tokens so admins editing the file do not need to learn anything.
 */
public final class Messages {

    private FileConfiguration config;
    private String prefix = "";

    public void load(FileConfiguration config) {
        this.config = config;
        this.prefix = config.getString("prefix", "&8[&bCloudMarket&8] &r");
    }

    public String raw(String key) {
        String value = config.getString(key);
        return value == null ? "&c[missing message: " + key + "]" : value;
    }

    public Component get(String key, Map<String, String> placeholders) {
        String text = raw(key);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return Fmt.color(prefix + text);
    }

    public Component get(String key) {
        return get(key, null);
    }

    /** Same as {@link #get} but without the plugin prefix, for GUI lore lines. */
    public Component bare(String key, Map<String, String> placeholders) {
        String text = raw(key);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return Fmt.color(text);
    }

    public void send(CommandSender target, String key) {
        target.sendMessage(get(key));
    }

    public void send(CommandSender target, String key, Map<String, String> placeholders) {
        target.sendMessage(get(key, placeholders));
    }
}
