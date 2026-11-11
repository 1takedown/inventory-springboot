package com.example.inventory.repository;

import com.example.inventory.support.MoneyValues;

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
public class ItemRepository {

    private static final String COLUMNS = """
            id, title, description, quantity, cost, profit, barcode,
            created_at, created_by, modified_at, modified_by, version
            """;

    private static final RowMapper<Item> ITEM_MAPPER = (result, rowNumber) -> new Item(
            result.getLong("id"),
            result.getString("title"),
            result.getString("description"),
            result.getInt("quantity"),
            result.getLong("cost"),
            result.getLong("profit"),
            result.getString("barcode"),
            result.getString("created_at"),
            result.getString("created_by"),
            result.getString("modified_at"),
            result.getString("modified_by"),
            result.getInt("version"),
            MoneyValues.add(result.getLong("cost"), result.getLong("profit")));

    private final JdbcTemplate jdbcTemplate;

    public ItemRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Item> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM items ORDER BY title COLLATE NOCASE, id",
                ITEM_MAPPER);
    }

    public Optional<Item> findById(long id) {
        return single(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM items WHERE id = ?",
                ITEM_MAPPER,
                id));
    }

    public Optional<Item> findByBarcode(String barcode) {
        return single(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM items WHERE barcode = ?",
                ITEM_MAPPER,
                barcode));
    }

    public long insert(
            String title,
            String description,
            int quantity,
            long cost,
            long profit,
            String barcode,
            String actor) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO items (title, description, quantity, cost, profit, barcode, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, title);
            statement.setString(2, description);
            statement.setInt(3, quantity);
            statement.setLong(4, cost);
            statement.setLong(5, profit);
            statement.setString(6, barcode);
            statement.setString(7, actor);
            return statement;
        }, keyHolder);
        return generatedId(keyHolder, "item");
    }

    public int update(
            long id,
            String title,
            String description,
            int quantity,
            long cost,
            long profit,
            String barcode,
            String actor,
            int expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE items
                SET title = ?, description = ?, quantity = ?, cost = ?, profit = ?, barcode = ?,
                    modified_at = CURRENT_TIMESTAMP, modified_by = ?, version = version + 1
                WHERE id = ? AND version = ?
                """, title, description, quantity, cost, profit, barcode, actor, id, expectedVersion);
    }

    public int countOrderReferences(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE item_id = ?",
                Integer.class,
                id);
        return count == null ? 0 : count;
    }

    public int delete(long id) {
        return jdbcTemplate.update("DELETE FROM items WHERE id = ?", id);
    }

    public int decrementStock(long id, int quantity, String actor) {
        return jdbcTemplate.update("""
                UPDATE items
                SET quantity = quantity - ?, modified_at = CURRENT_TIMESTAMP, modified_by = ?,
                    version = version + 1
                WHERE id = ? AND quantity >= ?
                """, quantity, actor, id, quantity);
    }

    public int incrementStock(long id, int quantity, String actor) {
        return jdbcTemplate.update("""
                UPDATE items
                SET quantity = quantity + ?, modified_at = CURRENT_TIMESTAMP, modified_by = ?,
                    version = version + 1
                WHERE id = ? AND quantity <= 2147483647 - ?
                """, quantity, actor, id, quantity);
    }

    private Optional<Item> single(List<Item> matches) {
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    private long generatedId(KeyHolder keyHolder, String entity) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("SQLite did not return an " + entity + " id");
        }
        return key.longValue();
    }

    public record Item(
            long id,
            String title,
            String description,
            int quantity,
            long cost,
            long profit,
            String barcode,
            String createdAt,
            String createdBy,
            String modifiedAt,
            String modifiedBy,
            int version,
            long price) {
    }
}
