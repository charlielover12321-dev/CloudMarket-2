package com.cloudmarket.economy;

import com.cloudmarket.CloudMarket;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Exposes CloudMarket balances through Vault so other plugins - jobs, quests,
 * auction houses - can read and modify them.
 *
 * <p>Registration goes through {@link #tryRegister}, which checks for the Vault
 * plugin before this class is ever loaded. Vault is a soft dependency; naming its
 * types from a class that loads unconditionally would break servers without it.
 *
 * <p>Bank accounts are not implemented. Vault requires the methods to exist, so
 * they return a "not supported" response rather than pretending to succeed, which
 * would lose money for any plugin that trusted the result.
 */
public final class VaultHook extends AbstractEconomy {

    private final CloudMarket plugin;

    private VaultHook(CloudMarket plugin) {
        this.plugin = plugin;
    }

    public static VaultHook tryRegister(CloudMarket plugin) {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        try {
            VaultHook hook = new VaultHook(plugin);
            Bukkit.getServicesManager().register(net.milkbowl.vault.economy.Economy.class, hook,
                    plugin, ServicePriority.High);
            plugin.getLogger().info("[CloudMarket] Registered as the Vault economy provider.");
            return hook;
        } catch (Throwable t) {
            plugin.getLogger().warning("[CloudMarket] Could not register with Vault: " + t.getMessage());
            return null;
        }
    }

    public void unregister(CloudMarket plugin) {
        try {
            Bukkit.getServicesManager().unregister(net.milkbowl.vault.economy.Economy.class, this);
        } catch (Throwable ignored) {
            // Shutting down anyway.
        }
    }

    private Optional<UUID> resolve(String name) {
        return plugin.economy().resolve(name);
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "CloudMarket";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return plugin.configs().currencySymbol()
                + com.cloudmarket.util.Fmt.money(BigDecimal.valueOf(amount));
    }

    @Override
    public String currencyNamePlural() {
        return plugin.configs().currencyNamePlural();
    }

    @Override
    public String currencyNameSingular() {
        return plugin.configs().currencyNameSingular();
    }

    @Override
    public boolean hasAccount(String playerName) {
        return resolve(playerName).isPresent();
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return plugin.economy().isKnown(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        return resolve(playerName)
                .map(uuid -> plugin.economy().getBalance(uuid).doubleValue())
                .orElse(0.0d);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return plugin.economy().getBalance(player.getUniqueId()).doubleValue();
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        Optional<UUID> uuid = resolve(playerName);
        if (uuid.isEmpty()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Unknown player");
        }
        return withdraw(uuid.get(), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(player.getUniqueId(), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdraw(player.getUniqueId(), amount);
    }

    private EconomyResponse withdraw(UUID uuid, double amount) {
        BigDecimal value = BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP);
        if (value.signum() < 0) {
            return new EconomyResponse(0, plugin.economy().getBalance(uuid).doubleValue(),
                    EconomyResponse.ResponseType.FAILURE, "Negative amount");
        }
        if (!plugin.economy().withdraw(uuid, value)) {
            return new EconomyResponse(0, plugin.economy().getBalance(uuid).doubleValue(),
                    EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }
        return new EconomyResponse(amount, plugin.economy().getBalance(uuid).doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        Optional<UUID> uuid = resolve(playerName);
        if (uuid.isEmpty()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Unknown player");
        }
        return deposit(uuid.get(), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return deposit(player.getUniqueId(), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return deposit(player.getUniqueId(), amount);
    }

    private EconomyResponse deposit(UUID uuid, double amount) {
        BigDecimal value = BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP);
        plugin.economy().deposit(uuid, value);
        return new EconomyResponse(amount, plugin.economy().getBalance(uuid).doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return resolve(playerName).isPresent();
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        plugin.economy().touch(player.getUniqueId(), player.getName());
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // --------------------------------------------------------- banks: unsupported

    private EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                "CloudMarket does not provide bank accounts");
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
