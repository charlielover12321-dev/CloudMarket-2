package com.cloudmarket.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistence for balances, market state, shop chests and the audit log.
 *
 * <p>SQLite is the default because it needs no setup on a shared host. MySQL is
 * supported for anyone running a network where several servers share one economy.
 * The only dialect differences that matter here are the auto-increment keyword and
 * the upsert syntax, so both are handled inline rather than through an abstraction.
 *
 * <p>Nothing in this class should ever be called from the main server thread.
 */
public final class SqlStorage {

    public record BalanceRow(UUID uuid, String name, BigDecimal balance) {
    }

    public record MarketRow(String material, String category, double basePrice, double floorPrice,
                            double ceilingPrice, long equilibriumStock, long currentStock, boolean enabled) {
    }

    public record ChestRow(int id, String world, int x, int y, int z, UUID owner) {
    }

    public record ListingRow(int chestId, String material, BigDecimal price) {
    }

    public record ListingData(int id, UUID seller, String itemData, String displayName,
                              int quantity, BigDecimal price, long listedAt, long expiresAt) {
    }

    private final Logger logger;
    private HikariDataSource dataSource;
    private boolean mysql;

    public SqlStorage(Logger logger) {
        this.logger = logger;
    }

    public boolean isMysql() {
        return mysql;
    }

    public void connectSqlite(File file) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("CloudMarket-SQLite");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        // SQLite tolerates exactly one writer. More connections buys nothing but
        // SQLITE_BUSY errors under load.
        config.setMaximumPoolSize(1);
        config.addDataSourceProperty("journal_mode", "WAL");
        this.dataSource = new HikariDataSource(config);
        this.mysql = false;
    }

    public void connectMysql(String host, int port, String database, String user, String password,
                             boolean useSsl, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("CloudMarket-MySQL");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl + "&characterEncoding=utf8&useUnicode=true");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, poolSize));
        this.dataSource = new HikariDataSource(config);
        this.mysql = true;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public void createTables() throws SQLException {
        String autoIncrement = mysql ? "INT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String timestampType = mysql ? "DATETIME" : "TEXT";

        List<String> statements = List.of(
                """
                CREATE TABLE IF NOT EXISTS player_balances (
                  uuid VARCHAR(36) PRIMARY KEY,
                  last_name VARCHAR(48),
                  balance DECIMAL(18,2) NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS market_items (
                  material VARCHAR(96) PRIMARY KEY,
                  category VARCHAR(32),
                  base_price DECIMAL(12,4) NOT NULL,
                  floor_price DECIMAL(12,4) NOT NULL,
                  ceiling_price DECIMAL(12,4) NOT NULL,
                  equilibrium_stock BIGINT NOT NULL,
                  current_stock BIGINT NOT NULL DEFAULT 0,
                  enabled BOOLEAN NOT NULL DEFAULT TRUE
                )
                """,
                "CREATE TABLE IF NOT EXISTS transactions (\n"
                        + "  id " + autoIncrement + ",\n"
                        + "  player_uuid VARCHAR(36),\n"
                        + "  type VARCHAR(24),\n"
                        + "  material VARCHAR(96),\n"
                        + "  quantity INT,\n"
                        + "  total_amount DECIMAL(18,2),\n"
                        + "  tax_amount DECIMAL(18,2),\n"
                        + "  stock_after BIGINT,\n"
                        + "  counterparty VARCHAR(36),\n"
                        + "  timestamp " + timestampType + "\n"
                        + ")",
                "CREATE TABLE IF NOT EXISTS shop_chests (\n"
                        + "  id " + autoIncrement + ",\n"
                        + "  world VARCHAR(64) NOT NULL,\n"
                        + "  x INT NOT NULL,\n"
                        + "  y INT NOT NULL,\n"
                        + "  z INT NOT NULL,\n"
                        + "  owner_uuid VARCHAR(36) NOT NULL\n"
                        + ")",
                """
                CREATE TABLE IF NOT EXISTS shop_chest_listings (
                  chest_id INT NOT NULL,
                  material VARCHAR(96) NOT NULL,
                  price DECIMAL(12,2) NOT NULL,
                  PRIMARY KEY (chest_id, material)
                )
                """,
                "CREATE TABLE IF NOT EXISTS black_market_listings (\n"
                        + "  id " + autoIncrement + ",\n"
                        + "  seller_uuid VARCHAR(36) NOT NULL,\n"
                        + "  item_data TEXT NOT NULL,\n"
                        + "  display_name VARCHAR(160),\n"
                        + "  quantity INT NOT NULL,\n"
                        + "  price DECIMAL(18,2) NOT NULL,\n"
                        + "  listed_at BIGINT NOT NULL,\n"
                        + "  expires_at BIGINT NOT NULL\n"
                        + ")",
                "CREATE INDEX IF NOT EXISTS idx_bm_seller ON black_market_listings(seller_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_tx_player ON transactions(player_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_tx_time ON transactions(timestamp)",
                "CREATE INDEX IF NOT EXISTS idx_chest_loc ON shop_chests(world, x, y, z)"
        );

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                try {
                    statement.execute(sql);
                } catch (SQLException e) {
                    // MySQL rejects CREATE INDEX IF NOT EXISTS; harmless to skip.
                    if (!sql.startsWith("CREATE INDEX")) {
                        throw e;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- balances

    public Map<UUID, BalanceRow> loadBalances() throws SQLException {
        Map<UUID, BalanceRow> out = new LinkedHashMap<>();
        String sql = "SELECT uuid, last_name, balance FROM player_balances";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    UUID uuid = UUID.fromString(results.getString("uuid"));
                    out.put(uuid, new BalanceRow(uuid, results.getString("last_name"),
                            results.getBigDecimal("balance")));
                } catch (IllegalArgumentException ignored) {
                    // Corrupt row; skip rather than abort the load.
                }
            }
        }
        return out;
    }

    public void saveBalances(Collection<BalanceRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        String sql = mysql
                ? "INSERT INTO player_balances (uuid, last_name, balance) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE last_name=VALUES(last_name), balance=VALUES(balance)"
                : "INSERT INTO player_balances (uuid, last_name, balance) VALUES (?,?,?) "
                + "ON CONFLICT(uuid) DO UPDATE SET last_name=excluded.last_name, balance=excluded.balance";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (BalanceRow row : rows) {
                statement.setString(1, row.uuid().toString());
                statement.setString(2, row.name());
                statement.setBigDecimal(3, row.balance());
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);
        }
    }

    public List<BalanceRow> topBalances(int limit) throws SQLException {
        List<BalanceRow> out = new ArrayList<>();
        String sql = "SELECT uuid, last_name, balance FROM player_balances ORDER BY balance DESC LIMIT ?";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    try {
                        out.add(new BalanceRow(UUID.fromString(results.getString("uuid")),
                                results.getString("last_name"), results.getBigDecimal("balance")));
                    } catch (IllegalArgumentException ignored) {
                        // skip
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ market items

    public List<MarketRow> loadMarketItems() throws SQLException {
        List<MarketRow> out = new ArrayList<>();
        String sql = "SELECT * FROM market_items";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                out.add(new MarketRow(
                        results.getString("material"),
                        results.getString("category"),
                        results.getDouble("base_price"),
                        results.getDouble("floor_price"),
                        results.getDouble("ceiling_price"),
                        results.getLong("equilibrium_stock"),
                        results.getLong("current_stock"),
                        results.getBoolean("enabled")));
            }
        }
        return out;
    }

    public void saveMarketItems(Collection<MarketRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        String sql = mysql
                ? "INSERT INTO market_items (material, category, base_price, floor_price, ceiling_price, "
                + "equilibrium_stock, current_stock, enabled) VALUES (?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE category=VALUES(category), base_price=VALUES(base_price), "
                + "floor_price=VALUES(floor_price), ceiling_price=VALUES(ceiling_price), "
                + "equilibrium_stock=VALUES(equilibrium_stock), current_stock=VALUES(current_stock), "
                + "enabled=VALUES(enabled)"
                : "INSERT INTO market_items (material, category, base_price, floor_price, ceiling_price, "
                + "equilibrium_stock, current_stock, enabled) VALUES (?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(material) DO UPDATE SET category=excluded.category, "
                + "base_price=excluded.base_price, floor_price=excluded.floor_price, "
                + "ceiling_price=excluded.ceiling_price, equilibrium_stock=excluded.equilibrium_stock, "
                + "current_stock=excluded.current_stock, enabled=excluded.enabled";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (MarketRow row : rows) {
                statement.setString(1, row.material());
                statement.setString(2, row.category());
                statement.setDouble(3, row.basePrice());
                statement.setDouble(4, row.floorPrice());
                statement.setDouble(5, row.ceilingPrice());
                statement.setLong(6, row.equilibriumStock());
                statement.setLong(7, row.currentStock());
                statement.setBoolean(8, row.enabled());
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);
        }
    }

    // ------------------------------------------------------------ transactions

    public void logTransaction(UUID player, String type, String material, int quantity,
                               BigDecimal total, BigDecimal tax, long stockAfter, String counterparty)
            throws SQLException {
        String sql = "INSERT INTO transactions (player_uuid, type, material, quantity, total_amount, "
                + "tax_amount, stock_after, counterparty, timestamp) VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player == null ? null : player.toString());
            statement.setString(2, type);
            statement.setString(3, material);
            statement.setInt(4, quantity);
            statement.setBigDecimal(5, total);
            statement.setBigDecimal(6, tax);
            statement.setLong(7, stockAfter);
            statement.setString(8, counterparty);
            statement.setTimestamp(9, new java.sql.Timestamp(System.currentTimeMillis()));
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------------- shop chests

    public List<ChestRow> loadChests() throws SQLException {
        List<ChestRow> out = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM shop_chests");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    out.add(new ChestRow(results.getInt("id"), results.getString("world"),
                            results.getInt("x"), results.getInt("y"), results.getInt("z"),
                            UUID.fromString(results.getString("owner_uuid"))));
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        return out;
    }

    public List<ListingRow> loadListings() throws SQLException {
        List<ListingRow> out = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM shop_chest_listings");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                out.add(new ListingRow(results.getInt("chest_id"), results.getString("material"),
                        results.getBigDecimal("price")));
            }
        }
        return out;
    }

    public int insertChest(String world, int x, int y, int z, UUID owner) throws SQLException {
        String sql = "INSERT INTO shop_chests (world, x, y, z, owner_uuid) VALUES (?,?,?,?,?)";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, world);
            statement.setInt(2, x);
            statement.setInt(3, y);
            statement.setInt(4, z);
            statement.setString(5, owner.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        return -1;
    }

    public void deleteChest(int chestId) throws SQLException {
        try (Connection connection = connection()) {
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM shop_chest_listings WHERE chest_id=?")) {
                statement.setInt(1, chestId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM shop_chests WHERE id=?")) {
                statement.setInt(1, chestId);
                statement.executeUpdate();
            }
        }
    }

    public void saveListing(int chestId, String material, BigDecimal price) throws SQLException {
        String sql = mysql
                ? "INSERT INTO shop_chest_listings (chest_id, material, price) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE price=VALUES(price)"
                : "INSERT INTO shop_chest_listings (chest_id, material, price) VALUES (?,?,?) "
                + "ON CONFLICT(chest_id, material) DO UPDATE SET price=excluded.price";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, chestId);
            statement.setString(2, material);
            statement.setBigDecimal(3, price);
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------- black market

    public List<ListingData> loadBlackMarket() throws SQLException {
        List<ListingData> out = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement =
                     connection.prepareStatement("SELECT * FROM black_market_listings");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    out.add(new ListingData(
                            results.getInt("id"),
                            UUID.fromString(results.getString("seller_uuid")),
                            results.getString("item_data"),
                            results.getString("display_name"),
                            results.getInt("quantity"),
                            results.getBigDecimal("price"),
                            results.getLong("listed_at"),
                            results.getLong("expires_at")));
                } catch (IllegalArgumentException ignored) {
                    // Corrupt row; skip it rather than abort the load.
                }
            }
        }
        return out;
    }

    public int insertListing(UUID seller, String itemData, String displayName, int quantity,
                             BigDecimal price, long listedAt, long expiresAt) throws SQLException {
        String sql = "INSERT INTO black_market_listings "
                + "(seller_uuid, item_data, display_name, quantity, price, listed_at, expires_at) "
                + "VALUES (?,?,?,?,?,?,?)";
        try (Connection connection = connection();
             PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, seller.toString());
            statement.setString(2, itemData);
            statement.setString(3, displayName);
            statement.setInt(4, quantity);
            statement.setBigDecimal(5, price);
            statement.setLong(6, listedAt);
            statement.setLong(7, expiresAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        return -1;
    }

    public void updateListingQuantity(int id, int quantity) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE black_market_listings SET quantity=? WHERE id=?")) {
            statement.setInt(1, quantity);
            statement.setInt(2, id);
            statement.executeUpdate();
        }
    }

    public void deleteListingRow(int id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM black_market_listings WHERE id=?")) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }

    public void deleteListing(int chestId, String material) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM shop_chest_listings WHERE chest_id=? AND material=?")) {
            statement.setInt(1, chestId);
            statement.setString(2, material);
            statement.executeUpdate();
        }
    }
}
