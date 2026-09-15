package com.cloudmarket.config;

import com.cloudmarket.CloudMarket;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Owns the five config files and exposes them as typed settings. Every getter
 * re-reads from the in-memory {@link FileConfiguration}, so /marketadmin reload
 * genuinely takes effect without a restart.
 */
public final class ConfigManager {

    private final CloudMarket plugin;
    private final Messages messages = new Messages();

    private FileConfiguration economy;
    private FileConfiguration marketItems;
    private FileConfiguration overrides;
    private FileConfiguration storage;

    private File economyFile;
    private File marketItemsFile;
    private File overridesFile;
    private File storageFile;
    private File messagesFile;

    public ConfigManager(CloudMarket plugin) {
        this.plugin = plugin;
    }

    public Messages messages() {
        return messages;
    }

    public void loadAll() {
        economyFile = prepare("economy.yml");
        marketItemsFile = prepare("market-items.yml");
        overridesFile = prepare("craftable-overrides.yml");
        storageFile = prepare("storage.yml");
        messagesFile = prepare("messages.yml");

        economy = YamlConfiguration.loadConfiguration(economyFile);
        marketItems = YamlConfiguration.loadConfiguration(marketItemsFile);
        overrides = YamlConfiguration.loadConfiguration(overridesFile);
        storage = YamlConfiguration.loadConfiguration(storageFile);
        messages.load(YamlConfiguration.loadConfiguration(messagesFile));
    }

    private File prepare(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        return file;
    }

    public FileConfiguration marketItems() {
        return marketItems;
    }

    public File marketItemsFile() {
        return marketItemsFile;
    }

    public FileConfiguration overrides() {
        return overrides;
    }

    public File overridesFile() {
        return overridesFile;
    }

    public void saveMarketItems() {
        try {
            marketItems.save(marketItemsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save market-items.yml: " + e.getMessage());
        }
    }

    public void saveOverrides() {
        try {
            overrides.save(overridesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save craftable-overrides.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ economy

    public String currencySymbol() {
        return economy.getString("currency.symbol", "$");
    }

    public String currencyNameSingular() {
        return economy.getString("currency.name-singular", "Coin");
    }

    public String currencyNamePlural() {
        return economy.getString("currency.name-plural", "Coins");
    }

    public BigDecimal startingBalance() {
        return BigDecimal.valueOf(economy.getDouble("starting-balance", 250.0d))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public double taxRate() {
        double percent = economy.getDouble("tax-percent", 4.0d);
        return Math.max(0.0d, Math.min(percent, 90.0d)) / 100.0d;
    }

    public BigDecimal payMinimum() {
        return BigDecimal.valueOf(economy.getDouble("pay.minimum", 0.01d))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public BigDecimal payConfirmThreshold() {
        return BigDecimal.valueOf(economy.getDouble("pay.confirm-above", 10000.0d))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public int payConfirmSeconds() {
        return economy.getInt("pay.confirm-timeout-seconds", 30);
    }

    public int sellCapPerHour() {
        return economy.getInt("limits.sell-per-item-per-hour", 2304);
    }

    public int leaderboardRefreshSeconds() {
        return economy.getInt("leaderboard.refresh-seconds", 60);
    }

    public int leaderboardSize() {
        return economy.getInt("leaderboard.size", 10);
    }

    public int autosaveSeconds() {
        return economy.getInt("autosave-seconds", 300);
    }

    public boolean useBedrockForms() {
        return economy.getBoolean("bedrock.use-native-forms", true);
    }

    public boolean hookVault() {
        return economy.getBoolean("vault.register-as-provider", true);
    }

    // ------------------------------------------------------------------ storage

    public boolean useMysql() {
        return "mysql".equalsIgnoreCase(storage.getString("type", "sqlite"));
    }

    public String mysqlHost() {
        return storage.getString("mysql.host", "localhost");
    }

    public int mysqlPort() {
        return storage.getInt("mysql.port", 3306);
    }

    public String mysqlDatabase() {
        return storage.getString("mysql.database", "cloudmarket");
    }

    public String mysqlUser() {
        return storage.getString("mysql.username", "root");
    }

    public String mysqlPassword() {
        return storage.getString("mysql.password", "");
    }

    public boolean mysqlSsl() {
        return storage.getBoolean("mysql.use-ssl", false);
    }

    public int mysqlPoolSize() {
        return storage.getInt("mysql.pool-size", 6);
    }

    public String sqliteFileName() {
        return storage.getString("sqlite.file", "cloudmarket.db");
    }

    // ---------------------------------------------------------------- overrides

    /** Materials an admin has deliberately opted back into the global market. */
    public Set<Material> whitelistedCraftables() {
        Set<Material> out = new LinkedHashSet<>();
        List<String> names = overrides.getStringList("whitelist");
        for (String name : names) {
            Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (material == null) {
                plugin.getLogger().warning("craftable-overrides.yml lists an unknown material: " + name);
                continue;
            }
            out.add(material);
        }
        return out;
    }

    public void addWhitelist(Material material) {
        List<String> names = overrides.getStringList("whitelist");
        String key = material.name();
        if (!names.contains(key)) {
            names.add(key);
            overrides.set("whitelist", names);
            saveOverrides();
        }
    }
}
