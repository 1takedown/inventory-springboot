package com.example.inventory.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public TokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RefreshSession> findRefreshSessionByHash(String tokenHash) {
        List<RefreshSession> matches = jdbcTemplate.query("""
                SELECT id, family_id, user_id, token_hash, auth_version, expires_at,
                       consumed_at, revoked_at, replaced_by, created_at, created_ip, user_agent
                FROM auth_sessions WHERE token_hash = ?
                """, (result, rowNumber) -> new RefreshSession(
                result.getString("id"),
                result.getString("family_id"),
                result.getLong("user_id"),
                result.getString("token_hash"),
                result.getInt("auth_version"),
                result.getLong("expires_at"),
                nullableLong(result, "consumed_at"),
                nullableLong(result, "revoked_at"),
                result.getString("replaced_by"),
                result.getLong("created_at"),
                result.getString("created_ip"),
                result.getString("user_agent")), tokenHash);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    public void insertRefreshSession(RefreshSession session) {
        jdbcTemplate.update("""
                INSERT INTO auth_sessions
                    (id, family_id, user_id, token_hash, auth_version, expires_at, created_at,
                     created_ip, user_agent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                session.id(), session.familyId(), session.userId(), session.tokenHash(),
                session.authVersion(), session.expiresAt(), session.createdAt(),
                session.createdIp(), session.userAgent());
    }

    public int consumeIfAvailable(String id, long consumedAt, String replacementId) {
        return jdbcTemplate.update("""
                UPDATE auth_sessions
                SET consumed_at = ?, replaced_by = ?
                WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ?
                """,
                consumedAt, replacementId, id, consumedAt);
    }

    public int revokeFamily(String familyId, long revokedAt) {
        return jdbcTemplate.update(
                "UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE family_id = ?",
                revokedAt, familyId);
    }

    public int revokeUserSessions(long userId, long revokedAt) {
        return jdbcTemplate.update(
                "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                revokedAt, userId);
    }

    public void revokeAccessToken(String jti, long userId, long expiresAt, long revokedAt) {
        jdbcTemplate.update("""
                INSERT INTO revoked_access_tokens (jti, user_id, expires_at, revoked_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(jti) DO NOTHING
                """, jti, userId, expiresAt, revokedAt);
    }

    public boolean isAccessTokenRevoked(String jti, long now) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM revoked_access_tokens WHERE jti = ? AND expires_at > ?",
                Integer.class,
                jti,
                now);
        return count != null && count > 0;
    }

    public int deleteExpiredAccessTokens(long now) {
        return jdbcTemplate.update("DELETE FROM revoked_access_tokens WHERE expires_at <= ?", now);
    }

    public int deleteExpiredRefreshSessions(long now) {
        return jdbcTemplate.update("DELETE FROM auth_sessions WHERE expires_at <= ?", now);
    }

    private static Long nullableLong(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    public record RefreshSession(
            String id,
            String familyId,
            long userId,
            String tokenHash,
            int authVersion,
            long expiresAt,
            Long consumedAt,
            Long revokedAt,
            String replacedBy,
            long createdAt,
            String createdIp,
            String userAgent) {
    }
}
