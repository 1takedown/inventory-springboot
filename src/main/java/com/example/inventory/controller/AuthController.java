package com.example.inventory.controller;

import com.example.inventory.config.AuthProperties;
import com.example.inventory.service.AuthenticatedUser;
import com.example.inventory.service.JwtTokenService;
import com.example.inventory.service.LoginRateLimiter;
import com.example.inventory.service.RefreshTokenService;
import com.example.inventory.service.UserAccountService;
import com.example.inventory.support.ApiException;
import com.example.inventory.support.Messages;
import com.example.inventory.support.RequestValues;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthController.class);
    private static final String REFRESH_COOKIE = "inventory-refresh";

    private final UserAccountService users;
    private final JwtTokenService jwtTokens;
    private final RefreshTokenService refreshTokens;
    private final LoginRateLimiter rateLimiter;
    private final AuthProperties properties;
    private final Messages messages;

    public AuthController(
            UserAccountService users,
            JwtTokenService jwtTokens,
            RefreshTokenService refreshTokens,
            LoginRateLimiter rateLimiter,
            AuthProperties properties,
            Messages messages) {
        this.users = users;
        this.jwtTokens = jwtTokens;
        this.refreshTokens = refreshTokens;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.messages = messages;
    }

    @GetMapping("/auth/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken csrfToken) {
        return noStore(Map.of("token", csrfToken.getToken(), "headerName", csrfToken.getHeaderName()));
    }

    @PostMapping("/auth/session")
    public ResponseEntity<Map<String, Object>> browserLogin(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthenticatedUser user = authenticate(body, request);
        RefreshTokenService.RefreshToken refresh = refreshTokens.issue(user, request);
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(refresh.value(), refresh.expiresAt()).toString());
        return noStore(tokenBody(user, jwtTokens.issue(user), false, null));
    }

    @PostMapping("/auth/session/refresh")
    public ResponseEntity<Map<String, Object>> browserRefresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        rateLimiter.checkRefresh(ip);
        try {
            RefreshTokenService.RotatedRefreshToken rotated = refreshTokens.rotate(requiredToken(refreshToken), request);
            rateLimiter.succeededRefresh(ip);
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    refreshCookie(rotated.token().value(), rotated.token().expiresAt()).toString());
            return noStore(tokenBody(rotated.user(), jwtTokens.issue(rotated.user()), false, null));
        } catch (ApiException exception) {
            rateLimiter.failedRefresh(ip);
            throw exception;
        }
    }

    @DeleteMapping("/auth/session")
    public ResponseEntity<Void> browserLogout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletResponse response) {
        refreshTokens.revoke(refreshToken);
        revokeAccess(jwt);
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping("/auth/token")
    public ResponseEntity<Map<String, Object>> tokenLogin(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        AuthenticatedUser user = authenticate(body, request);
        RefreshTokenService.RefreshToken refresh = refreshTokens.issue(user, request);
        return noStore(tokenBody(user, jwtTokens.issue(user), true, refresh));
    }

    @PostMapping("/auth/token/refresh")
    public ResponseEntity<Map<String, Object>> tokenRefresh(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        rateLimiter.checkRefresh(ip);
        try {
            String refreshToken = body == null ? "" : RequestValues.text(body.get("refreshToken"));
            RefreshTokenService.RotatedRefreshToken rotated = refreshTokens.rotate(requiredToken(refreshToken), request);
            rateLimiter.succeededRefresh(ip);
            return noStore(tokenBody(rotated.user(), jwtTokens.issue(rotated.user()), true, rotated.token()));
        } catch (ApiException exception) {
            rateLimiter.failedRefresh(ip);
            throw exception;
        }
    }

    @PostMapping("/auth/token/revoke")
    public ResponseEntity<Void> tokenRevoke(
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        String refreshToken = body == null ? "" : RequestValues.text(body.get("refreshToken"));
        refreshTokens.revoke(refreshToken);
        revokeAccess(jwt);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PutMapping("/auth/password")
    public ResponseEntity<Void> changePassword(
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request,
            HttpServletResponse response) {
        Map<String, Object> input = body == null ? Map.of() : body;
        String currentPassword = input.get("currentPassword") instanceof String value ? value : "";
        String newPassword = input.get("newPassword") instanceof String value ? value : "";
        long userId = Long.parseLong(jwt.getSubject());
        String ip = request.getRemoteAddr();
        rateLimiter.checkPasswordChange(userId, ip);
        try {
            users.changeOwnPassword(userId, currentPassword, newPassword);
            rateLimiter.succeededPasswordChange(userId, ip);
        } catch (ApiException exception) {
            if (exception.getStatus() == HttpStatus.BAD_REQUEST) {
                rateLimiter.failedPasswordChange(userId, ip);
            }
            throw exception;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> legacyLogin(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        if (!properties.isLegacyLoginEnabled()) {
            throw new ApiException(HttpStatus.GONE, "api.login.removed");
        }
        LOGGER.warn("Deprecated /api/login endpoint used from {}", request.getRemoteAddr());
        AuthenticatedUser user = authenticate(body, request);
        JwtTokenService.AccessToken access = jwtTokens.issue(user);
        Map<String, Object> response = tokenBody(user, access, false, null);
        response.put("message", messages.get(request, "api.login.success"));
        response.put("token", response.remove("accessToken"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Deprecation", "true")
                .header("Link", "</api/auth/token>; rel=\"successor-version\"")
                .body(response);
    }

    private AuthenticatedUser authenticate(Map<String, Object> requestBody, HttpServletRequest request) {
        Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
        String username = RequestValues.text(body.get("username"));
        String password = body.get("password") instanceof String value ? value : "";
        if (username.isEmpty() || username.length() > 64 || password.isEmpty()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.login.required");
        }
        String ip = request.getRemoteAddr();
        rateLimiter.check(username, ip);
        AuthenticatedUser user = users.authenticate(username, password);
        if (user == null) {
            rateLimiter.failed(username, ip);
            LOGGER.warn("Failed login for username {} from {}", username, ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "api.login.invalid");
        }
        rateLimiter.succeeded(username, ip);
        LOGGER.info("Successful login for user {} from {}", user.id(), ip);
        return user;
    }

    private Map<String, Object> tokenBody(
            AuthenticatedUser user,
            JwtTokenService.AccessToken access,
            boolean includeRefresh,
            RefreshTokenService.RefreshToken refresh) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", access.value());
        body.put("tokenType", "Bearer");
        body.put("expiresIn", Duration.between(Instant.now(), access.expiresAt()).toSeconds());
        if (includeRefresh && refresh != null) {
            body.put("refreshToken", refresh.value());
            body.put("refreshExpiresIn", Duration.between(Instant.now(), refresh.expiresAt()).toSeconds());
        }
        body.put("user", Map.of("id", user.id(), "username", user.username(), "role", user.role()));
        return body;
    }

    private ResponseCookie refreshCookie(String value, Instant expiresAt) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(Duration.between(Instant.now(), expiresAt))
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
    }

    private void revokeAccess(Jwt jwt) {
        if (jwt != null) {
            refreshTokens.revokeAccessToken(
                    jwt.getId(), Long.parseLong(jwt.getSubject()), jwt.getExpiresAt());
        }
    }

    private String requiredToken(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "api.auth.refreshInvalid");
        }
        return value;
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
