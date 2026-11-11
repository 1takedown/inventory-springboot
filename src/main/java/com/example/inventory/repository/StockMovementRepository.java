package com.example.inventory.repository;

import com.example.inventory.repository.ItemRepository.Item;
import com.example.inventory.support.DatabaseTimestamps;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Repository
public class StockMovementRepository {
    public static final List<String> REASONS = List.of("OPENING_STOCK", "MIGRATION_BASELINE",
            "MANUAL_ADJUSTMENT", "ORDER_RESERVED", "ORDER_ITEM_REMOVED", "ORDER_CANCELLED",
            "ORDER_RETURNED", "ITEM_DELETED");

    private final JdbcTemplate jdbc;

    public StockMovementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Item item, int delta, String reason, String actor,
                       Long orderId, Long lineId, Long returnId) {
        jdbc.update("""
                INSERT INTO stock_movements
                  (item_id, item_id_snapshot, title_snapshot, barcode_snapshot, delta,
                   quantity_before, quantity_after, reason, created_by,
                   order_id, order_item_id, order_return_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, item.id(), item.id(), item.title(), item.barcode(), delta,
                (long) item.quantity() - delta, item.quantity(), reason, actor, orderId, lineId, returnId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> page(int page, int size, String search, Long itemId, String reason) {
        return page(page, size, search, itemId, reason, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> page(int page, int size, String search, Long itemId, String reason, LocalDate date) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (date != null) {
            var zone = ZoneId.of("Asia/Shanghai");
            var format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
            where.append(" AND created_at >= ? AND created_at < ?");
            args.add(format.format(date.atStartOfDay(zone).toInstant()));
            args.add(format.format(date.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        if (!search.isEmpty()) {
            where.append(" AND (instr(lower(title_snapshot), lower(?)) > 0 OR instr(lower(barcode_snapshot), lower(?)) > 0)");
            args.add(search);
            args.add(search);
        }
        if (itemId != null) {
            where.append(" AND item_id_snapshot = ?");
            args.add(itemId);
        }
        if (!reason.isEmpty()) {
            where.append(" AND reason = ?");
            args.add(reason);
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM stock_movements" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT id, item_id, item_id_snapshot, title_snapshot, barcode_snapshot,
                       delta, quantity_before, quantity_after, reason, order_id,
                       order_item_id, order_return_id, created_at, created_by,
                       (SELECT o.text FROM orders o WHERE o.id = stock_movements.order_id) AS order_text
                FROM stock_movements
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        rows.forEach(row -> row.put("created_at", DatabaseTimestamps.utcIso((String) row.get("created_at"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("page", page);
        result.put("size", size);
        result.put("totalItems", total);
        result.put("totalPages", (total + size - 1) / size);
        return result;
    }
}
