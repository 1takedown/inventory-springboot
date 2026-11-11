package com.example.inventory.support;

public class RateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitException(long retryAfterSeconds) {
        super("api.login.rateLimited");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
