package com.example.inventory.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findById(long id) {
        return findAccount(
                "SELECT id, username, password, role, active, auth_version FROM users WHERE id = ?",
                id);
    }

    public Optional<UserAccount> findByUsername(String username) {
        return findAccount(
                "SELECT id, username, password, role, active, auth_version FROM users WHERE username = ?",
                username);
    }

    public List<UserView> findAll() {
        return jdbcTemplate.query("""
                SELECT id, username, role, active, auth_version, created_at, created_by,
                       modified_at, modified_by
                FROM users ORDER BY username COLLATE NOCASE
                """, (result, rowNumber) -> mapView(result));
    }

    public UserView findViewByUsername(String username) {
        return jdbcTemplate.queryForObject("""
                SELECT id, username, role, active, auth_version, created_at, created_by,
                       modified_at, modified_by
                FROM users WHERE username = ?
                """, (result, rowNumber) -> mapView(result), username);
    }

    public void insert(String username, String passwordHash, String role, String actor) {
        jdbcTemplate.update("""
                INSERT INTO users (username, password, role, active, auth_version, created_by)
                VALUES (?, ?, ?, 1, 0, ?)
                """, username, passwordHash, role, actor);
    }

    public int updatePasswordHash(long id, String passwordHash, String actor, boolean incrementVersion) {
        if (incrementVersion) {
            return jdbcTemplate.update("""
                    UPDATE users SET password = ?, auth_version = auth_version + 1,
                           modified_at = CURRENT_TIMESTAMP, modified_by = ?
                    WHERE id = ?
                    """, passwordHash, actor, id);
        }
        return jdbcTemplate.update("""
                UPDATE users SET password = ?, modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ?
                """, passwordHash, actor, id);
    }

    public int updateRoleAndActive(long id, String role, boolean active, String actor) {
        return jdbcTemplate.update("""
                UPDATE users SET role = ?, active = ?, auth_version = auth_version + 1,
                       modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ?
                """, role, active ? 1 : 0, actor, id);
    }

    public int activateAdministrator(long id, String actor) {
        return jdbcTemplate.update("""
                UPDATE users SET role = 'ADMIN', active = 1,
                       modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ?
                """, actor, id);
    }

    public int incrementAuthVersion(long id, String actor) {
        return jdbcTemplate.update("""
                UPDATE users SET auth_version = auth_version + 1,
                       modified_at = CURRENT_TIMESTAMP, modified_by = ?
                WHERE id = ?
                """, actor, id);
    }

    public int countUsers() {
        return count("SELECT COUNT(*) FROM users");
    }

    public int countActiveAdministrators() {
        return count("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND active = 1");
    }

    private Optional<UserAccount> findAccount(String sql, Object argument) {
        List<UserAccount> matches = jdbcTemplate.query(sql, (result, rowNumber) -> new UserAccount(
                result.getLong("id"),
                result.getString("username"),
                result.getString("password"),
                result.getString("role"),
                result.getInt("active") == 1,
                result.getInt("auth_version")), argument);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    private UserView mapView(java.sql.ResultSet result) throws java.sql.SQLException {
        return new UserView(
                result.getLong("id"),
                result.getString("username"),
                result.getString("role"),
                result.getInt("active"),
                result.getInt("auth_version"),
                result.getString("created_at"),
                result.getString("created_by"),
                result.getString("modified_at"),
                result.getString("modified_by"));
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    public record UserAccount(
            long id,
            String username,
            String passwordHash,
            String role,
            boolean active,
            int authVersion) {
    }

    public record UserView(
            long id,
            String username,
            String role,
            int active,
            int authVersion,
            String createdAt,
            String createdBy,
            String modifiedAt,
            String modifiedBy) {
    }
}
