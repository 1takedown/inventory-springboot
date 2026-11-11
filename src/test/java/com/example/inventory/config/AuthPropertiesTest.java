package com.example.inventory.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthPropertiesTest {

    @Test
    void rejectsMissingOrShortSigningKeys() {
        AuthProperties missing = new AuthProperties();
        assertThatThrownBy(missing::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");

        AuthProperties shortKey = new AuthProperties();
        shortKey.setJwtSecretBase64(Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(shortKey::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }
}
