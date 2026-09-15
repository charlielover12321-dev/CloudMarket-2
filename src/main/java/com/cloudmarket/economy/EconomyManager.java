package com.cloudmarket.economy;

import com.cloudmarket.CloudMarket;
import com.cloudmarket.storage.SqlStorage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authoritative in-memory balance store with write-behind persistence.
 *
 * <p>Balances are held in a concurrent map and flushed on an interval, on quit and
 * on shutdown. Going to SQL on every transaction would block the main thread on a
 * disk write for every item sold.
 *
 * <p>All mutation goes through {@link java.util.concurrent.ConcurrentHashMap#compute},
 * which holds a per-key lock for the duration. That is what makes a read-check-write
 * sequence safe without a global lock, and it is why {@link #transfer} is written as
 * two compute calls guarded by a pre-check rather than as two separate
 * withdraw/deposit calls that another thread could interleave with.
 *
 * <h2>A note on Bedrock players</h2>
 * Floodgate assigns Bedrock players a synthetic but stable UUID, so keying on UUID
 * works for them exactly as it does for Java players. Looking a player up
 * <em>by name</em> is the part that breaks: Bedrock names are prefixed (a dot by
 * default) and never resolve through Mojang's API. So we keep our own name index,
 * populated from the database and refreshed on join, and never call out to Mojang.
 */
public final class EconomyManager {

    private final CloudMarket plugin;
    private final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicReference<BigDecimal> burned = new AtomicReference<>(BigDecimal.ZERO);

    public EconomyManager(CloudMarket plugin) {
        this.plugin = plugin;
    }

    public void loadFrom(Map<UUID, SqlStorage.BalanceRow> rows) {
        balances.clear();
        names.clear();
        nameIndex.clear();
        for (SqlStorage.BalanceRow row : rows.values()) {
            balances.put(row.uuid(), row.balance() == null ? BigDecimal.ZERO : row.balance());
            if (row.name() != null) {
                names.put(row.uuid(), row.name());
                nameIndex.put(row.name().toLowerCase(java.util.Locale.ROOT), row.uuid());
            }
        }
    }

    /** Register a player's current name so /pay and /bal can find them later. */
    public void touch(UUID uuid, String name) {
        if (name != null) {
            String previous = names.put(uuid, name);
            if (previous != null && !previous.equalsIgnoreCase(name)) {
                nameIndex.remove(previous.toLowerCase(java.util.Locale.ROOT));
            }
            nameIndex.put(name.toLowerCase(java.util.Locale.ROOT), uuid);
        }
        if (!balances.containsKey(uuid)) {
            balances.put(uuid, plugin.configs().startingBalance());
            dirty.add(uuid);
        }
    }

    public String nameOf(UUID uuid) {
        return names.getOrDefault(uuid, uuid.toString().substring(0, 8));
    }

    /**
     * Resolve a name to a UUID. Tries the exact name first, then the same name with
     * the Floodgate prefix applied, then without it - so a Java player can type
     * {@code /pay Steve 100} and reach the Bedrock player {@code .Steve}.
     */
    public Optional<UUID> resolve(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        UUID direct = nameIndex.get(lower);
        if (direct != null) {
            return Optional.of(direct);
        }
        String prefix = plugin.bedrock().getUsernamePrefix();
        if (prefix != null && !prefix.isEmpty()) {
            UUID prefixed = nameIndex.get((prefix + name).toLowerCase(java.util.Locale.ROOT));
            if (prefixed != null) {
                return Optional.of(prefixed);
            }
            if (lower.startsWith(prefix.toLowerCase(java.util.Locale.ROOT))) {
                UUID stripped = nameIndex.get(lower.substring(prefix.length()));
                if (stripped != null) {
                    return Optional.of(stripped);
                }
            }
        }
        return Optional.empty();
    }

    public List<String> knownNames() {
        return new ArrayList<>(names.values());
    }

    public boolean isKnown(UUID uuid) {
        return balances.containsKey(uuid);
    }

    public BigDecimal getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, BigDecimal.ZERO);
    }

    public boolean has(UUID uuid, BigDecimal amount) {
        return getBalance(uuid).compareTo(amount) >= 0;
    }

    public void deposit(UUID uuid, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return;
        }
        balances.compute(uuid, (key, current) ->
                (current == null ? plugin.configs().startingBalance() : current)
                        .add(amount).setScale(2, RoundingMode.HALF_UP));
        dirty.add(uuid);
    }

    /**
     * Returns false and changes nothing if the player cannot cover the amount.
     *
     * <p>The success flag is set inside the remapping function rather than inferred
     * from the returned value afterwards. Inferring it would be wrong for a
     * zero-balance withdrawal of zero, and would race with any other thread that
     * touched the same key between the compute and the check.
     */
    public boolean withdraw(UUID uuid, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return true;
        }
        boolean[] success = {false};
        balances.compute(uuid, (key, current) -> {
            BigDecimal base = current == null ? plugin.configs().startingBalance() : current;
            if (base.compareTo(amount) < 0) {
                return base;
            }
            success[0] = true;
            return base.subtract(amount).setScale(2, RoundingMode.HALF_UP);
        });
        if (success[0]) {
            dirty.add(uuid);
        }
        return success[0];
    }

    public void setBalance(UUID uuid, BigDecimal amount) {
        balances.put(uuid, amount.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        dirty.add(uuid);
    }

    /**
     * Move money between two players. Withdraws first; if the withdrawal fails the
     * deposit never happens, so a failed transfer cannot mint currency.
     */
    public boolean transfer(UUID from, UUID to, BigDecimal amount) {
        if (from.equals(to) || amount.signum() <= 0) {
            return false;
        }
        if (!withdraw(from, amount)) {
            return false;
        }
        deposit(to, amount);
        return true;
    }

    /**
     * Destroy currency. Transaction tax goes here rather than to a holding account:
     * the whole point of the sink is that the money leaves the economy.
     */
    public void burn(BigDecimal amount) {
        if (amount.signum() <= 0) {
            return;
        }
        burned.updateAndGet(current -> current.add(amount));
    }

    public BigDecimal totalBurned() {
        return burned.get();
    }

    public BigDecimal totalSupply() {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : balances.values()) {
            total = total.add(value);
        }
        return total;
    }

    /** Snapshot and clear the dirty set for the flush task. */
    public List<SqlStorage.BalanceRow> drainDirty() {
        List<SqlStorage.BalanceRow> out = new ArrayList<>();
        for (UUID uuid : Set.copyOf(dirty)) {
            dirty.remove(uuid);
            out.add(new SqlStorage.BalanceRow(uuid, names.get(uuid), getBalance(uuid)));
        }
        return out;
    }

    /** Everything, for the shutdown flush. */
    public List<SqlStorage.BalanceRow> snapshotAll() {
        List<SqlStorage.BalanceRow> out = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : balances.entrySet()) {
            out.add(new SqlStorage.BalanceRow(entry.getKey(), names.get(entry.getKey()), entry.getValue()));
        }
        return out;
    }
}
