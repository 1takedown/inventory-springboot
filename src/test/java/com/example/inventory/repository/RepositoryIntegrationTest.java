package com.example.inventory.repository;

import com.example.inventory.repository.TokenRepository.RefreshSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-repositories.db",
        "app.auth.jwt-secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.auth.cookie-secure=false"
})
class RepositoryIntegrationTest {

    @Autowired
    private ItemRepository items;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private UserRepository users;

    @Autowired
    private TokenRepository tokens;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM revoked_access_tokens");
        jdbcTemplate.update("DELETE FROM auth_sessions");
        jdbcTemplate.update("DELETE FROM stock_movements");
        jdbcTemplate.update("DELETE FROM order_return_items");
        jdbcTemplate.update("DELETE FROM order_returns");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM items");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void mapsInventoryProjectionsAndPerformsConditionalStockUpdates() {
        long itemId = items.insert("Widget", "Description", 5, 1050L, 200L, "ABC-1", "test");

        var item = items.findById(itemId).orElseThrow();
        assertThat(item.price()).isEqualTo(1250L);
        assertThat(item.version()).isZero();
        assertThat(items.findByBarcode("ABC-1")).contains(item);
        assertThat(items.decrementStock(itemId, 3, "cashier")).isEqualTo(1);
        assertThat(items.findById(itemId).orElseThrow().version()).isEqualTo(1);
        assertThat(items.decrementStock(itemId, 3, "cashier")).isZero();

        long orderId = orders.insert("Sale", "cashier");
        orders.insertLine(
                orderId, itemId, item.title(), item.barcode(), 3, item.price(),
                item.cost(), item.profit(), "cashier");
        assertThat(items.update(
                itemId,
                "Renamed Widget",
                item.description(),
                2,
                9900L,
                item.profit(),
                "RENAMED",
                "admin",
                1)).isEqualTo(1);

        var summary = orders.findById(orderId).orElseThrow();
        assertThat(summary.totalQuantity()).isEqualTo(3);
        assertThat(summary.totalAmount()).isEqualTo(3750L);
        assertThat(summary.returnedQuantity()).isZero();
        assertThat(summary.totalProfit()).isEqualTo(600L);
        assertThat(summary.returnedProfit()).isZero();
        assertThat(summary.netProfit()).isEqualTo(600L);
        assertThat(summary.totalCost()).isEqualTo(3150L);
        assertThat(summary.returnedCost()).isZero();
        assertThat(summary.netCost()).isEqualTo(3150L);
        assertThat(orders.findLines(orderId)).singleElement()
                .satisfies(line -> {
                    assertThat(line.itemId()).isEqualTo(itemId);
                    assertThat(line.title()).isEqualTo("Widget");
                    assertThat(line.barcode()).isEqualTo("ABC-1");
                    assertThat(line.subtotal()).isEqualTo(3750L);
                    assertThat(line.remainingQuantity()).isEqualTo(3);
                    assertThat(line.cost()).isEqualTo(1050L);
                    assertThat(line.costSubtotal()).isEqualTo(3150L);
                    assertThat(line.profit()).isEqualTo(200L);
                    assertThat(line.profitSubtotal()).isEqualTo(600L);
                });

        long returnId = orders.insertReturn(orderId, "Damaged", "cashier");
        long orderItemId = orders.findLines(orderId).get(0).id();
        assertThat(orders.addReturnedQuantity(orderId, orderItemId, 2, "cashier")).isEqualTo(1);
        assertThat(orders.addReturnedQuantity(orderId, orderItemId, 2, "cashier")).isZero();
        orders.insertReturnLine(returnId, orderItemId, 2, 2500L);

        var returnedSummary = orders.findById(orderId).orElseThrow();
        assertThat(returnedSummary.returnedQuantity()).isEqualTo(2);
        assertThat(returnedSummary.totalProfit()).isEqualTo(600L);
        assertThat(returnedSummary.returnedProfit()).isEqualTo(400L);
        assertThat(returnedSummary.netProfit()).isEqualTo(200L);
        assertThat(returnedSummary.returnedCost()).isEqualTo(2100L);
        assertThat(returnedSummary.netCost()).isEqualTo(1050L);
        assertThat(orders.findLines(orderId).get(0).remainingQuantity()).isEqualTo(1);
        assertThat(orders.findReturns(orderId)).singleElement()
                .satisfies(orderReturn -> {
                    assertThat(orderReturn.reason()).isEqualTo("Damaged");
                    assertThat(orderReturn.totalQuantity()).isEqualTo(2);
                    assertThat(orderReturn.totalAmount()).isEqualTo(2500L);
                });
        assertThat(orders.findReturnLines(orderId)).singleElement()
                .satisfies(line -> {
                    assertThat(line.orderItemId()).isEqualTo(orderItemId);
                    assertThat(line.quantity()).isEqualTo(2);
                });
        assertThat(items.countOrderReferences(itemId)).isEqualTo(1);

        long historicalOrderId = orders.insert("Historical sale", "fixture");
        jdbcTemplate.update("""
                INSERT INTO order_items
                    (order_id, item_id, title_snapshot, barcode_snapshot, quantity,
                     paid_amount, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, historicalOrderId, itemId, "Widget", "ABC-1", 1, 1250L, "fixture");
        var historicalSummary = orders.findById(historicalOrderId).orElseThrow();
        assertThat(historicalSummary.totalProfit()).isNull();
        assertThat(historicalSummary.returnedProfit()).isNull();
        assertThat(historicalSummary.netProfit()).isNull();
        assertThat(historicalSummary.totalCost()).isNull();
        assertThat(historicalSummary.returnedCost()).isNull();
        assertThat(historicalSummary.netCost()).isNull();
        assertThat(orders.findLines(historicalOrderId)).singleElement()
                .satisfies(line -> {
                    assertThat(line.cost()).isNull();
                    assertThat(line.costSubtotal()).isNull();
                    assertThat(line.profit()).isNull();
                    assertThat(line.profitSubtotal()).isNull();
                });
        jdbcTemplate.update(
                "UPDATE order_items SET returned_quantity = quantity WHERE order_id = ?",
                historicalOrderId);
        assertThat(orders.archiveIfActive(historicalOrderId, "admin")).isEqualTo(1);
        assertThat(orders.archiveIfActive(historicalOrderId, "admin")).isZero();
        assertThat(orders.findById(historicalOrderId).orElseThrow().archivedBy()).isEqualTo("admin");
        assertThat(orders.findActiveById(historicalOrderId)).isEmpty();
        assertThat(orders.findAll()).extracting(OrderRepository.OrderSummary::id)
                .doesNotContain(historicalOrderId);
        assertThat(orders.countOrders()).isEqualTo(1);
        assertThat(orders.countArchivedOrders()).isEqualTo(1);
        assertThat(orders.findArchivedPage(10, 0)).extracting(OrderRepository.OrderSummary::id)
                .containsExactly(historicalOrderId);
    }

    @Test
    void searchesOrderPagesUsingOrderAndActiveLineSnapshots() {
        long itemId = items.insert("Current title", "Description", 20, 1000L, 200L, "CURRENT", "admin");
        long textOrderId = orders.insert("Customer Alpha 100%_sale", "cashier");
        orders.insertLine(textOrderId, itemId, "Original Widget", "OLD-ABC", 1, 1200L, 1000L, 200L, "cashier");

        long itemOrderId = orders.insert("Customer Beta", "cashier");
        orders.insertLine(itemOrderId, itemId, "Snapshot Gadget", "SNAP-123", 1, 1200L, 1000L, 200L, "cashier");

        long removedOrderId = orders.insert("Removed line order", "DRAFT", "cashier");
        orders.insertLine(removedOrderId, itemId, "Hidden Match", "HIDDEN", 1, 1200L, 1000L, 200L, "cashier");
        jdbcTemplate.update("UPDATE order_items SET removed_at = CURRENT_TIMESTAMP WHERE order_id = ?", removedOrderId);

        long archivedOrderId = orders.insert("Archived Alpha", "cashier");
        orders.insertLine(archivedOrderId, itemId, "Archive Item", "ARCHIVE", 1, 1200L, 1000L, 200L, "cashier");
        jdbcTemplate.update("UPDATE orders SET archived_at = CURRENT_TIMESTAMP WHERE id = ?", archivedOrderId);

        long cancelledOrderId = orders.insert("Cancelled Alpha", "DRAFT", "cashier");
        orders.insertLine(cancelledOrderId, itemId, "Cancel Item", "CANCEL", 1, 1200L, 1000L, 200L, "cashier");
        jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelledOrderId);

        assertThat(orders.findPage(10, 0, "customer alpha"))
                .extracting(OrderRepository.OrderSummary::id).containsExactly(textOrderId);
        assertThat(orders.findPage(10, 0, "snapshot gadget"))
                .extracting(OrderRepository.OrderSummary::id).containsExactly(itemOrderId);
        assertThat(orders.findPage(10, 0, "snap-123"))
                .extracting(OrderRepository.OrderSummary::id).containsExactly(itemOrderId);
        assertThat(orders.findPage(10, 0, String.valueOf(textOrderId)))
                .extracting(OrderRepository.OrderSummary::id).contains(textOrderId);
        assertThat(orders.findPage(10, 0, "%_"))
                .extracting(OrderRepository.OrderSummary::id).containsExactly(textOrderId);
        assertThat(orders.findPage(10, 0, "hidden match")).isEmpty();
        assertThat(orders.countOrders("alpha")).isEqualTo(1);
        assertThat(orders.findArchivedPage(10, 0, "alpha"))
                .extracting(OrderRepository.OrderSummary::id).containsExactly(archivedOrderId);
        assertThat(orders.countArchivedOrders("alpha")).isEqualTo(1);
        assertThat(orders.findCancelledPage(10, 0, "alpha"))
                .extracting(OrderRepository.OrderSummary::id).containsExactly(cancelledOrderId);
        assertThat(orders.countCancelledOrders("alpha")).isEqualTo(1);
    }

    @Test
    void aggregatesDailyOutItemsByOrderDateAndKeepsCurrentRestorationsVisible() {
        long itemId = items.insert("Widget", "Description", 50, 1000L, 200L, "OLD", "admin");

        long activeOrderId = orders.insert("Active", "cashier");
        orders.insertLine(activeOrderId, itemId, "Widget", "OLD", 5, 1200L, 1000L, 200L, "cashier");
        long activeLineId = orders.findLines(activeOrderId).get(0).id();
        jdbcTemplate.update(
                "UPDATE order_items SET created_at = ? WHERE id = ?",
                "2026-07-25 16:00:00",
                activeLineId);
        jdbcTemplate.update("UPDATE orders SET order_date = '2026-07-26' WHERE id = ?", activeOrderId);
        assertThat(orders.addReturnedQuantity(activeOrderId, activeLineId, 1, "cashier")).isEqualTo(1);

        long removedOrderId = orders.insert("Removed", "DRAFT", "cashier");
        orders.insertLine(removedOrderId, itemId, "Widget", "OLD", 2, 1200L, 1000L, 200L, "cashier");
        long removedLineId = orders.findLines(removedOrderId).get(0).id();
        jdbcTemplate.update(
                "UPDATE order_items SET created_at = ?, removed_at = CURRENT_TIMESTAMP WHERE id = ?",
                "2026-07-25 17:00:00",
                removedLineId);
        jdbcTemplate.update("UPDATE orders SET order_date = '2026-07-26' WHERE id = ?", removedOrderId);

        long cancelledOrderId = orders.insert("Cancelled", "DRAFT", "cashier");
        orders.insertLine(cancelledOrderId, itemId, "Widget", "OLD", 3, 1200L, 1000L, 200L, "cashier");
        long cancelledLineId = orders.findLines(cancelledOrderId).get(0).id();
        jdbcTemplate.update(
                "UPDATE order_items SET created_at = ? WHERE id = ?",
                "2026-07-25 18:00:00",
                cancelledLineId);
        jdbcTemplate.update("UPDATE orders SET order_date = '2026-07-26' WHERE id = ?", cancelledOrderId);
        assertThat(orders.cancelDraft(cancelledOrderId, "cashier")).isEqualTo(1);

        long oldOrderId = orders.insert("Old", "cashier");
        orders.insertLine(oldOrderId, itemId, "Widget", "OLD", 7, 1200L, 1000L, 200L, "cashier");
        jdbcTemplate.update(
                "UPDATE order_items SET created_at = ? WHERE order_id = ?",
                "2026-07-25 15:59:59",
                oldOrderId);
        jdbcTemplate.update("UPDATE orders SET order_date = '2026-07-25' WHERE id = ?", oldOrderId);

        long nextOrderId = orders.insert("Next", "cashier");
        orders.insertLine(nextOrderId, itemId, "Widget", "OLD", 11, 1200L, 1000L, 200L, "cashier");
        jdbcTemplate.update(
                "UPDATE order_items SET created_at = ? WHERE order_id = ?",
                "2026-07-26 16:00:00",
                nextOrderId);
        jdbcTemplate.update("UPDATE orders SET order_date = '2026-07-27' WHERE id = ?", nextOrderId);

        assertThat(items.update(
                itemId, "Current Widget", "Description", 50, 1000L, 200L,
                "CURRENT", "admin", 0)).isEqualTo(1);

        var rows = orders.findDailyOutItemsOnDate("2026-07-26");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.title()).isEqualTo("Current Widget");
            assertThat(row.barcode()).isEqualTo("CURRENT");
            assertThat(row.grossQuantity()).isEqualTo(10);
            assertThat(row.restoredQuantity()).isEqualTo(6);
            assertThat(row.netQuantity()).isEqualTo(4);
            assertThat(row.netAmount()).isEqualTo(4800L);
            assertThat(row.netCost()).isEqualTo(4000L);
            assertThat(row.netProfit()).isEqualTo(800L);
        });

        var relatedOrders = orders.findDailyOutOrdersForItemOnDate(itemId, "2026-07-26");
        assertThat(relatedOrders).extracting(OrderRepository.DailyOutOrder::id)
                .containsExactly(cancelledOrderId, removedOrderId, activeOrderId);
        assertThat(relatedOrders).filteredOn(order -> order.id() == activeOrderId).singleElement()
                .satisfies(order -> {
                    assertThat(order.grossQuantity()).isEqualTo(5);
                    assertThat(order.restoredQuantity()).isEqualTo(1);
                    assertThat(order.netQuantity()).isEqualTo(4);
                });
        assertThat(relatedOrders).filteredOn(order -> order.id() == removedOrderId).singleElement()
                .satisfies(order -> {
                    assertThat(order.status()).isEqualTo("DRAFT");
                    assertThat(order.grossQuantity()).isEqualTo(2);
                    assertThat(order.restoredQuantity()).isEqualTo(2);
                    assertThat(order.netQuantity()).isZero();
                });
        assertThat(relatedOrders).filteredOn(order -> order.id() == cancelledOrderId).singleElement()
                .satisfies(order -> {
                    assertThat(order.status()).isEqualTo("CANCELLED");
                    assertThat(order.grossQuantity()).isEqualTo(3);
                    assertThat(order.restoredQuantity()).isEqualTo(3);
                    assertThat(order.netQuantity()).isZero();
                });

        assertThat(orders.findDailyOutOrdersForItemOnDate(
                itemId,
                "2026-07-25")).singleElement()
                .extracting(OrderRepository.DailyOutOrder::id)
                .isEqualTo(oldOrderId);

        jdbcTemplate.update("UPDATE order_items SET cost_snapshot = NULL, profit_snapshot = NULL WHERE id = ?", activeLineId);
        assertThat(orders.findDailyOutItemsOnDate("2026-07-26")).singleElement().satisfies(row -> {
            assertThat(row.netAmount()).isEqualTo(4800L);
            assertThat(row.netCost()).isNull();
            assertThat(row.netProfit()).isNull();
        });
    }

    @Test
    void mapsUsersAndPersistsTokenLifecycleState() {
        users.insert("admin", "password-hash", "ADMIN", "test");
        var user = users.findByUsername("admin").orElseThrow();
        assertThat(user.active()).isTrue();
        assertThat(users.countActiveAdministrators()).isEqualTo(1);

        tokens.insertRefreshSession(new RefreshSession(
                "session-1",
                "family-1",
                user.id(),
                "hash-1",
                user.authVersion(),
                2_000,
                null,
                null,
                null,
                1_000,
                "127.0.0.1",
                "test-agent"));

        assertThat(tokens.consumeIfAvailable("session-1", 1_100, "session-2")).isEqualTo(1);
        assertThat(tokens.consumeIfAvailable("session-1", 1_101, "session-3")).isZero();
        tokens.revokeFamily("family-1", 1_200);

        var session = tokens.findRefreshSessionByHash("hash-1").orElseThrow();
        assertThat(session.consumedAt()).isEqualTo(1_100);
        assertThat(session.replacedBy()).isEqualTo("session-2");
        assertThat(session.revokedAt()).isEqualTo(1_200);

        tokens.insertRefreshSession(new RefreshSession(
                "session-revoked",
                "family-2",
                user.id(),
                "hash-revoked",
                user.authVersion(),
                2_000,
                null,
                null,
                null,
                1_000,
                "127.0.0.1",
                "test-agent"));
        tokens.revokeFamily("family-2", 1_150);
        assertThat(tokens.consumeIfAvailable("session-revoked", 1_160, "session-next")).isZero();

        tokens.insertRefreshSession(new RefreshSession(
                "session-expired",
                "family-3",
                user.id(),
                "hash-expired",
                user.authVersion(),
                1_050,
                null,
                null,
                null,
                1_000,
                "127.0.0.1",
                "test-agent"));
        assertThat(tokens.consumeIfAvailable("session-expired", 1_100, "session-late")).isZero();

        tokens.revokeAccessToken("jti-1", user.id(), 2_000, 1_000);
        assertThat(tokens.isAccessTokenRevoked("jti-1", 1_500)).isTrue();
        assertThat(tokens.deleteExpiredAccessTokens(2_000)).isEqualTo(1);
        assertThat(tokens.isAccessTokenRevoked("jti-1", 1_500)).isFalse();
        assertThat(tokens.deleteExpiredRefreshSessions(2_000)).isEqualTo(3);
    }
}
