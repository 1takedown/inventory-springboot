package com.example.inventory.service;

import com.example.inventory.support.RateLimitException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    @Test
    void successfulLoginsDoNotConsumeIpFailureLimit() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int index = 0; index < 25; index++) {
            limiter.check("cashier" + index, "10.0.0.1");
            limiter.succeeded("cashier" + index, "10.0.0.1");
        }

        limiter.check("another-cashier", "10.0.0.1");
    }

    @Test
    void successfulLoginClearsOnlyThatAccountFailureBucket() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int index = 0; index < 4; index++) {
            limiter.check("cashier", "10.0.0.1");
            limiter.failed("cashier", "10.0.0.1");
        }
        for (int index = 0; index < 15; index++) {
            String username = "other-cashier" + index;
            limiter.check(username, "10.0.0.1");
            limiter.failed(username, "10.0.0.1");
        }

        limiter.check("cashier", "10.0.0.1");
        limiter.succeeded("cashier", "10.0.0.1");
        limiter.check("cashier", "10.0.0.1");

        limiter.check("final-cashier", "10.0.0.1");
        limiter.failed("final-cashier", "10.0.0.1");
        assertThatThrownBy(() -> limiter.check("new-cashier", "10.0.0.1"))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void repeatedAccountFailuresAreThrottled() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int index = 0; index < 5; index++) {
            limiter.check("cashier", "10.0.0.1");
            limiter.failed("cashier", "10.0.0.1");
        }

        assertThatThrownBy(() -> limiter.check("cashier", "10.0.0.1"))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void usernameWithColonDoesNotShareAccountBucketWithIpv6Lookalike() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int index = 0; index < 5; index++) {
            limiter.check("admin", "2001:db8::1");
            limiter.failed("admin", "2001:db8::1");
        }

        assertThatThrownBy(() -> limiter.check("admin", "2001:db8::1"))
                .isInstanceOf(RateLimitException.class);

        limiter.check("admin:2001", "db8::1");
    }

    @Test
    void passwordChangeFailuresAreThrottledPerUser() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int index = 0; index < 5; index++) {
            limiter.checkPasswordChange(7, "10.0.0.1");
            limiter.failedPasswordChange(7, "10.0.0.1");
        }

        assertThatThrownBy(() -> limiter.checkPasswordChange(7, "10.0.0.1"))
                .isInstanceOf(RateLimitException.class);
        limiter.checkPasswordChange(8, "10.0.0.1");
    }

    @Test
    void refreshFailuresAreThrottledPerIp() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int index = 0; index < 60; index++) {
            limiter.checkRefresh("10.0.0.2");
            limiter.failedRefresh("10.0.0.2");
        }

        assertThatThrownBy(() -> limiter.checkRefresh("10.0.0.2"))
                .isInstanceOf(RateLimitException.class);
        limiter.checkRefresh("10.0.0.3");
    }
}
