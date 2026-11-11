package com.example.inventory;

import com.example.inventory.repository.StockMovementRepository;
import com.example.inventory.service.InventoryService;
import com.example.inventory.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-stock-movements.db",
        "app.auth.jwt-secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class StockMovementIntegrationTest {
    @Autowired InventoryService inventory;
    @Autowired StockMovementRepository movements;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        for (String table : List.of("stock_movements", "order_return_items", "order_returns", "order_items", "orders", "items")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void recordsAContinuousBalanceAcrossOrderLifecycleAndManualChanges() {
        long item = item(10);
        inventory.updateItem(item, Map.of("quantity", 12, "version", 0), "admin");
        long order = order(item, 3);
        inventory.updateItem(item, Map.of("quantity", 9, "title", "Renamed", "version", 2), "admin");
        inventory.addOrderItems(order, List.of(Map.of("item_id", item, "quantity", 2)), "cashier", "en");
        long expectedMovement = jdbc.queryForObject("SELECT max(id) FROM stock_movements", Long.class);
        inventory.updateOrderText(order, "Updated sale", "cashier", "en");
        long line = jdbc.queryForObject("SELECT min(id) FROM order_items WHERE order_id = ?", Long.class, order);
        inventory.removeOrderItem(order, line, "cashier", "en");
        inventory.cancelOrder(order, "cashier", "en");

        long sale = order(item, 4);
        inventory.confirmOrder(sale, "cashier", "en");
        inventory.updateOrderPickup(sale, true, "admin", "en");
        inventory.returnOrder(sale, true, null, "Returned", "cashier", "en");
        inventory.updateItem(item, Map.of("quantity", 11, "version", inventory.getItem(item).get("version")), "admin");

        long unrelatedItem = item(5);
        inventory.updateItem(unrelatedItem, Map.of("title", "Renamed", "version", 0), "admin");
        order(unrelatedItem, 1);

        assertThat(jdbc.queryForList("SELECT delta FROM stock_movements WHERE item_id_snapshot = ? ORDER BY id", Integer.class, item))
                .containsExactly(10, 2, -3, -2, 3, 2, -4, 4, -1);
        assertThat(jdbc.queryForList("SELECT quantity_after FROM stock_movements WHERE item_id_snapshot = ? ORDER BY id", Integer.class, item))
                .containsExactly(10, 12, 9, 7, 10, 12, 8, 12, 11);
        assertThat(jdbc.queryForList("SELECT quantity_before FROM stock_movements WHERE item_id_snapshot = ? ORDER BY id", Integer.class, item))
                .containsExactly(0, 10, 12, 9, 7, 10, 12, 8, 12);
        assertThat(jdbc.queryForList("SELECT reason FROM stock_movements WHERE item_id_snapshot = ? ORDER BY id", String.class, item))
                .containsExactly("OPENING_STOCK", "MANUAL_ADJUSTMENT", "ORDER_RESERVED", "ORDER_RESERVED",
                        "ORDER_ITEM_REMOVED", "ORDER_CANCELLED", "ORDER_RESERVED", "ORDER_RETURNED", "MANUAL_ADJUSTMENT");
        assertThat(jdbc.queryForObject("SELECT order_item_id FROM stock_movements WHERE reason = 'ORDER_ITEM_REMOVED' AND item_id_snapshot = ?", Long.class, item))
                .isEqualTo(line);
        assertThat(jdbc.queryForObject("SELECT order_return_id FROM stock_movements WHERE reason = 'ORDER_RETURNED' AND item_id_snapshot = ?", Long.class, item))
                .isEqualTo(jdbc.queryForObject("SELECT id FROM order_returns WHERE order_id = ?", Long.class, sale));

        Map<String, Object> page = movements.page(1, 1, "Renamed", item, "ORDER_RESERVED");
        assertThat(page).containsEntry("totalItems", 2L).containsEntry("totalPages", 2L);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) page.get("items");
        assertThat(rows).extracting(row -> ((Number) row.get("id")).longValue()).containsExactly(expectedMovement);
        assertThat(((Number) rows.get(0).get("delta")).intValue()).isEqualTo(-2);
        assertThat(rows.get(0)).containsEntry("created_by", "cashier").containsEntry("order_text", "Updated sale");
        assertThat(((Number) rows.get(0).get("order_id")).longValue()).isEqualTo(order);
        assertThat(rows.get(0).get("created_at").toString()).endsWith("Z");
    }

    @Test
    void retainsDeletedItemIdentityAndClosesItsBalance() {
        long item = item(5);
        inventory.updateItem(item, Map.of("title", "New name", "barcode", "REUSED", "version", 0), "admin");
        inventory.deleteItem(item, "admin");
        long replacement = ((Number) inventory.createItem(Map.of("title", "Replacement", "barcode", "REUSED", "quantity", 0, "cost", 1, "profit", 0), "admin").get("id")).longValue();
        assertThat(jdbc.queryForList("SELECT reason, delta, quantity_before, quantity_after FROM stock_movements WHERE item_id_snapshot = ?", replacement))
                .singleElement().satisfies(row -> assertThat(row)
                        .containsEntry("reason", "OPENING_STOCK")
                        .containsEntry("delta", 0).containsEntry("quantity_before", 0).containsEntry("quantity_after", 0));
        assertThat(movements.page(0, 50, "", item, "")).containsEntry("totalItems", 2L);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) movements.page(0, 50, "", item, "").get("items");
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("order_id", null).containsEntry("order_text", null));
        assertThat(jdbc.queryForList("SELECT quantity_after FROM stock_movements WHERE item_id_snapshot = ? ORDER BY id", Integer.class, item))
                .containsExactly(5, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE item_id_snapshot = ? AND item_id IS NULL", Integer.class, item))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT created_by FROM stock_movements WHERE reason = 'ITEM_DELETED'", String.class))
                .isEqualTo("admin");
    }

    @Test
    void movementFailureRollsBackOrderAndInventory() {
        long item = item(5);
        jdbc.execute("""
                CREATE TRIGGER fail_movement BEFORE INSERT ON stock_movements
                BEGIN SELECT RAISE(ABORT, 'test ledger failure'); END
                """);
        try {
            assertThatThrownBy(() -> order(item, 2))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("test ledger failure");
        } finally {
            jdbc.execute("DROP TRIGGER fail_movement");
        }
        assertThat(inventory.getItem(item)).containsEntry("quantity", 5).containsEntry("version", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements", Integer.class)).isEqualTo(1);
    }

    @Test
    void overflowRollsBackTheWholeReturn() {
        long item = item(5);
        long sale = order(item, 2);
        inventory.confirmOrder(sale, "cashier", "en");
        inventory.updateItem(item, Map.of("quantity", Integer.MAX_VALUE, "version", 1), "admin");
        assertThatThrownBy(() -> inventory.returnOrder(sale, true, null, null, "cashier", "en"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(409);
                    assertThat(error.getMessageKey()).isEqualTo("api.item.stockOverflow");
                });
        assertThat(inventory.getItem(item)).containsEntry("quantity", Integer.MAX_VALUE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_returns", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT returned_quantity FROM order_items", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements", Integer.class)).isEqualTo(3);
    }

    private long item(int quantity) {
        return ((Number) inventory.createItem(Map.of("title", "Widget", "quantity", quantity, "cost", 1, "profit", 0), "admin").get("id")).longValue();
    }

    private long order(long item, int quantity) {
        return ((Number) inventory.createOrder("Sale", List.of(Map.of("item_id", item, "quantity", quantity)),
                "DRAFT", "cashier", "en").get("id")).longValue();
    }
}
