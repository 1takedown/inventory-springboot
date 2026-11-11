package com.example.inventory.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository {

    private static final String SUMMARY_QUERY = """
            SELECT o.id, o.text, o.status, o.order_date,
                   o.created_at, o.created_by, o.modified_at, o.modified_by,
                   o.confirmed_at, o.confirmed_by, o.cancelled_at, o.cancelled_by,
                   o.archived_at, o.archived_by, o.picked_up_at,
                   COALESCE(SUM(oi.quantity), 0) AS total_quantity,
                   COALESCE(SUM(oi.quantity * oi.paid_amount), 0) AS total_amount,
                   COALESCE(SUM(oi.returned_quantity), 0) AS returned_quantity,
                   COALESCE(SUM(oi.returned_quantity * oi.paid_amount), 0) AS returned_amount,
                   CASE WHEN COUNT(oi.id) = COUNT(oi.profit_snapshot)
                        THEN COALESCE(SUM(oi.quantity * oi.profit_snapshot), 0) END AS total_profit,
                   CASE WHEN COUNT(oi.id) = COUNT(oi.profit_snapshot)
                        THEN COALESCE(SUM(oi.returned_quantity * oi.profit_snapshot), 0) END AS returned_profit,
                   CASE WHEN COUNT(oi.id) = COUNT(oi.profit_snapshot)
                        THEN COALESCE(SUM((oi.quantity - oi.returned_quantity) * oi.profit_snapshot), 0)
                        END AS net_profit,
                   CASE WHEN COUNT(oi.id) = COUNT(oi.cost_snapshot)
                        THEN COALESCE(SUM(oi.quantity * oi.cost_snapshot), 0) END AS total_cost,
                   CASE WHEN COUNT(oi.id) = COUNT(oi.cost_snapshot)
                        THEN COALESCE(SUM(oi.returned_quantity * oi.cost_snapshot), 0) END AS returned_cost,
                   CASE WHEN COUNT(oi.id) = COUNT(oi.cost_snapshot)
                        THEN COALESCE(SUM((oi.quantity - oi.returned_quantity) * oi.cost_snapshot), 0)
                        END AS net_cost
            FROM orders o
            LEFT JOIN order_items oi ON oi.order_id = o.id AND oi.removed_at IS NULL
            """;

    private static final String SEARCH_CONDITION = """
             AND (CAST(o.id AS TEXT) COLLATE NOCASE LIKE ? ESCAPE '\\'
                  OR o.text COLLATE NOCASE LIKE ? ESCAPE '\\'
                  OR EXISTS (
                      SELECT 1
                      FROM order_items oi_search
                      WHERE oi_search.order_id = o.id
                        AND oi_search.removed_at IS NULL
                        AND (oi_search.title_snapshot COLLATE NOCASE LIKE ? ESCAPE '\\'
                             OR oi_search.barcode_snapshot COLLATE NOCASE LIKE ? ESCAPE '\\')
                  ))
            """;

    private static final RowMapper<OrderSummary> SUMMARY_MAPPER = (result, rowNumber) -> new OrderSummary(
            result.getLong("id"),
            result.getString("text"),
            result.getString("status"),
            result.getString("order_date"),
            result.getString("created_at"),
            result.getString("created_by"),
            result.getString("modified_at"),
            result.getString("modified_by"),
            result.getString("confirmed_at"),
            result.getString("confirmed_by"),
            result.getString("cancelled_at"),
            result.getString("cancelled_by"),
            result.getString("archived_at"),
            result.getString("archived_by"),
            result.getString("picked_up_at"),
            result.getInt("total_quantity"),
            result.getLong("total_amount"),
            result.getInt("returned_quantity"),
            result.getLong("returned_amount"),
            nullableLong(result, "total_profit"),
            nullableLong(result, "returned_profit"),
            nullableLong(result, "net_profit"),
            nullableLong(result, "total_cost"),
            nullableLong(result, "returned_cost"),
            nullableLong(result, "net_cost"));

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OrderSummary> findAll() {
        return jdbcTemplate.query(
                SUMMARY_QUERY + """
                        
                        WHERE o.archived_at IS NULL AND o.status <> 'CANCELLED'
                        GROUP BY o.id
                        ORDER BY o.order_date DESC, datetime(o.created_at) DESC, o.id DESC
                        """,
                SUMMARY_MAPPER);
    }

    public List<OrderSummary> findPage(int size, long offset, String search) {
        if (search == null || search.isBlank()) {
            return jdbcTemplate.query(
                    SUMMARY_QUERY + """

                            WHERE o.archived_at IS NULL AND o.status <> 'CANCELLED'
                            GROUP BY o.id
                            ORDER BY o.order_date DESC, datetime(o.created_at) DESC, o.id DESC
                            LIMIT ? OFFSET ?
                            """,
                    SUMMARY_MAPPER,
                    size,
                    offset);
        }
        String pattern = searchPattern(search);
        return jdbcTemplate.query(
                SUMMARY_QUERY + """

                        WHERE o.archived_at IS NULL AND o.status <> 'CANCELLED'
                        """ + SEARCH_CONDITION + """
                        GROUP BY o.id
                        ORDER BY o.order_date DESC, datetime(o.created_at) DESC, o.id DESC
                        LIMIT ? OFFSET ?
                        """,
                SUMMARY_MAPPER,
                pattern,
                pattern,
                pattern,
                pattern,
                size,
                offset);
    }

    public List<OrderSummary> findArchivedPage(int size, long offset) {
        return findArchivedPage(size, offset, "");
    }

    public List<OrderSummary> findArchivedPage(int size, long offset, String search) {
        if (search == null || search.isBlank()) {
            return jdbcTemplate.query(
                    SUMMARY_QUERY + """

                            WHERE o.archived_at IS NOT NULL
                            GROUP BY o.id
                            ORDER BY o.order_date DESC, datetime(o.created_at) DESC, o.id DESC
                            LIMIT ? OFFSET ?
                            """,
                    SUMMARY_MAPPER,
                    size,
                    offset);
        }
        String pattern = searchPattern(search);
        return jdbcTemplate.query(
                SUMMARY_QUERY + """

                        WHERE o.archived_at IS NOT NULL
                        """ + SEARCH_CONDITION + """
                        GROUP BY o.id
                        ORDER BY o.order_date DESC, datetime(o.created_at) DESC, o.id DESC
                        LIMIT ? OFFSET ?
                        """,
                SUMMARY_MAPPER,
                pattern,
                pattern,
                pattern,
                pattern,
                size,
                offset);
    }

    public List<OrderSummary> findCancelledPage(int size, long offset, String search) {
        if (search == null || search.isBlank()) {
            return jdbcTemplate.query(
                    SUMMARY_QUERY + """

                            WHERE o.status = 'CANCELLED'
                            GROUP BY o.id
                            ORDER BY o.order_date DESC, datetime(o.created_at) DESC, o.id DESC
                            LIMIT ? OFFSET ?
                            """,
                    SUMMARY_MAPPER,
                    size,
                    offset);
        }
        String pattern = searchPattern(search);
        return jdbcTemplate.query(
                SUMMARY_QUERY + """

                        WHERE o.status = 'CANCELLED'
                        """ + SEARCH_CONDITION + """
                        GROUP BY o.id
                        ORDER BY o.order_date DESC, datetime(o.created_at) DESC, o.id DESC
                        LIMIT ? OFFSET ?
                        """,
                SUMMARY_MAPPER,
                pattern,
                pattern,
                pattern,
                pattern,
                size,
                offset);
    }

    public long countOrders() {
        return countOrders("");
    }

    public long countOrders(String search) {
        return countMatchingOrders("o.archived_at IS NULL AND o.status <> 'CANCELLED'", search);
    }

    public long countArchivedOrders() {
        return countArchivedOrders("");
    }

    public long countArchivedOrders(String search) {
        return countMatchingOrders("o.archived_at IS NOT NULL", search);
    }

    public long countCancelledOrders(String search) {
        return countMatchingOrders("o.status = 'CANCELLED'", search);
    }

    private long countMatchingOrders(String statusCondition, String search) {
        if (search == null || search.isBlank()) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orders o WHERE " + statusCondition, Long.class);
            return count == null ? 0 : count;
        }
        String pattern = searchPattern(search);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders o WHERE " + statusCondition + SEARCH_CONDITION,
                Long.class,
                pattern,
                pattern,
                pattern,
                pattern);
        return count == null ? 0 : count;
    }

    private static String searchPattern(String search) {
        String escaped = search.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public List<DailyOutItem> findDailyOutItemsOnDate(String orderDate) {
        return jdbcTemplate.query("""
                SELECT oi.item_id, i.title, i.barcode,
                       SUM(oi.quantity) AS gross_quantity,
                       SUM(CASE
                           WHEN oi.removed_at IS NOT NULL OR o.status = 'CANCELLED'
                               THEN oi.quantity
                           ELSE oi.returned_quantity
                       END) AS restored_quantity,
                       SUM(oi.quantity - CASE
                           WHEN oi.removed_at IS NOT NULL OR o.status = 'CANCELLED'
                               THEN oi.quantity
                           ELSE oi.returned_quantity
                       END) AS net_quantity,
                       SUM((oi.quantity - CASE
                           WHEN oi.removed_at IS NOT NULL OR o.status = 'CANCELLED'
                               THEN oi.quantity
                           ELSE oi.returned_quantity
                       END) * oi.paid_amount) AS net_amount,
                       CASE WHEN COUNT(oi.id) = COUNT(oi.cost_snapshot)
                           THEN SUM((oi.quantity - CASE
                               WHEN oi.removed_at IS NOT NULL OR o.status = 'CANCELLED'
                                   THEN oi.quantity
                               ELSE oi.returned_quantity
                           END) * oi.cost_snapshot)
                       END AS net_cost,
                       CASE WHEN COUNT(oi.id) = COUNT(oi.profit_snapshot)
                           THEN SUM((oi.quantity - CASE
                               WHEN oi.removed_at IS NOT NULL OR o.status = 'CANCELLED'
                                   THEN oi.quantity
                               ELSE oi.returned_quantity
                           END) * oi.profit_snapshot)
                       END AS net_profit
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                JOIN items i ON i.id = oi.item_id
                WHERE o.order_date = ?
                GROUP BY oi.item_id, i.title, i.barcode
                ORDER BY net_quantity DESC, gross_quantity DESC, i.title COLLATE NOCASE,
                         oi.item_id
                """, (result, rowNumber) -> new DailyOutItem(
                result.getLong("item_id"),
                result.getString("title"),
                result.getString("barcode"),
                result.getInt("gross_quantity"),
                result.getInt("restored_quantity"),
                result.getInt("net_quantity"),
                result.getLong("net_amount"),
                nullableLong(result, "net_cost"),
                nullableLong(result, "net_profit")), orderDate);
    }

    public List<DailyOutOrder> findDailyOutOrdersForItemOnDate(long itemId, String orderDate) {
        return jdbcTemplate.query("""
                SELECT o.id, o.text, o.status, o.archived_at,
                       SUM(oi.quantity) AS gross_quantity,
                       SUM(CASE
                           WHEN oi.removed_at IS NOT NULL OR o.status = 'CANCELLED'
                               THEN oi.quantity
                           ELSE oi.returned_quantity
                       END) AS restored_quantity,
                       SUM(oi.quantity - CASE
                           WHEN oi.removed_at IS NOT NULL OR o.status = 'CANCELLED'
                               THEN oi.quantity
                           ELSE oi.returned_quantity
                       END) AS net_quantity
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                WHERE oi.item_id = ?
                  AND o.order_date = ?
                GROUP BY o.id, o.text, o.status, o.order_date, o.created_at, o.archived_at
                ORDER BY datetime(o.created_at) DESC, o.id DESC
                """, (result, rowNumber) -> new DailyOutOrder(
                result.getLong("id"),
                result.getString("text"),
                result.getString("status"),
                result.getString("archived_at"),
                result.getInt("gross_quantity"),
                result.getInt("restored_quantity"),
                result.getInt("net_quantity")), itemId, orderDate);
    }

    public Optional<OrderSummary> findActiveById(long id) {
        List<OrderSummary> matches = jdbcTemplate.query(
                SUMMARY_QUERY + """

                        WHERE o.id = ? AND o.archived_at IS NULL AND o.status <> 'CANCELLED'
                        GROUP BY o.id
                        """,
                SUMMARY_MAPPER,
                id);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    public Optional<OrderSummary> findById(long id) {
        List<OrderSummary> matches = jdbcTemplate.query(
                SUMMARY_QUERY + """
                        
                        WHERE o.id = ?
                        GROUP BY o.id
                        """,
                SUMMARY_MAPPER,
                id);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    public List<OrderLine> findLines(long orderId) {
        return findLines(orderId, false);
    }

    public List<OrderLine> findRemovedLines(long orderId) {
        return findLines(orderId, true);
    }

    private List<OrderLine> findLines(long orderId, boolean removed) {
        return jdbcTemplate.query("""
                SELECT oi.id, oi.item_id, oi.title_snapshot AS title,
                       oi.barcode_snapshot AS barcode, oi.quantity, oi.returned_quantity,
                       (oi.quantity - oi.returned_quantity) AS remaining_quantity, oi.paid_amount,
                       (oi.quantity * oi.paid_amount) AS subtotal,
                       oi.cost_snapshot AS cost,
                       (oi.quantity * oi.cost_snapshot) AS cost_subtotal,
                       oi.profit_snapshot AS profit,
                       (oi.quantity * oi.profit_snapshot) AS profit_subtotal,
                       oi.created_at, oi.created_by, oi.removed_at, oi.removed_by
                FROM order_items oi
                WHERE oi.order_id = ? AND ((? = 1 AND oi.removed_at IS NOT NULL)
                                            OR (? = 0 AND oi.removed_at IS NULL))
                ORDER BY oi.id
                """, (result, rowNumber) -> new OrderLine(
                result.getLong("id"),
                result.getLong("item_id"),
                result.getString("title"),
                result.getString("barcode"),
                result.getInt("quantity"),
                result.getInt("returned_quantity"),
                result.getInt("remaining_quantity"),
                result.getLong("paid_amount"),
                result.getLong("subtotal"),
                nullableLong(result, "cost"),
                nullableLong(result, "cost_subtotal"),
                nullableLong(result, "profit"),
                nullableLong(result, "profit_subtotal"),
                result.getString("created_at"),
                result.getString("created_by"),
                result.getString("removed_at"),
                result.getString("removed_by")), orderId, removed ? 1 : 0, removed ? 1 : 0);
    }

    public List<ReturnSummary> findReturns(long orderId) {
        return jdbcTemplate.query("""
                SELECT r.id, r.reason, r.created_at, r.created_by,
                       COALESCE(SUM(ri.quantity), 0) AS total_quantity,
                       COALESCE(SUM(ri.refund_amount), 0) AS total_amount
                FROM order_returns r
                LEFT JOIN order_return_items ri ON ri.return_id = r.id
                WHERE r.order_id = ?
                GROUP BY r.id
                ORDER BY datetime(r.created_at), r.id
                """, (result, rowNumber) -> new ReturnSummary(
                result.getLong("id"),
                result.getString("reason"),
                result.getString("created_at"),
                result.getString("created_by"),
                result.getInt("total_quantity"),
                result.getLong("total_amount")), orderId);
    }

    public List<ReturnLine> findReturnLines(long orderId) {
        return jdbcTemplate.query("""
                SELECT ri.return_id, ri.order_item_id, oi.item_id,
                       oi.title_snapshot AS title, oi.barcode_snapshot AS barcode,
                       ri.quantity, ri.refund_amount
                FROM order_return_items ri
                JOIN order_returns r ON r.id = ri.return_id
                JOIN order_items oi ON oi.id = ri.order_item_id
                WHERE r.order_id = ?
                ORDER BY ri.id
                """, (result, rowNumber) -> new ReturnLine(
                result.getLong("return_id"),
                result.getLong("order_item_id"),
                result.getLong("item_id"),
                result.getString("title"),
                result.getString("barcode"),
                result.getInt("quantity"),
                result.getLong("refund_amount")), orderId);
    }

    public long insert(String text, String actor) {
        return insert(text, null, "CONFIRMED", actor);
    }

    public long insert(String text, String status, String actor) {
        return insert(text, null, status, actor);
    }

    public long insert(String text, String orderDate, String status, String actor) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO orders
                        (text, order_date, status, confirmed_at, confirmed_by, created_by)
                    VALUES (?, COALESCE(?, date('now', '+8 hours')), ?,
                            CASE WHEN ? = 'CONFIRMED' THEN CURRENT_TIMESTAMP END,
                            CASE WHEN ? = 'CONFIRMED' THEN ? END, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, text);
            statement.setString(2, orderDate);
            statement.setString(3, status);
            statement.setString(4, status);
            statement.setString(5, status);
            statement.setString(6, actor);
            statement.setString(7, actor);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("SQLite did not return an order id");
        }
        return key.longValue();
    }

    public long insertLine(
            long orderId,
            long itemId,
            String titleSnapshot,
            String barcodeSnapshot,
            int quantity,
            long paidAmount,
            long costSnapshot,
            long profitSnapshot,
            String actor) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO order_items
                    (order_id, item_id, title_snapshot, barcode_snapshot, quantity,
                     paid_amount, cost_snapshot, profit_snapshot, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, orderId);
            statement.setLong(2, itemId);
            statement.setString(3, titleSnapshot);
            statement.setString(4, barcodeSnapshot);
            statement.setInt(5, quantity);
            statement.setLong(6, paidAmount);
            statement.setLong(7, costSnapshot);
            statement.setLong(8, profitSnapshot);
            statement.setString(9, actor);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("SQLite did not return an order line id");
        return key.longValue();
    }

    public int countActiveLines(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE order_id = ? AND removed_at IS NULL",
                Integer.class,
                orderId);
        return count == null ? 0 : count;
    }

    public int removeLineIfActive(long orderId, long lineId, String actor) {
        return jdbcTemplate.update("""
                UPDATE order_items
                SET removed_at = CURRENT_TIMESTAMP, removed_by = ?,
                    modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ? AND order_id = ? AND removed_at IS NULL
                  AND (SELECT COUNT(*) FROM order_items
                       WHERE order_id = ? AND removed_at IS NULL) > 1
                """, actor, actor, lineId, orderId, orderId);
    }

    public int touchDraft(long orderId, String actor) {
        return jdbcTemplate.update("""
                UPDATE orders
                SET modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ? AND status = 'DRAFT' AND archived_at IS NULL
                """, actor, orderId);
    }

    public int updateDraftText(long orderId, String text, String actor) {
        return jdbcTemplate.update("""
                UPDATE orders
                SET text = ?, modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ? AND status = 'DRAFT' AND archived_at IS NULL
                """, text, actor, orderId);
    }

    public int updateDraftOrderDate(long orderId, String orderDate, String actor) {
        return jdbcTemplate.update("""
                UPDATE orders
                SET order_date = ?, modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ? AND status = 'DRAFT' AND archived_at IS NULL
                """, orderDate, actor, orderId);
    }

    public int confirmDraft(long orderId, String actor) {
        return jdbcTemplate.update("""
                UPDATE orders
                SET status = 'CONFIRMED', confirmed_at = CURRENT_TIMESTAMP, confirmed_by = ?,
                    modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ? AND status = 'DRAFT' AND archived_at IS NULL
                """, actor, actor, orderId);
    }

    public int confirmDraftAsPickedUp(long orderId, String actor) {
        return jdbcTemplate.update("""
                UPDATE orders
                SET status = 'CONFIRMED', confirmed_at = CURRENT_TIMESTAMP, confirmed_by = ?,
                    picked_up_at = CURRENT_TIMESTAMP,
                    modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ? AND status = 'DRAFT' AND archived_at IS NULL
                """, actor, actor, orderId);
    }

    public int cancelDraft(long orderId, String actor) {
        return jdbcTemplate.update("""
                UPDATE orders
                SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP, cancelled_by = ?,
                    modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ? AND status = 'DRAFT' AND archived_at IS NULL
                """, actor, actor, orderId);
    }

    public int archiveIfActive(long orderId, String actor) {
        return jdbcTemplate.update("""
                UPDATE orders
                SET archived_at = CURRENT_TIMESTAMP, archived_by = ?
                WHERE id = ? AND archived_at IS NULL
                """, actor, orderId);
    }

    public int setPickupState(long orderId, boolean pickedUp, String actor) {
        return jdbcTemplate.update("""
                UPDATE orders
                SET picked_up_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                    modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ? AND status = 'CONFIRMED' AND archived_at IS NULL
                """, pickedUp, actor, orderId);
    }

    public long insertReturn(long orderId, String reason, String actor) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO order_returns (order_id, reason, created_by) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, orderId);
            statement.setString(2, reason);
            statement.setString(3, actor);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("SQLite did not return an order return id");
        }
        return key.longValue();
    }

    public int addReturnedQuantity(
            long orderId,
            long orderItemId,
            int quantity,
            String actor) {
        return jdbcTemplate.update("""
                UPDATE order_items
                SET returned_quantity = returned_quantity + ?,
                    modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ? AND order_id = ?
                  AND returned_quantity + ? <= quantity
                """, quantity, actor, orderItemId, orderId, quantity);
    }

    public void insertReturnLine(
            long returnId,
            long orderItemId,
            int quantity,
            long refundAmount) {
        jdbcTemplate.update("""
                INSERT INTO order_return_items
                    (return_id, order_item_id, quantity, refund_amount)
                VALUES (?, ?, ?, ?)
                """, returnId, orderItemId, quantity, refundAmount);
    }

    private static Long nullableLong(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    public record OrderSummary(
            long id,
            String text,
            String status,
            String orderDate,
            String createdAt,
            String createdBy,
            String modifiedAt,
            String modifiedBy,
            String confirmedAt,
            String confirmedBy,
            String cancelledAt,
            String cancelledBy,
            String archivedAt,
            String archivedBy,
            String pickedUpAt,
            int totalQuantity,
            long totalAmount,
            int returnedQuantity,
            long returnedAmount,
            Long totalProfit,
            Long returnedProfit,
            Long netProfit,
            Long totalCost,
            Long returnedCost,
            Long netCost) {
        public OrderSummary(
                long id,
                String text,
                String status,
                String createdAt,
                String createdBy,
                String modifiedAt,
                String modifiedBy,
                String confirmedAt,
                String confirmedBy,
                String cancelledAt,
                String cancelledBy,
                String archivedAt,
                String archivedBy,
                String pickedUpAt,
                int totalQuantity,
                long totalAmount,
                int returnedQuantity,
                long returnedAmount,
                Long totalProfit,
                Long returnedProfit,
                Long netProfit,
                Long totalCost,
                Long returnedCost,
                Long netCost) {
            this(id, text, status, legacyOrderDate(createdAt), createdAt, createdBy,
                    modifiedAt, modifiedBy, confirmedAt, confirmedBy, cancelledAt, cancelledBy,
                    archivedAt, archivedBy, pickedUpAt, totalQuantity, totalAmount,
                    returnedQuantity, returnedAmount, totalProfit, returnedProfit, netProfit,
                    totalCost, returnedCost, netCost);
        }

        public OrderSummary(
                long id,
                String text,
                String status,
                String createdAt,
                String createdBy,
                String modifiedAt,
                String modifiedBy,
                String confirmedAt,
                String confirmedBy,
                String cancelledAt,
                String cancelledBy,
                String archivedAt,
                String archivedBy,
                int totalQuantity,
                long totalAmount,
                int returnedQuantity,
                long returnedAmount,
                Long totalProfit,
                Long returnedProfit,
                Long netProfit,
                Long totalCost,
                Long returnedCost,
                Long netCost) {
            this(id, text, status, legacyOrderDate(createdAt), createdAt, createdBy, modifiedAt, modifiedBy,
                    confirmedAt, confirmedBy, cancelledAt, cancelledBy, archivedAt, archivedBy,
                    null, totalQuantity, totalAmount, returnedQuantity, returnedAmount,
                    totalProfit, returnedProfit, netProfit, totalCost, returnedCost, netCost);
        }

        public OrderSummary(
                long id,
                String text,
                String createdAt,
                String createdBy,
                String modifiedAt,
                String modifiedBy,
                String archivedAt,
                String archivedBy,
                int totalQuantity,
                long totalAmount,
                int returnedQuantity,
                long returnedAmount,
                Long totalProfit,
                Long returnedProfit,
                Long netProfit,
                Long totalCost,
                Long returnedCost,
                Long netCost) {
            this(id, text, "CONFIRMED", legacyOrderDate(createdAt), createdAt, createdBy, modifiedAt, modifiedBy,
                    createdAt, createdBy, null, null, archivedAt, archivedBy, null,
                    totalQuantity, totalAmount, returnedQuantity, returnedAmount,
                    totalProfit, returnedProfit, netProfit, totalCost, returnedCost, netCost);
        }

        private static String legacyOrderDate(String createdAt) {
            return createdAt != null && createdAt.length() >= 10 ? createdAt.substring(0, 10) : createdAt;
        }
    }

    public record OrderLine(
            long id,
            long itemId,
            String title,
            String barcode,
            int quantity,
            int returnedQuantity,
            int remainingQuantity,
            long paidAmount,
            long subtotal,
            Long cost,
            Long costSubtotal,
            Long profit,
            Long profitSubtotal,
            String createdAt,
            String createdBy,
            String removedAt,
            String removedBy) {
        public OrderLine(
                long id,
                long itemId,
                String title,
                String barcode,
                int quantity,
                int returnedQuantity,
                int remainingQuantity,
                long paidAmount,
                long subtotal,
                Long cost,
                Long costSubtotal,
                Long profit,
                Long profitSubtotal) {
            this(id, itemId, title, barcode, quantity, returnedQuantity, remainingQuantity,
                    paidAmount, subtotal, cost, costSubtotal, profit, profitSubtotal,
                    null, null, null, null);
        }
    }

    public record ReturnSummary(
            long id,
            String reason,
            String createdAt,
            String createdBy,
            int totalQuantity,
            long totalAmount) {
    }

    public record ReturnLine(
            long returnId,
            long orderItemId,
            long itemId,
            String title,
            String barcode,
            int quantity,
            long refundAmount) {
    }

    public record DailyOutItem(
            long itemId,
            String title,
            String barcode,
            int grossQuantity,
            int restoredQuantity,
            int netQuantity,
            long netAmount,
            Long netCost,
            Long netProfit) {
    }

    public record DailyOutOrder(
            long id,
            String text,
            String status,
            String archivedAt,
            int grossQuantity,
            int restoredQuantity,
            int netQuantity) {
    }
}
