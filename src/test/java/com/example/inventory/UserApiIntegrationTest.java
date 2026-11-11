package com.example.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-inventory-users.db",
        "app.auth.jwt-secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.auth.cookie-secure=false",
        "app.auth.legacy-login-enabled=true"
})
@AutoConfigureMockMvc
class UserApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM revoked_access_tokens");
        jdbcTemplate.update("DELETE FROM auth_sessions");
        jdbcTemplate.update("DELETE FROM stock_movements");
        jdbcTemplate.update("DELETE FROM order_return_items");
        jdbcTemplate.update("DELETE FROM order_returns");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM items");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update(
                "INSERT INTO users (username, password, role, created_by) VALUES (?, ?, ?, ?)",
                "admin",
                new BCryptPasswordEncoder().encode("secret"),
                "ADMIN",
                "test");
    }

    @Test
    void adminCanCreateAuditorWhoCanRefreshAndChangeOwnPassword() throws Exception {
        String adminToken = login("admin", "secret");
        mockMvc.perform(post("/api/users").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auditor\",\"password\":\"auditor-secret-password\",\"role\":\"AUDITOR\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("AUDITOR"));
        JsonNode session = objectMapper.readTree(mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auditor\",\"password\":\"auditor-secret-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("AUDITOR"))
                .andReturn().getResponse().getContentAsString());
        JsonNode rotated = objectMapper.readTree(mockMvc.perform(post("/api/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", session.get("refreshToken").asText()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("AUDITOR"))
                .andReturn().getResponse().getContentAsString());
        String access = rotated.get("accessToken").asText();
        mockMvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"auditor-secret-password\",\"newPassword\":\"new-auditor-password\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", rotated.get("refreshToken").asText()))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auditor\",\"password\":\"new-auditor-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("AUDITOR"));
    }

    @Test
    void assigningAuditorInvalidatesExistingAccessAndRefreshTokens() throws Exception {
        String adminToken = login("admin", "secret");
        insertCashier("restricted-user", "cashier-secret");
        JsonNode session = objectMapper.readTree(mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"restricted-user\",\"password\":\"cashier-secret\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = 'restricted-user'", Long.class);
        mockMvc.perform(patch("/api/users/{id}", userId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AUDITOR\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + session.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", session.get("refreshToken").asText()))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"restricted-user\",\"password\":\"cashier-secret\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("AUDITOR"));
    }

    @Test
    void cashierCannotAccessUserManagement() throws Exception {
        insertCashier("cashier", "cashier-secret");
        String cashierToken = login("cashier", "cashier-secret");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"someone","password":"long-enough-password","role":"CASHIER"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateListAndResetUsers() throws Exception {
        String adminToken = login("admin", "secret");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new-cashier","password":"long-enough-password","role":"CASHIER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("new-cashier"))
                .andExpect(jsonPath("$.role").value("CASHIER"))
                .andExpect(jsonPath("$.active").value(1));

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        long cashierId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, "new-cashier");
        mockMvc.perform(post("/api/users/{id}/reset-password", cashierId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"another-secure-password\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new-cashier\",\"password\":\"another-secure-password\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsSelfDemotionAndLastAdminDemotion() throws Exception {
        String adminToken = login("admin", "secret");
        long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, "admin");

        mockMvc.perform(patch("/api/users/{id}", adminId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CASHIER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isString());

        mockMvc.perform(patch("/api/users/{id}", adminId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"second-admin","password":"long-enough-password","role":"ADMIN"}
                                """))
                .andExpect(status().isCreated());
        long secondAdminId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, "second-admin");

        mockMvc.perform(patch("/api/users/{id}", secondAdminId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CASHIER\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsInvalidUserIdAndInvalidCreatePayload() throws Exception {
        String adminToken = login("admin", "secret");

        mockMvc.perform(patch("/api/users/{id}", "not-a-number")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ab","password":"long-enough-password","role":"CASHIER"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"valid-user","password":"short","role":"CASHIER"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokeSessionsInvalidatesOutstandingRefreshTokens() throws Exception {
        String adminToken = login("admin", "secret");
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"session-user","password":"long-enough-password","role":"CASHIER"}
                                """))
                .andExpect(status().isCreated());

        String tokenResponse = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"session-user\",\"password\":\"long-enough-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(tokenResponse).get("refreshToken").asText();
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, "session-user");

        mockMvc.perform(post("/api/users/{id}/revoke-sessions", userId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());

        Integer revokedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NOT NULL",
                Integer.class,
                userId);
        assertThat(revokedCount).isEqualTo(1);
    }

    private void insertCashier(String username, String password) {
        jdbcTemplate.update(
                "INSERT INTO users (username, password, role, created_by) VALUES (?, ?, ?, ?)",
                username,
                new BCryptPasswordEncoder().encode(password),
                "CASHIER",
                "test");
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.get("token").asText();
    }
}
