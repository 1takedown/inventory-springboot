package com.example.inventory.service;

import com.example.inventory.config.AuthProperties;
import com.example.inventory.repository.TokenRepository;
import com.example.inventory.repository.TokenRepository.RefreshSession;
import com.example.inventory.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenService.class);
    private final TokenRepository tokens;
    private final UserAccountService users;
    private final AuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            TokenRepository tokens,
            UserAccountService users,
            AuthProperties properties) {
        this.tokens = tokens;
        this.users = users;
        this.properties = properties;
    }

    @Transactional
    public RefreshToken issue(AuthenticatedUser user, HttpServletRequest request) {
        return create(user, UUID.randomUUID().toString(), request);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public RotatedRefreshToken rotate(String rawToken, HttpServletRequest request) {
        long now = Instant.now().getEpochSecond();
        RefreshSession session = tokens.findRefreshSessionByHash(hash(rawToken))
                .orElseThrow(this::invalid);
        String familyId = session.familyId();
        boolean consumed = session.consumedAt() != null;
        boolean revoked = session.revokedAt() != null;
        boolean expired = session.expiresAt() <= now;
        if (consumed || revoked || expired) {
            tokens.revokeFamily(familyId, now);
            LOGGER.warn("Rejected refresh-token replay or expiry for session family {}", familyId);
            throw invalid();
        }
        long userId = session.userId();
        AuthenticatedUser user = users.requireActive(userId);
        int sessionVersion = session.authVersion();
        if (sessionVersion != user.authVersion()) {
            tokens.revokeFamily(familyId, now);
            LOGGER.info("Revoked stale refresh-token family {} for user {}", familyId, user.id());
            throw invalid();
        }
        String nextId = UUID.randomUUID().toString();
        int updated = tokens.consumeIfAvailable(session.id(), now, nextId);
        if (updated != 1) {
            tokens.revokeFamily(familyId, now);
            LOGGER.warn("Revoked refresh-token family {} after a concurrent consume or replay", familyId);
            throw invalid();
        }
        RefreshToken next = create(user, familyId, nextId, request);
        return new RotatedRefreshToken(user, next);
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokens.findRefreshSessionByHash(hash(rawToken))
                .ifPresent(session -> tokens.revokeFamily(
                        session.familyId(),
                        Instant.now().getEpochSecond()));
    }

    public void revokeAccessToken(String jti, long userId, Instant expiresAt) {
        if (jti == null || expiresAt == null) {
            return;
        }
        tokens.revokeAccessToken(
                jti,
                userId,
                expiresAt.getEpochSecond(),
                Instant.now().getEpochSecond());
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000)
    public void cleanupExpired() {
        long now = Instant.now().getEpochSecond();
        tokens.deleteExpiredAccessTokens(now);
        tokens.deleteExpiredRefreshSessions(now);
    }

    private RefreshToken create(AuthenticatedUser user, String familyId, HttpServletRequest request) {
        return create(user, familyId, UUID.randomUUID().toString(), request);
    }

    private RefreshToken create(
            AuthenticatedUser user,
            String familyId,
            String id,
            HttpServletRequest request) {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + properties.getRefreshTokenSeconds();
        tokens.insertRefreshSession(new RefreshSession(
                id,
                familyId,
                user.id(),
                hash(raw),
                user.authVersion(),
                expiresAt,
                null,
                null,
                null,
                now,
                request.getRemoteAddr(),
                truncate(request.getHeader("User-Agent"), 255)));
        return new RefreshToken(raw, Instant.ofEpochSecond(expiresAt));
    }

    private String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String truncate(String value, int maxLength) {
        return value == null ? null : value.substring(0, Math.min(value.length(), maxLength));
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "api.auth.refreshInvalid");
    }

    public record RefreshToken(String value, Instant expiresAt) {
    }

    public record RotatedRefreshToken(AuthenticatedUser user, RefreshToken token) {
    }
}
