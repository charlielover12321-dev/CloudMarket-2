package com.cloudmarket;

import com.cloudmarket.commands.MarketAdminCommand;
import com.cloudmarket.commands.MarketCommands;
import com.cloudmarket.commands.MoneyCommands;
import com.cloudmarket.commands.ShopChestCommand;
import com.cloudmarket.config.ConfigManager;
import com.cloudmarket.economy.EconomyManager;
import com.cloudmarket.economy.VaultHook;
import com.cloudmarket.market.MarketManager;
import com.cloudmarket.shops.ShopChestListener;
import com.cloudmarket.shops.ShopChestManager;
import com.cloudmarket.storage.SqlStorage;
import com.cloudmarket.ui.BedrockBridge;
import com.cloudmarket.ui.GuiListener;
import com.cloudmarket.ui.GuiManager;
import com.cloudmarket.ui.PromptManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CloudMarket.
 *
 * <p>Two economies that share nothing but a currency: a server-wide cloud market
 * for raw materials with automatic stock-driven pricing, and independent
 * player-owned shop chests priced by hand.
 */
public final class CloudMarket extends JavaPlugin implements Listener {

    private ConfigManager configs;
    private SqlStorage storage;
    private EconomyManager economy;
    private MarketManager market;
    private ShopChestManager shopChests;
    private GuiManager gui;
    private BedrockBridge bedrock;
    private PromptManager prompts;
    private VaultHook vaultHook;

    private final AtomicReference<List<SqlStorage.BalanceRow>> leaderboardCache =
            new AtomicReference<>(List.of());
    private volatile boolean ready;

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        configs = new ConfigManager(this);
        configs.loadAll();

        storage = new SqlStorage(getLogger());
        try {
            if (configs.useMysql()) {
                storage.connectMysql(configs.mysqlHost(), configs.mysqlPort(), configs.mysqlDatabase(),
                        configs.mysqlUser(), configs.mysqlPassword(), configs.mysqlSsl(),
                        configs.mysqlPoolSize());
            } else {
                File file = new File(getDataFolder(), configs.sqliteFileName());
                storage.connectSqlite(file);
            }
            storage.createTables();
        } catch (Exception e) {
            getLogger().severe("[CloudMarket] Could not open the database. The plugin will not enable, "
                    + "because running without storage would silently lose every balance on restart. "
                    + "Cause: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = new EconomyManager(this);
        bedrock = new BedrockBridge(this);
        prompts = new PromptManager(this);
        market = new MarketManager(this);
        shopChests = new ShopChestManager(this);
        gui = new GuiManager(this);

        try {
            economy.loadFrom(storage.loadBalances());
            shopChests.load(storage.loadChests(), storage.loadListings());
        } catch (Exception e) {
            getLogger().warning("[CloudMarket] Could not load saved data: " + e.getMessage());
        }

        bedrock.initialise();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(prompts, this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new ShopChestListener(this), this);

        // One instance each. MoneyCommands in particular holds the pending-/pay
        // confirmation map, so separate instances per alias would mean a
        // confirmation registered by /pay could never be found by /pay confirm.
        MarketCommands marketCommands = new MarketCommands(this);
        MoneyCommands moneyCommands = new MoneyCommands(this);
        register("shop", marketCommands);
        register("buy", marketCommands);
        register("sell", marketCommands);
        register("pay", moneyCommands);
        register("balance", moneyCommands);
        register("leaderboard", moneyCommands);
        register("shopchest", new ShopChestCommand(this));
        register("marketadmin", new MarketAdminCommand(this));

        // The Vault check has to happen HERE, not inside VaultHook.
        //
        // Calling any method on VaultHook forces the JVM to load that class, and
        // loading it means resolving its superclass, net.milkbowl.vault.economy
        // .AbstractEconomy. On a server without Vault that class does not exist, so
        // the JVM throws NoClassDefFoundError before a single line of our code runs
        // - a guard inside VaultHook is already too late. This class never names a
        // Vault type, so the check below is safe.
        if (configs.hookVault() && getServer().getPluginManager().getPlugin("Vault") != null) {
            try {
                vaultHook = VaultHook.tryRegister(this);
            } catch (Throwable t) {
                // An incompatible Vault build should cost us the bridge, not the
                // whole plugin.
                getLogger().warning("[CloudMarket] Vault is installed but the economy bridge could "
                        + "not be registered (" + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + "). CloudMarket will run normally; other plugins just will not see "
                        + "balances through Vault.");
            }
        } else if (configs.hookVault()) {
            getLogger().info("[CloudMarket] Vault not installed, so no economy bridge was registered. "
                    + "CloudMarket works fine without it - install Vault only if another plugin needs "
                    + "to read or change balances.");
        }

        scheduleTasks();
        getLogger().info("[CloudMarket] Enabled. Waiting for the server to finish loading before "
                + "scanning recipes.");
    }

    @Override
    public void onDisable() {
        ready = false;
        if (prompts != null) {
            prompts.shutdown();
        }
        if (vaultHook != null) {
            vaultHook.unregister(this);
        }
        // Flush synchronously. The server is going down, so there is no later.
        if (storage != null && economy != null) {
            try {
                storage.saveBalances(economy.snapshotAll());
                if (market != null) {
                    storage.saveMarketItems(market.snapshot());
                }
                getLogger().info("[CloudMarket] Saved balances and market state.");
            } catch (Exception e) {
                getLogger().severe("[CloudMarket] Failed to save on shutdown: " + e.getMessage());
            }
            storage.close();
        }
    }

    private void register(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("[CloudMarket] Command '" + name + "' is missing from plugin.yml.");
            return;
        }
        command.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }

    /**
     * Recipes are registered from datapacks during world load, which finishes after
     * plugins enable. Scanning in onEnable would see an empty or partial recipe
     * registry and bar nothing - so the scan waits for the server-load event, with a
     * delayed task as a backstop in case that event has already fired on a reload.
     */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (!ready) {
            reloadMarket();
            ready = true;
        }
    }

    private void scheduleTasks() {
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!ready) {
                getLogger().info("[CloudMarket] Server-load event not seen; scanning recipes now.");
                reloadMarket();
                ready = true;
            }
        }, 100L);

        long autosave = Math.max(60L, configs.autosaveSeconds()) * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::flush, autosave, autosave);

        long refresh = Math.max(15L, configs.leaderboardRefreshSeconds()) * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::refreshLeaderboard, 100L, refresh);
    }

    /** Rebuild the market from config, recipes and saved stock. */
    public void reloadMarket() {
        List<SqlStorage.MarketRow> rows = new ArrayList<>();
        try {
            rows = storage.loadMarketItems();
        } catch (Exception e) {
            getLogger().warning("[CloudMarket] Could not read saved market state: " + e.getMessage());
        }
        market.rebuild(rows);
    }

    private void flush() {
        try {
            storage.saveBalances(economy.drainDirty());
            if (ready) {
                storage.saveMarketItems(market.snapshot());
            }
        } catch (Exception e) {
            getLogger().warning("[CloudMarket] Autosave failed: " + e.getMessage());
        }
    }

    private void refreshLeaderboard() {
        try {
            // Flush first so the leaderboard reflects money earned since the last
            // save rather than lagging a full autosave interval behind.
            storage.saveBalances(economy.drainDirty());
            leaderboardCache.set(storage.topBalances(configs.leaderboardSize()));
        } catch (Exception e) {
            getLogger().fine("[CloudMarket] Leaderboard refresh failed: " + e.getMessage());
        }
    }

    public List<SqlStorage.BalanceRow> leaderboard() {
        return leaderboardCache.get();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        economy.touch(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    /** Write an audit-log row without blocking whatever called us. */
    public void logTransaction(UUID player, String type, String material, int quantity,
                               BigDecimal total, BigDecimal tax, long stockAfter, String counterparty) {
        async(() -> {
            try {
                storage.logTransaction(player, type, material, quantity, total, tax,
                        stockAfter, counterparty);
            } catch (Exception e) {
                getLogger().fine("[CloudMarket] Could not write transaction log: " + e.getMessage());
            }
        });
    }

    public void async(Runnable task) {
        if (isEnabled()) {
            getServer().getScheduler().runTaskAsynchronously(this, task);
        }
    }

    // --------------------------------------------------------------- accessors

    public ConfigManager configs() {
        return configs;
    }

    public SqlStorage storage() {
        return storage;
    }

    public EconomyManager economy() {
        return economy;
    }

    public MarketManager market() {
        return market;
    }

    public ShopChestManager shopChests() {
        return shopChests;
    }

    public GuiManager gui() {
        return gui;
    }

    public BedrockBridge bedrock() {
        return bedrock;
    }

    public PromptManager prompts() {
        return prompts;
    }
}
