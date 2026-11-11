package com.example.inventory.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private String jwtSecretBase64 = "";
    private String issuer = "inventory-api";
    private String audience = "inventory-clients";
    private long accessTokenSeconds = 600;
    private long refreshTokenSeconds = 2_592_000;
    private boolean cookieSecure = true;
    private String allowedOrigins = "";
    private boolean legacyLoginEnabled = false;
    private boolean requireHttps;
    private byte[] signingKey;

    @PostConstruct
    void validate() {
        try {
            signingKey = Base64.getDecoder().decode(jwtSecretBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT_SECRET_BASE64 must be valid Base64", exception);
        }
        if (signingKey.length < 32) {
            throw new IllegalStateException("JWT_SECRET_BASE64 must decode to at least 32 random bytes");
        }
        if (issuer.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("JWT issuer and audience must not be blank");
        }
        if (accessTokenSeconds < 60 || accessTokenSeconds > 3600) {
            throw new IllegalStateException("Access-token lifetime must be between 60 and 3600 seconds");
        }
        if (refreshTokenSeconds < accessTokenSeconds || refreshTokenSeconds > 31_536_000) {
            throw new IllegalStateException("Refresh-token lifetime is invalid");
        }
    }

    public byte[] signingKey() {
        return signingKey.clone();
    }

    public List<String> allowedOriginList() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    public String getJwtSecretBase64() { return jwtSecretBase64; }
    public void setJwtSecretBase64(String value) { jwtSecretBase64 = value; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String value) { issuer = value; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public long getAccessTokenSeconds() { return accessTokenSeconds; }
    public void setAccessTokenSeconds(long value) { accessTokenSeconds = value; }
    public long getRefreshTokenSeconds() { return refreshTokenSeconds; }
    public void setRefreshTokenSeconds(long value) { refreshTokenSeconds = value; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean value) { cookieSecure = value; }
    public String getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(String value) { allowedOrigins = value; }
    public boolean isLegacyLoginEnabled() { return legacyLoginEnabled; }
    public void setLegacyLoginEnabled(boolean value) { legacyLoginEnabled = value; }
    public boolean isRequireHttps() { return requireHttps; }
    public void setRequireHttps(boolean value) { requireHttps = value; }
}
