package com.example.inventory.service;

import com.example.inventory.config.AuthProperties;
import com.example.inventory.repository.TokenRepository;
import com.example.inventory.repository.TokenRepository.RefreshSession;
import com.example.inventory.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    @Test
    void concurrentRotationMissRevokesRefreshFamily() throws Exception {
        TokenRepository tokens = mock(TokenRepository.class);
        UserAccountService users = mock(UserAccountService.class);
        AuthProperties properties = new AuthProperties();
        RefreshTokenService service = new RefreshTokenService(tokens, users, properties);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthenticatedUser user = new AuthenticatedUser(
                7, "cashier", "hash", "CASHIER", true, 3);
        String rawToken = "refresh-token";
        String tokenHash = hash(service, rawToken);

        when(tokens.findRefreshSessionByHash(tokenHash)).thenReturn(Optional.of(new RefreshSession(
                "session-1",
                "family-1",
                user.id(),
                tokenHash,
                user.authVersion(),
                Instant.now().plusSeconds(600).getEpochSecond(),
                null,
                null,
                null,
                Instant.now().getEpochSecond(),
                "127.0.0.1",
                "test-agent")));
        when(users.requireActive(user.id())).thenReturn(user);
        when(tokens.consumeIfAvailable(eq("session-1"), anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.rotate(rawToken, request))
                .isInstanceOf(ApiException.class);

        verify(tokens).revokeFamily(eq("family-1"), anyLong());
    }

    @Test
    void revocationInvalidatesTheWholeRefreshFamily() throws Exception {
        TokenRepository tokens = mock(TokenRepository.class);
        RefreshTokenService service = new RefreshTokenService(
                tokens, mock(UserAccountService.class), new AuthProperties());
        String tokenHash = hash(service, "ancestor-token");
        when(tokens.findRefreshSessionByHash(tokenHash)).thenReturn(Optional.of(new RefreshSession(
                "session-1", "family-1", 7, tokenHash, 1,
                Instant.now().plusSeconds(600).getEpochSecond(),
                Instant.now().getEpochSecond(), null, "session-2",
                Instant.now().getEpochSecond(), "127.0.0.1", "test-agent")));

        service.revoke("ancestor-token");

        verify(tokens).revokeFamily(eq("family-1"), anyLong());
    }

    @Test
    void revokingAnUnknownTokenIsAnIdempotentNoOp() throws Exception {
        TokenRepository tokens = mock(TokenRepository.class);
        RefreshTokenService service = new RefreshTokenService(
                tokens, mock(UserAccountService.class), new AuthProperties());
        String tokenHash = hash(service, "unknown-token");
        when(tokens.findRefreshSessionByHash(tokenHash)).thenReturn(Optional.empty());

        service.revoke("unknown-token");

        verify(tokens).findRefreshSessionByHash(tokenHash);
        verifyNoMoreInteractions(tokens);
    }
    private String hash(RefreshTokenService service, String rawToken) throws Exception {
        Method method = RefreshTokenService.class.getDeclaredMethod("hash", String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, rawToken);
    }
}
