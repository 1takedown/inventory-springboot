package com.example.inventory;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayExistingDatabaseMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void createsConsecutiveMigrationsWithoutTheDeferredRecognitionSchema() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("fresh-sequence.db") + "?foreign_keys=true";
        var flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().toString())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA foreign_keys")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery("PRAGMA table_info(items)")) {
                while (columns.next()) {
                    assertThat(columns.getString("name")).isNotEqualTo("recognition_key");
                }
            }
            try (var columns = statement.executeQuery("PRAGMA table_info(orders)")) {
                while (columns.next()) {
                    assertThat(columns.getString("name")).isNotEqualTo("port");
                }
            }
            try (var indexes = statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE name = 'idx_items_recognition_key'")) {
                assertThat(indexes.next()).isTrue();
                assertThat(indexes.getInt(1)).isZero();
            }
            statement.execute("INSERT INTO users (username, password, created_by) VALUES ('default-admin', 'hash', 'test')");
            try (var rows = statement.executeQuery("SELECT role FROM users WHERE username = 'default-admin'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("ADMIN");
            }
        }
    }

    @Test
    void addsAuditorRoleWithoutChangingUsersSessionsOrIdSequence() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("auditor-role.db") + "?foreign_keys=true";
        Flyway.configure().dataSource(url, null, null).target(MigrationVersion.fromVersion("11")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO users
                        (id, username, password, role, active, auth_version, created_at, created_by, modified_at, modified_by)
                    VALUES (7, 'admin', 'admin-hash', 'ADMIN', 1, 2, '2026-01-01', 'fixture', NULL, NULL),
                           (8, 'cashier', 'cashier-hash', 'CASHIER', 0, 5, '2026-01-02', 'admin', '2026-01-03', 'admin'),
                           (31, 'deleted', 'hash', 'CASHIER', 1, 0, '2026-01-01', 'fixture', NULL, NULL)
                    """);
            statement.execute("DELETE FROM users WHERE id = 31");
            statement.execute("""
                    INSERT INTO auth_sessions (id, family_id, user_id, token_hash, auth_version, expires_at, created_at)
                    VALUES ('session', 'family', 8, 'token-hash', 5, 9999999999, 1)
                    """);
            statement.execute("INSERT INTO revoked_access_tokens VALUES ('revoked', 8, 9999999999, 1)");
        }

        var flyway = Flyway.configure().dataSource(url, null, null).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA foreign_keys")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            try (var rows = statement.executeQuery("SELECT * FROM users ORDER BY id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("id")).isEqualTo(7);
                assertThat(rows.getString("username")).isEqualTo("admin");
                assertThat(rows.getString("password")).isEqualTo("admin-hash");
                assertThat(rows.getString("role")).isEqualTo("ADMIN");
                assertThat(rows.getInt("auth_version")).isEqualTo(2);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("id")).isEqualTo(8);
                assertThat(rows.getString("username")).isEqualTo("cashier");
                assertThat(rows.getString("password")).isEqualTo("cashier-hash");
                assertThat(rows.getString("role")).isEqualTo("CASHIER");
                assertThat(rows.getInt("active")).isZero();
                assertThat(rows.getInt("auth_version")).isEqualTo(5);
                assertThat(rows.getString("created_at")).isEqualTo("2026-01-02");
                assertThat(rows.getString("created_by")).isEqualTo("admin");
                assertThat(rows.getString("modified_at")).isEqualTo("2026-01-03");
                assertThat(rows.getString("modified_by")).isEqualTo("admin");
                assertThat(rows.next()).isFalse();
            }
            for (String table : new String[]{"auth_sessions", "revoked_access_tokens"}) {
                try (var rows = statement.executeQuery("SELECT * FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("user_id")).isEqualTo(8);
                    assertThat(rows.getLong("expires_at")).isEqualTo(9999999999L);
                    assertThat(rows.next()).isFalse();
                }
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(rows.next()).isFalse();
            }
            statement.execute("INSERT INTO users (username, password, role, created_by) VALUES ('auditor', 'hash', 'AUDITOR', 'admin')");
            try (var rows = statement.executeQuery("SELECT id FROM users WHERE username = 'auditor'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(32);
            }
            statement.execute("INSERT INTO users (username, password, created_by) VALUES ('default-admin', 'hash', 'test')");
            try (var rows = statement.executeQuery("SELECT id, role FROM users WHERE username = 'default-admin'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("id")).isEqualTo(33);
                assertThat(rows.getString("role")).isEqualTo("ADMIN");
            }
            assertThatThrownBy(() -> statement.execute("INSERT INTO users (username, password, role, created_by) VALUES ('invalid', 'hash', 'OTHER', 'admin')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("UPDATE users SET active = 2 WHERE username = 'auditor'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void establishesStockBaselinesWithoutChangingStock() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("stock-baselines.db");
        Flyway.configure().dataSource(url, null, null).target(MigrationVersion.fromVersion("10")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO items (title, quantity, cost, profit, created_by) VALUES ('Existing', 7, 100, 0, 'old'), ('Empty', 0, 100, 0, 'old')");
        }
        var flyway = Flyway.configure().dataSource(url, null, null).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("SELECT m.*, i.quantity FROM stock_movements m JOIN items i ON i.id = m.item_id ORDER BY m.id")) {
            for (int quantity : new int[]{7, 0}) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt("delta")).isEqualTo(quantity);
                assertThat(result.getInt("quantity_before")).isZero();
                assertThat(result.getInt("quantity_after")).isEqualTo(quantity);
                assertThat(result.getInt("quantity")).isEqualTo(quantity);
                assertThat(result.getString("reason")).isEqualTo("MIGRATION_BASELINE");
                assertThat(result.getString("created_by")).isEqualTo("system:migration");
            }
            assertThat(result.next()).isFalse();
        }
    }

    @Test
    void backfillsOrderDatesUsingTheShanghaiCalendarDay() throws Exception {
        Path database = Files.createTempFile(tempDir, "order-date-migration-", ".db");
        String url = "jdbc:sqlite:" + database;
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("9"))
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO orders (text, created_at, created_by)
                    VALUES ('Before Shanghai midnight', '2026-07-26 15:59:59', 'fixture'),
                           ('After Shanghai midnight', '2026-07-26 16:00:00', 'fixture')
                    """);
        }

        Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "SELECT order_date FROM orders ORDER BY id")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("2026-07-26");
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("2026-07-27");
        }
    }

    @Test
    void upgradesAnExistingDatabaseWithoutLosingUsers() throws Exception {
        Path database = Files.createTempFile(tempDir, "existing-inventory-", ".db");
        String url = "jdbc:sqlite:" + database;
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      username TEXT UNIQUE NOT NULL,
                      password TEXT NOT NULL,
                      modified_at TEXT,
                      modified_by TEXT,
                      created_at TEXT DEFAULT CURRENT_TIMESTAMP NOT NULL,
                      created_by TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO users (username, password, created_by)
                    VALUES ('existing-admin', '$2a$10$placeholder', 'fixture')
                    """);
        }

        Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "SELECT username, role, active, auth_version FROM users")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("username")).isEqualTo("existing-admin");
            assertThat(result.getString("role")).isEqualTo("ADMIN");
            assertThat(result.getInt("active")).isEqualTo(1);
            assertThat(result.getInt("auth_version")).isZero();
        }
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM auth_sessions")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isZero();
        }
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM order_returns")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isZero();
        }
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "PRAGMA table_info(order_items)")) {
            boolean foundReturnedQuantity = false;
            boolean foundTitleSnapshot = false;
            boolean foundProfitSnapshot = false;
            boolean foundCostSnapshot = false;
            boolean foundRemovedAt = false;
            while (result.next()) {
                foundReturnedQuantity |= "returned_quantity".equals(result.getString("name"));
                foundTitleSnapshot |= "title_snapshot".equals(result.getString("name"));
                foundProfitSnapshot |= "profit_snapshot".equals(result.getString("name"));
                foundCostSnapshot |= "cost_snapshot".equals(result.getString("name"));
                foundRemovedAt |= "removed_at".equals(result.getString("name"));
            }
            assertThat(foundReturnedQuantity).isTrue();
            assertThat(foundTitleSnapshot).isTrue();
            assertThat(foundProfitSnapshot).isTrue();
            assertThat(foundCostSnapshot).isTrue();
            assertThat(foundRemovedAt).isTrue();
        }
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "PRAGMA table_info(orders)")) {
            boolean foundArchivedAt = false;
            boolean foundArchivedBy = false;
            boolean foundStatus = false;
            boolean foundConfirmedAt = false;
            boolean foundCancelledAt = false;
            boolean foundPort = false;
            boolean foundPickedUpAt = false;
            boolean foundOrderDate = false;
            while (result.next()) {
                foundArchivedAt |= "archived_at".equals(result.getString("name"));
                foundArchivedBy |= "archived_by".equals(result.getString("name"));
                foundStatus |= "status".equals(result.getString("name"));
                foundConfirmedAt |= "confirmed_at".equals(result.getString("name"));
                foundCancelledAt |= "cancelled_at".equals(result.getString("name"));
                foundPort |= "port".equals(result.getString("name"));
                foundPickedUpAt |= "picked_up_at".equals(result.getString("name"));
                foundOrderDate |= "order_date".equals(result.getString("name"));
            }
            assertThat(foundArchivedAt).isTrue();
            assertThat(foundArchivedBy).isTrue();
            assertThat(foundStatus).isTrue();
            assertThat(foundConfirmedAt).isTrue();
            assertThat(foundCancelledAt).isTrue();
            assertThat(foundPort).isFalse();
            assertThat(foundPickedUpAt).isTrue();
            assertThat(foundOrderDate).isTrue();
        }
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "PRAGMA table_info(items)")) {
            boolean foundVersion = false;
            while (result.next()) {
                foundVersion |= "version".equals(result.getString("name"));
            }
            assertThat(foundVersion).isTrue();
        }
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "PRAGMA index_list(orders)")) {
            boolean foundArchiveIndex = false;
            boolean foundOrderDateIndex = false;
            while (result.next()) {
                foundArchiveIndex |= "idx_orders_archived_at".equals(result.getString("name"));
                foundOrderDateIndex |= "idx_orders_order_date".equals(result.getString("name"));
            }
            assertThat(foundArchiveIndex).isTrue();
            assertThat(foundOrderDateIndex).isTrue();
        }
    }

    @Test
    void convertsLegacyMoneyToCheckedIntegerCentsWithHalfUpRounding() throws Exception {
        Path database = Files.createTempFile(tempDir, "money-migration-", ".db");
        String url = "jdbc:sqlite:" + database;
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("7"))
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO users (username, password, created_by) VALUES ('admin', 'hash', 'fixture')");
            statement.execute("INSERT INTO items (title, quantity, cost, profit, created_by) VALUES ('Widget', 5, 10.005, 0.335, 'fixture')");
            statement.execute("INSERT INTO orders (text, created_by) VALUES ('Legacy order', 'fixture')");
            statement.execute("""
                    INSERT INTO order_items
                        (order_id, item_id, quantity, paid_amount, cost_snapshot,
                         profit_snapshot, created_by)
                    VALUES (1, 1, 2, 10.345, 10.005, 0.335, 'fixture')
                    """);
            statement.execute("INSERT INTO order_returns (order_id, created_by) VALUES (1, 'fixture')");
            statement.execute("""
                    INSERT INTO order_return_items
                        (return_id, order_item_id, quantity, refund_amount)
                    VALUES (1, 1, 1, 20.005)
                    """);
        }

        Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "SELECT cost, profit, typeof(cost), typeof(profit) FROM items")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1001L);
            assertThat(result.getLong(2)).isEqualTo(34L);
            assertThat(result.getString(3)).isEqualTo("integer");
            assertThat(result.getString(4)).isEqualTo("integer");
        }
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "SELECT paid_amount, cost_snapshot, profit_snapshot, typeof(paid_amount) FROM order_items")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1035L);
            assertThat(result.getLong(2)).isEqualTo(1001L);
            assertThat(result.getLong(3)).isEqualTo(34L);
            assertThat(result.getString(4)).isEqualTo("integer");
        }
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "SELECT refund_amount, typeof(refund_amount) FROM order_return_items")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(2001L);
            assertThat(result.getString(2)).isEqualTo("integer");
        }
    }
}
