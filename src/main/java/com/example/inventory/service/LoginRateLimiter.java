package com.example.inventory.service;

import com.example.inventory.support.RateLimitException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class LoginRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final long WINDOW_SECONDS = 600;
    private final Cache<String, ArrayDeque<Long>> failures = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(15))
            .build();

    public synchronized void check(String username, String ipAddress) {
        long now = Instant.now().getEpochSecond();
        String accountKey = accountKey(username, ipAddress);
        String ipKey = ipKey(ipAddress);
        long retryAfter = Math.max(
                retryAfter(accountKey, 5, now),
                retryAfter(ipKey, 20, now));
        if (retryAfter > 0) {
            LOGGER.warn("Throttled login attempts from client address {}", ipAddress);
            throw new RateLimitException(retryAfter);
        }
    }

    public synchronized void failed(String username, String ipAddress) {
        long now = Instant.now().getEpochSecond();
        add(accountKey(username, ipAddress), now);
        add(ipKey(ipAddress), now);
    }

    public synchronized void succeeded(String username, String ipAddress) {
        failures.invalidate(accountKey(username, ipAddress));
    }

    public synchronized void checkPasswordChange(long userId, String ipAddress) {
        long now = Instant.now().getEpochSecond();
        long retryAfter = retryAfter(passwordKey(userId, ipAddress), 5, now);
        if (retryAfter > 0) {
            LOGGER.warn("Throttled password-change attempts for user {} from {}", userId, ipAddress);
            throw new RateLimitException(retryAfter);
        }
    }

    public synchronized void failedPasswordChange(long userId, String ipAddress) {
        add(passwordKey(userId, ipAddress), Instant.now().getEpochSecond());
    }

    public synchronized void succeededPasswordChange(long userId, String ipAddress) {
        failures.invalidate(passwordKey(userId, ipAddress));
    }

    public synchronized void checkRefresh(String ipAddress) {
        long now = Instant.now().getEpochSecond();
        long retryAfter = retryAfter(refreshKey(ipAddress), 60, now);
        if (retryAfter > 0) {
            LOGGER.warn("Throttled refresh-token attempts from client address {}", ipAddress);
            throw new RateLimitException(retryAfter);
        }
    }

    public synchronized void failedRefresh(String ipAddress) {
        add(refreshKey(ipAddress), Instant.now().getEpochSecond());
    }

    public synchronized void succeededRefresh(String ipAddress) {
        failures.invalidate(refreshKey(ipAddress));
    }

    private static String accountKey(String username, String ipAddress) {
        return "account|" + encode(username.toLowerCase()) + "|" + encode(ipAddress);
    }

    private static String ipKey(String ipAddress) {
        return "ip|" + encode(ipAddress);
    }

    private static String passwordKey(long userId, String ipAddress) {
        return "password|" + userId + "|" + encode(ipAddress);
    }

    private static String refreshKey(String ipAddress) {
        return "refresh|" + encode(ipAddress);
    }

    private static String encode(String value) {
        return HexFormat.of().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void add(String key, long now) {
        ArrayDeque<Long> values = failures.get(key, ignored -> new ArrayDeque<>());
        synchronized (values) {
            discardExpired(values, now);
            values.addLast(now);
        }
    }

    private long retryAfter(String key, int limit, long now) {
        ArrayDeque<Long> values = failures.getIfPresent(key);
        if (values == null) {
            return 0;
        }
        synchronized (values) {
            discardExpired(values, now);
            return values.size() >= limit ? Math.max(1, values.getFirst() + WINDOW_SECONDS - now) : 0;
        }
    }

    private void discardExpired(ArrayDeque<Long> values, long now) {
        while (!values.isEmpty() && values.getFirst() <= now - WINDOW_SECONDS) {
            values.removeFirst();
        }
    }
}
