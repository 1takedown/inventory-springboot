package com.example.inventory;

import com.example.inventory.service.InventoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-inventory.db",
        "app.auth.jwt-secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.auth.cookie-secure=false",
        "app.auth.legacy-login-enabled=true"
})
@AutoConfigureMockMvc
class InventoryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryService inventory;

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
                "INSERT INTO users (username, password, role, created_by) VALUES (?, ?, 'ADMIN', ?)",
                "admin",
                new BCryptPasswordEncoder().encode("secret"),
                "test");
    }

    @Test
    void auditorCanReadInventoryOrdersDashboardAndStockHistoryWithoutMonetaryFields() throws Exception {
        String token = auditorToken();
        long firstItem = ((Number) inventory.createItem(Map.of(
                "title", "Auditor widget", "quantity", 20, "cost", 12.34, "profit", 5.67, "barcode", "VIEW-1"), "admin").get("id")).longValue();
        long secondItem = ((Number) inventory.createItem(Map.of(
                "title", "Removed widget", "quantity", 20, "cost", 2, "profit", 3), "admin").get("id")).longValue();
        var draft = inventory.createOrder("Active draft", List.of(
                Map.of("item_id", firstItem, "quantity", 2), Map.of("item_id", secondItem, "quantity", 1)), "DRAFT", "admin", "en");
        long draftId = ((Number) draft.get("id")).longValue();
        JsonNode draftJson = objectMapper.valueToTree(draft);
        inventory.removeOrderItem(draftId, draftJson.get("items").get(1).get("id").asLong(), "admin", "en");
        var sale = inventory.createOrder("Active sale", List.of(Map.of("item_id", firstItem, "quantity", 3)),
                "CONFIRMED", "admin", "en");
        long saleId = ((Number) sale.get("id")).longValue();
        JsonNode saleJson = objectMapper.valueToTree(sale);
        inventory.returnOrder(saleId, false, List.of(Map.of(
                "order_item_id", saleJson.get("items").get(0).get("id").asLong(), "quantity", 1)), "Damaged", "admin", "en");
        long cancelledId = ((Number) inventory.createOrder("Cancelled", List.of(Map.of("item_id", firstItem, "quantity", 1)),
                "DRAFT", "admin", "en").get("id")).longValue();
        inventory.cancelOrder(cancelledId, "admin", "en");
        long archivedId = ((Number) inventory.createOrder("Archived", List.of(Map.of("item_id", firstItem, "quantity", 1)),
                "CONFIRMED", "admin", "en").get("id")).longValue();
        inventory.returnOrder(archivedId, true, null, null, "admin", "en");
        inventory.archiveOrder(archivedId, "admin", "en");
        String orderDate = String.valueOf(sale.get("order_date"));

        for (String path : List.of("/api/items", "/api/items/by-barcode?barcode=VIEW-1", "/api/items/by-barcode/VIEW-1",
                "/api/orders", "/api/orders/page?page=0&size=1&q=Active", "/api/orders/" + draftId, "/api/orders/" + saleId,
                "/api/orders/archived/page", "/api/orders/cancelled/page",
                "/api/orders/" + archivedId, "/api/orders/" + cancelledId,
                "/api/dashboard/daily-out-items?date=" + orderDate,
                "/api/dashboard/daily-out-items/" + firstItem + "/orders?date=" + orderDate,
                "/api/stock-movements")) {
            JsonNode response = objectMapper.readTree(mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertNoMonetaryFields(response);
        }
        mockMvc.perform(get("/api/items/by-barcode/VIEW-1").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.title").value("Auditor widget"))
                .andExpect(jsonPath("$.quantity").value(16));
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/orders/page").queryParam("q", "Active").queryParam("size", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/orders/{id}", draftId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.removed_items[0].title").value("Removed widget"));
        mockMvc.perform(get("/api/orders/{id}", saleId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.return_status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.returns[0].items[0].quantity").value(1));
        mockMvc.perform(get("/api/orders/archived/page").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items[0].id").value(archivedId))
                .andExpect(jsonPath("$.items[0].return_status").value("FULLY_RETURNED"));
        mockMvc.perform(get("/api/orders/cancelled/page").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items[0].id").value(cancelledId))
                .andExpect(jsonPath("$.items[0].cancelled_by").value("admin"));
        mockMvc.perform(get("/api/orders/{id}", archivedId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.archived_at").isNotEmpty())
                .andExpect(jsonPath("$.items[0].returned_quantity").value(1));
        mockMvc.perform(get("/api/orders/{id}", cancelledId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/dashboard/daily-out-items").queryParam("date", orderDate)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.date").value(orderDate))
                .andExpect(jsonPath("$.totals.gross_quantity").isNumber())
                .andExpect(jsonPath("$.totals.net_quantity").isNumber())
                .andExpect(jsonPath("$.items[?(@.title == 'Auditor widget')]").isNotEmpty());
        mockMvc.perform(get("/api/dashboard/daily-out-items/{id}/orders", firstItem).queryParam("date", orderDate)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.orders[?(@.id == " + saleId + ")].text").value("Active sale"));
        mockMvc.perform(get("/api/stock-movements").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items[?(@.order_text == 'Archived')].reason",
                        org.hamcrest.Matchers.contains("ORDER_RETURNED", "ORDER_RESERVED")))
                .andExpect(jsonPath("$.items[0].quantity_after").isNumber());
        for (String path : List.of("/api/users", "/api/receipt-templates", "/api/receipt-templates/simple/download")) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
        String adminToken = login();
        mockMvc.perform(get("/api/items/by-barcode/VIEW-1").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.cost").value(12.34)).andExpect(jsonPath("$.price").value(18.01));
        mockMvc.perform(get("/api/orders/{id}", saleId).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.total_amount").isNumber())
                .andExpect(jsonPath("$.returns[0].items[0].refund_amount").isNumber());
        mockMvc.perform(get("/api/orders/{id}", draftId).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.removed_items[0].subtotal").isNumber());
        mockMvc.perform(get("/api/orders/archived/page").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.items[0].total_amount").isNumber());
        mockMvc.perform(get("/api/orders/cancelled/page").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.items[0].total_amount").isNumber());
        mockMvc.perform(get("/api/dashboard/daily-out-items").queryParam("date", orderDate)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totals.net_amount").isNumber())
                .andExpect(jsonPath("$.totals.net_cost").isNumber())
                .andExpect(jsonPath("$.totals.net_profit").isNumber());
    }

    @Test
    void auditorCannotWriteBusinessDataEvenThroughDirectApiRequests() throws Exception {
        String token = auditorToken();
        long auditorId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = 'auditor'", Long.class);
        long itemId = ((Number) inventory.createItem(Map.of("title", "Widget", "quantity", 10, "cost", 1, "profit", 2), "admin").get("id")).longValue();
        var draft = inventory.createOrder("Draft", List.of(Map.of("item_id", itemId, "quantity", 1)), "DRAFT", "admin", "en");
        long draftId = ((Number) draft.get("id")).longValue();
        long lineId = objectMapper.valueToTree(draft).get("items").get(0).get("id").asLong();
        long saleId = ((Number) inventory.createOrder("Sale", List.of(Map.of("item_id", itemId, "quantity", 1)),
                "CONFIRMED", "admin", "en").get("id")).longValue();
        Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
        for (String table : List.of("users", "items", "orders", "order_items", "stock_movements", "order_returns",
                "order_return_items", "auth_sessions", "revoked_access_tokens")) {
            before.put(table, jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
        }
        for (var request : List.of(
                post("/api/items"), put("/api/items/{id}", itemId), delete("/api/items/{id}", itemId),
                post("/api/orders"), put("/api/orders/{id}/text", draftId),
                put("/api/orders/{id}/date", draftId), post("/api/orders/{id}/items", draftId),
                delete("/api/orders/{id}/items/{lineId}", draftId, lineId), post("/api/orders/{id}/confirm", draftId),
                post("/api/orders/{id}/cancel", draftId), put("/api/orders/{id}/pickup", draftId),
                put("/api/orders/{id}/pickup", saleId), post("/api/orders/{id}/returns", saleId),
                post("/api/orders/{id}/archive", saleId), post("/api/users"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/users/{id}", auditorId),
                post("/api/users/{id}/reset-password", auditorId), post("/api/users/{id}/revoke-sessions", auditorId))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            for (var table : before.entrySet()) {
                assertThat(jdbcTemplate.queryForList("SELECT * FROM " + table.getKey() + " ORDER BY 1"))
                        .as("%s must not change after a forbidden auditor request", table.getKey()).isEqualTo(table.getValue());
            }
        }
    }

    private String auditorToken() throws Exception {
        jdbcTemplate.update("INSERT INTO users (username, password, role, created_by) VALUES ('auditor', ?, 'AUDITOR', 'test')",
                new BCryptPasswordEncoder().encode("auditor-secret"));
        return objectMapper.readTree(mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"auditor\",\"password\":\"auditor-secret\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("AUDITOR"))
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }

    private void assertNoMonetaryFields(JsonNode node) {
        node.fieldNames().forEachRemaining(name -> assertThat(name.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("price", "cost", "profit", "amount", "subtotal"));
        for (JsonNode child : node) assertNoMonetaryFields(child);
    }

    @Test
    void forwardsEveryFrontendRouteToTheSinglePageApp() throws Exception {
        for (String path : List.of(
                "/login", "/items", "/orders", "/orders/5", "/orders/archived", "/orders/cancelled",
                "/sell", "/stock-history", "/dashboard", "/account", "/users")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void filtersStockMovementsByShanghaiDayWithCombinedFiltersAndPagination() throws Exception {
        String token = login();
        for (String timestamp : new String[] {
                "2026-09-04 15:59:59", "2026-09-04 16:00:00",
                "2026-09-05 15:59:59", "2026-09-05 16:00:00" }) {
            jdbcTemplate.update("""
                    INSERT INTO stock_movements
                      (item_id_snapshot, title_snapshot, delta, quantity_before, quantity_after,
                       reason, created_by, created_at)
                    VALUES (7, 'Widget', 1, 0, 1, 'OPENING_STOCK', 'test', ?)
                    """, timestamp);
        }
        jdbcTemplate.update("""
                INSERT INTO stock_movements
                  (item_id_snapshot, title_snapshot, delta, quantity_before, quantity_after,
                   reason, created_by, created_at)
                VALUES (8, 'Other', 1, 0, 1, 'MANUAL_ADJUSTMENT', 'test', '2026-09-05 00:00:00')
                """);
        mockMvc.perform(get("/api/stock-movements").header("Authorization", "Bearer " + token)
                        .param("date", "2026-09-05").param("q", "Widget").param("itemId", "7")
                        .param("reason", "OPENING_STOCK").param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].created_at").value("2026-09-04T16:00:00Z"));
        mockMvc.perform(get("/api/stock-movements").header("Authorization", "Bearer " + token)
                        .param("date", "2026-09-05").param("reason", "OPENING_STOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].created_at").value("2026-09-05T15:59:59Z"))
                .andExpect(jsonPath("$.totalItems").value(2));
        mockMvc.perform(get("/api/stock-movements").header("Authorization", "Bearer " + token)
                        .param("date", "2026-09-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        for (String noDate : new String[] { null, "" }) {
            var request = get("/api/stock-movements").header("Authorization", "Bearer " + token);
            if (noDate != null) request.param("date", noDate);
            mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(5));
            mockMvc.perform(request.param("q", "Widget").param("itemId", "7")
                            .param("reason", "OPENING_STOCK").param("size", "2").param("page", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(4))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[0].created_at").value("2026-09-04T16:00:00Z"))
                    .andExpect(jsonPath("$.items[1].created_at").value("2026-09-04T15:59:59Z"));
        }
        for (String invalid : new String[] { "2026-02-30", "invalid", "2026-9-05" }) {
            mockMvc.perform(get("/api/stock-movements").header("Authorization", "Bearer " + token)
                            .param("date", invalid))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rotatesRefreshTokensAndRejectsReplay() throws Exception {
        String response = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(response).get("refreshToken").asText();

        String rotatedResponse = mockMvc.perform(post("/api/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn().getResponse().getContentAsString();
        String rotatedToken = objectMapper.readTree(rotatedResponse).get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", rotatedToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokingRotatedAncestorInvalidatesItsDescendant() throws Exception {
        JsonNode login = objectMapper.readTree(mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String ancestor = login.get("refreshToken").asText();

        JsonNode rotated = objectMapper.readTree(mockMvc.perform(post("/api/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", ancestor))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String descendant = rotated.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/token/revoke")
                        .header("Authorization", "Bearer " + rotated.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", ancestor))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", descendant))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mapsFrameworkClientErrorsWithoutReturningInternalServerError() throws Exception {
        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The request body or parameters are invalid"));

        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());

        mockMvc.perform(get("/api/does-not-exist")
                        .header("Authorization", "Bearer " + login()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("The requested API resource was not found"));
    }

    @Test
    void storesAndCalculatesCurrencyExactlyAndRejectsFractionalCents() throws Exception {
        String adminToken = login();
        String itemResponse = mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Exact\",\"quantity\":5,\"cost\":0.1,\"profit\":0.2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(0.3))
                .andReturn().getResponse().getContentAsString();
        long itemId = objectMapper.readTree(itemResponse).get("id").asLong();

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Exact total\",\"items\":[{\"item_id\":"
                                + itemId + ",\"quantity\":3}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total_amount").value(0.9))
                .andExpect(jsonPath("$.total_cost").value(0.3))
                .andExpect(jsonPath("$.total_profit").value(0.6));

        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Fractional cent\",\"quantity\":1,\"cost\":1.001,\"profit\":0}"))
                .andExpect(status().isBadRequest());
    }
    @Test
    void updateReturnsBadRequestWhenResolvedCostAndProfitOverflow() throws Exception {
        String adminToken = login();
        JsonNode item = objectMapper.readTree(mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Maximum price","quantity":1,
                                 "cost":"92233720368547758.07","profit":0}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(put("/api/items/" + item.get("id").asLong())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profit":0.01,"version":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The monetary total is too large"));
    }
    @Test
    void cashierCanCreateItemsOrdersAndEditDraftLinesButCannotDeleteItemsOrChangeSavedOrders() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (username, password, role, created_by) VALUES (?, ?, ?, ?)",
                "cashier",
                new BCryptPasswordEncoder().encode("cashier-secret"),
                "CASHIER",
                "test");
        String response = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"cashier\",\"password\":\"cashier-secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).get("token").asText();

        mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stock-movements").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stock-movements").header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.size").value(50));
        mockMvc.perform(get("/api/dashboard/daily-out-items")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/dashboard/daily-out-items/1/orders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        String created = mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cashier item\",\"quantity\":1,\"cost\":1,\"profit\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Cashier item"))
                .andReturn().getResponse().getContentAsString();
        long itemId = objectMapper.readTree(created).get("id").asLong();
        mockMvc.perform(put("/api/items/" + itemId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed by cashier\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed by cashier"));
        mockMvc.perform(delete("/api/items/" + itemId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM items WHERE id = ?", Integer.class, itemId)).isEqualTo(1);

        String adminToken = login();
        long[] draftItemIds = new long[2];
        for (int index = 0; index < draftItemIds.length; index++) {
            draftItemIds[index] = objectMapper.readTree(mockMvc.perform(post("/api/items")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Draft item " + index + "\",\"quantity\":5,\"cost\":1,\"profit\":1}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString()).get("id").asLong();
        }
        String draftBody = """
                {"text":"Cashier draft","status":"DRAFT","order_date":"2026-01-03",
                 "items":[{"item_id":%d,"quantity":1},{"item_id":%d,"quantity":1}]}
                """.formatted(draftItemIds[0], draftItemIds[1]);
        JsonNode draft = objectMapper.readTree(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(draftBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.created_by").value("cashier"))
                .andExpect(jsonPath("$.items[0].created_by").value("cashier"))
                .andReturn().getResponse().getContentAsString());
        long orderId = draft.get("id").asLong();
        long lineId = draft.get("items").get(0).get("id").asLong();
        for (long draftItemId : draftItemIds) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT quantity FROM items WHERE id = ?", Integer.class, draftItemId)).isEqualTo(4);
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM stock_movements
                WHERE order_id = ? AND reason = 'ORDER_RESERVED' AND created_by = 'cashier'
                  AND delta = -1 AND quantity_before = 5 AND quantity_after = 4
                """, Integer.class, orderId)).isEqualTo(2);

        JsonNode confirmed = objectMapper.readTree(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Cashier confirmed sale","status":"CONFIRMED",
                                 "items":[{"item_id":%d,"quantity":1}]}
                                """.formatted(draftItemIds[0])))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.created_by").value("cashier"))
                .andExpect(jsonPath("$.confirmed_by").value("cashier"))
                .andReturn().getResponse().getContentAsString());
        long confirmedId = confirmed.get("id").asLong();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM items WHERE id = ?", Integer.class, draftItemIds[0])).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM stock_movements
                WHERE order_id = ? AND reason = 'ORDER_RESERVED' AND created_by = 'cashier'
                  AND delta = -1 AND quantity_before = 4 AND quantity_after = 3
                """, Integer.class, confirmedId)).isEqualTo(1);

        Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
        for (String table : List.of("orders", "order_items", "items", "stock_movements", "order_returns", "order_return_items")) {
            before.put(table, jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }

        for (String invalidBody : List.of("{}", """
                {"text":"Insufficient stock","status":"CONFIRMED",
                 "items":[{"item_id":%d,"quantity":1},{"item_id":%d,"quantity":999}]}
                """.formatted(draftItemIds[1], draftItemIds[0]))) {
            mockMvc.perform(post("/api/orders")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(invalidBody))
                    .andExpect(status().isBadRequest());
            for (var table : before.entrySet()) {
                assertThat(jdbcTemplate.queryForList("SELECT * FROM " + table.getKey() + " ORDER BY id"))
                        .as("%s must not change after an invalid order", table.getKey())
                        .isEqualTo(table.getValue());
            }
        }

        for (var request : List.of(
                put("/api/orders/{id}/text", orderId).content("{\"text\":\"Changed\"}"),
                put("/api/orders/{id}/date", orderId).content("{\"order_date\":\"2026-01-04\"}"),
                post("/api/orders/{id}/confirm", orderId),
                post("/api/orders/{id}/cancel", orderId),
                put("/api/orders/{id}/pickup", orderId).content("{\"picked_up\":true}"),
                put("/api/orders/{id}/pickup", confirmedId).content("{\"picked_up\":true}"),
                post("/api/orders/{id}/returns", confirmedId).content("{\"full\":true}"),
                post("/api/orders/{id}/archive", confirmedId))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
            for (var table : before.entrySet()) {
                assertThat(jdbcTemplate.queryForList("SELECT * FROM " + table.getKey() + " ORDER BY id"))
                        .as("%s must not change after a forbidden request", table.getKey())
                        .isEqualTo(table.getValue());
            }
        }

        mockMvc.perform(post("/api/orders/{id}/items", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"item_id\":%d,\"quantity\":1}]}".formatted(draftItemIds[1])))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.modified_by").value("cashier"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM items WHERE id = ?", Integer.class, draftItemIds[1])).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM stock_movements
                WHERE order_id = ? AND reason = 'ORDER_RESERVED' AND created_by = 'cashier'
                  AND delta = -1 AND quantity_before = 4 AND quantity_after = 3
                """, Integer.class, orderId)).isEqualTo(1);

        mockMvc.perform(delete("/api/orders/{id}/items/{lineId}", orderId, lineId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.removed_items[0].id").value(lineId))
                .andExpect(jsonPath("$.removed_items[0].removed_by").value("cashier"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM items WHERE id = ?", Integer.class, draftItemIds[0])).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM stock_movements
                WHERE order_id = ? AND order_item_id = ? AND reason = 'ORDER_ITEM_REMOVED'
                  AND created_by = 'cashier' AND delta = 1
                """, Integer.class, orderId, lineId)).isEqualTo(1);

        mockMvc.perform(post("/api/orders/{id}/items", confirmedId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"item_id\":%d,\"quantity\":1}]}".formatted(draftItemIds[1])))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_DRAFT"));
    }

    @Test
    void adminCanViewSelectedDateDailyOutItemsIncludingDrafts() throws Exception {
        String adminToken = login();
        long itemId = objectMapper.readTree(mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Dashboard Widget","description":"","quantity":5,
                                 "cost":10.0,"profit":2.0,"barcode":"DASH-1"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        long orderId = objectMapper.readTree(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Dashboard draft","status":"DRAFT","order_date":"2026-07-25",
                                 "items":[{"item_id":%d,"quantity":2}]}
                                """.formatted(itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.port").doesNotExist())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(get("/api/dashboard/daily-out-items")
                        .queryParam("date", "2026-07-25")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-07-25"))
                .andExpect(jsonPath("$.time_zone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.totals.gross_quantity").value(2))
                .andExpect(jsonPath("$.totals.restored_quantity").value(0))
                .andExpect(jsonPath("$.totals.net_quantity").value(2))
                .andExpect(jsonPath("$.totals.net_amount").value(24.0))
                .andExpect(jsonPath("$.totals.net_cost").value(20.0))
                .andExpect(jsonPath("$.totals.net_profit").value(4.0))
                .andExpect(jsonPath("$.items[0].port").doesNotExist())
                .andExpect(jsonPath("$.items[0].title").value("Dashboard Widget"))
                .andExpect(jsonPath("$.items[0].barcode").value("DASH-1"));

        mockMvc.perform(get("/api/dashboard/daily-out-items")
                        .queryParam("date", "2026-07-24")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-07-24"))
                .andExpect(jsonPath("$.totals.gross_quantity").value(0))
                .andExpect(jsonPath("$.items").isEmpty());

        long secondOrderId = objectMapper.readTree(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Second dashboard draft","status":"DRAFT","order_date":"2026-07-25",
                                 "items":[{"item_id":%d,"quantity":1}]}
                                """.formatted(itemId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(get("/api/dashboard/daily-out-items/" + itemId + "/orders")
                        .queryParam("date", "2026-07-25")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.port").doesNotExist())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].id").value(secondOrderId))
                .andExpect(jsonPath("$.orders[1].id").value(orderId))
                .andExpect(jsonPath("$.orders[1].status").value("DRAFT"))
                .andExpect(jsonPath("$.orders[1].gross_quantity").value(2))
                .andExpect(jsonPath("$.orders[1].net_quantity").value(2));
        mockMvc.perform(get("/api/dashboard/daily-out-items")
                        .queryParam("date", "2026-07-25")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].gross_quantity").value(3))
                .andExpect(jsonPath("$.items[0].net_quantity").value(3));
    }

    @Test
    void dashboardRejectsInvalidSelectedDate() throws Exception {
        mockMvc.perform(get("/api/dashboard/daily-out-items")
                        .queryParam("date", "2026-02-30")
                        .header("Authorization", "Bearer " + login()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("The request body or parameters are invalid"));

        mockMvc.perform(get("/api/dashboard/daily-out-items/not-an-id/orders")
                        .header("Authorization", "Bearer " + login()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/dashboard/daily-out-items/1/orders")
                        .header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk());
    }

    @Test
    void dashboardOmitsUnknownFinancialSnapshots() throws Exception {
        String adminToken = login();
        long itemId = objectMapper.readTree(mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Legacy Dashboard Widget","description":"","quantity":5,
                                 "cost":10.0,"profit":2.0,"barcode":"LEGACY-DASH"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        long orderId = objectMapper.readTree(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Legacy dashboard sale",
                                 "items":[{"item_id":%d,"quantity":2}]}
                                """.formatted(itemId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        jdbcTemplate.update(
                "UPDATE order_items SET cost_snapshot = NULL, profit_snapshot = NULL WHERE order_id = ?",
                orderId);

        mockMvc.perform(get("/api/dashboard/daily-out-items")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.gross_quantity").value(2))
                .andExpect(jsonPath("$.totals.net_amount").value(24.0))
                .andExpect(jsonPath("$.totals.net_cost").doesNotExist())
                .andExpect(jsonPath("$.totals.net_profit").doesNotExist())
                .andExpect(jsonPath("$.items[0].net_cost").doesNotExist())
                .andExpect(jsonPath("$.items[0].net_profit").doesNotExist());
    }

    @Test
    void browserLoginRequiresCsrfAndSetsHttpOnlyRefreshCookie() throws Exception {
        var csrfResult = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andReturn();
        String csrfResponse = csrfResult.getResponse().getContentAsString();
        String csrfToken = objectMapper.readTree(csrfResponse).get("token").asText();
        jakarta.servlet.http.Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/auth/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(jsonPath("$.error").value("The security token is invalid or expired"));

        mockMvc.perform(post("/api/auth/session")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(result -> assertThat(
                        result.getResponse().getCookie("inventory-refresh").isHttpOnly()).isTrue());
    }

    @Test
    void wrongCurrentPasswordIsAValidationFailure() throws Exception {
        String token = login();

        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-password","newPassword":"a-new-secure-password"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The current password is incorrect"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication token is required"));
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectsStockHistoryTimestampsBarcodeQueriesAndOrderPaging() throws Exception {
        String adminToken = login();
        String itemResponse = mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Original Widget","description":"Original","quantity":5,
                                 "cost":10.0,"profit":2.0,"barcode":"ORIGINAL"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode item = objectMapper.readTree(itemResponse);
        long itemId = item.get("id").asLong();
        assertThat(item.get("created_at").asText()).endsWith("Z");

        String orderResponse = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Versioned sale","items":[{"item_id":%d,"quantity":1}]}
                                """.formatted(itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].title").value("Original Widget"))
                .andReturn().getResponse().getContentAsString();
        JsonNode order = objectMapper.readTree(orderResponse);
        long orderId = order.get("id").asLong();
        assertThat(order.get("created_at").asText()).endsWith("Z");

        mockMvc.perform(put("/api/items/{id}", itemId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Stale overwrite","description":"Original","quantity":5,
                                 "cost":10.0,"profit":2.0,"barcode":"STALE","version":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_CHANGED"));

        mockMvc.perform(put("/api/items/{id}", itemId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Renamed Widget","description":"Current","quantity":4,
                                 "cost":10.0,"profit":2.0,"barcode":"A/B","version":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(get("/api/items/by-barcode")
                        .param("barcode", "A/B")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed Widget"));

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Original Widget"))
                .andExpect(jsonPath("$.items[0].barcode").value("ORIGINAL"));

        mockMvc.perform(get("/api/orders/page")
                        .param("page", "0")
                        .param("size", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(orderId))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(get("/api/orders/page")
                        .param("q", "original widget")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(orderId))
                .andExpect(jsonPath("$.totalItems").value(1));

        mockMvc.perform(get("/api/orders/page")
                        .param("q", "renamed widget")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0));

        mockMvc.perform(get("/api/orders/page")
                        .param("page", "0")
                        .param("size", "101")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void supportsPartialAndFullReturnsAndRemovesHardDeletion() throws Exception {
        String adminToken = login();
        String itemBody = """
                {"title":"Widget","description":"Test item","quantity":5,
                 "cost":10.5,"profit":2.0,"barcode":"ABC-1"}
                """;
        String itemResponse = mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(12.5))
                .andReturn().getResponse().getContentAsString();
        long itemId = objectMapper.readTree(itemResponse).get("id").asLong();

        String orderResponse = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Test sale","items":[{"item_id":%d,"quantity":2}]}
                                """.formatted(itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total_quantity").value(2))
                .andExpect(jsonPath("$.total_amount").value(25.0))
                .andExpect(jsonPath("$.returned_quantity").value(0))
                .andExpect(jsonPath("$.net_amount").value(25.0))
                .andExpect(jsonPath("$.total_profit").value(4.0))
                .andExpect(jsonPath("$.returned_profit").value(0.0))
                .andExpect(jsonPath("$.net_profit").value(4.0))
                .andExpect(jsonPath("$.total_cost").value(21.0))
                .andExpect(jsonPath("$.returned_cost").value(0.0))
                .andExpect(jsonPath("$.net_cost").value(21.0))
                .andExpect(jsonPath("$.items[0].cost").value(10.5))
                .andExpect(jsonPath("$.items[0].cost_subtotal").value(21.0))
                .andExpect(jsonPath("$.items[0].profit").value(2.0))
                .andExpect(jsonPath("$.items[0].profit_subtotal").value(4.0))
                .andExpect(jsonPath("$.return_status").value("NOT_RETURNED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode order = objectMapper.readTree(orderResponse);
        long orderId = order.get("id").asLong();
        long orderItemId = order.get("items").get(0).get("id").asLong();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM items WHERE id = ?", Integer.class, itemId)).isEqualTo(3);

        mockMvc.perform(post("/api/orders/{id}/returns", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Damaged","items":[{"order_item_id":%d,"quantity":1}]}
                                """.formatted(orderItemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.returned_quantity").value(1))
                .andExpect(jsonPath("$.returned_amount").value(12.5))
                .andExpect(jsonPath("$.net_quantity").value(1))
                .andExpect(jsonPath("$.net_amount").value(12.5))
                .andExpect(jsonPath("$.total_profit").value(4.0))
                .andExpect(jsonPath("$.returned_profit").value(2.0))
                .andExpect(jsonPath("$.net_profit").value(2.0))
                .andExpect(jsonPath("$.returned_cost").value(10.5))
                .andExpect(jsonPath("$.net_cost").value(10.5))
                .andExpect(jsonPath("$.return_status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.items[0].remaining_quantity").value(1))
                .andExpect(jsonPath("$.returns[0].reason").value("Damaged"))
                .andExpect(jsonPath("$.returns[0].items[0].refund_amount").value(12.5));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM items WHERE id = ?", Integer.class, itemId)).isEqualTo(4);

        mockMvc.perform(post("/api/orders/{id}/archive", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/orders/{id}/returns", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"order_item_id":%d,"quantity":2}]}
                                """.formatted(orderItemId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/orders/{id}/returns", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"order_item_id":999999,"quantity":1}]}
                                """))
                .andExpect(status().isBadRequest());

        jdbcTemplate.update(
                "INSERT INTO users (username, password, role, created_by) VALUES (?, ?, ?, ?)",
                "cashier-return",
                new BCryptPasswordEncoder().encode("cashier-secret"),
                "CASHIER",
                "test");
        String cashierResponse = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"cashier-return\",\"password\":\"cashier-secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String cashierToken = objectMapper.readTree(cashierResponse).get("token").asText();

        mockMvc.perform(post("/api/orders/{id}/returns", orderId)
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"full\":true}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/orders/{id}/returns", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"full\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.returned_quantity").value(2))
                .andExpect(jsonPath("$.returned_amount").value(25.0))
                .andExpect(jsonPath("$.net_quantity").value(0))
                .andExpect(jsonPath("$.net_amount").value(0.0))
                .andExpect(jsonPath("$.total_profit").value(4.0))
                .andExpect(jsonPath("$.returned_profit").value(4.0))
                .andExpect(jsonPath("$.net_profit").value(0.0))
                .andExpect(jsonPath("$.total_cost").value(21.0))
                .andExpect(jsonPath("$.returned_cost").value(21.0))
                .andExpect(jsonPath("$.net_cost").value(0.0))
                .andExpect(jsonPath("$.return_status").value("FULLY_RETURNED"))
                .andExpect(jsonPath("$.returns.length()").value(2));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM items WHERE id = ?", Integer.class, itemId)).isEqualTo(5);

        mockMvc.perform(post("/api/orders/{id}/returns", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"full\":true}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/orders/{id}/archive", orderId)
                        .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/orders/{id}/archive", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived_at").isNotEmpty())
                .andExpect(jsonPath("$.archived_by").value("admin"));

        mockMvc.perform(post("/api/orders/{id}/archive", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/orders/page")
                        .param("page", "0")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(0));

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived_at").isNotEmpty());

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/orders/archived/page")
                        .param("page", "0")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/orders/cancelled/page")
                        .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/orders/archived/page")
                        .param("page", "0")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(orderId))
                .andExpect(jsonPath("$.totalItems").value(1));

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(delete("/api/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void supportsDraftItemEditingConfirmationAndCancellation() throws Exception {
        String adminToken = login();
        long firstItemId = objectMapper.readTree(mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Draft Widget","description":"","quantity":5,
                                 "cost":10.0,"profit":2.0,"barcode":"DRAFT-1"}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long secondItemId = objectMapper.readTree(mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Extra Widget","description":"","quantity":3,
                                 "cost":4.0,"profit":1.0,"barcode":"DRAFT-2"}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        JsonNode draft = objectMapper.readTree(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Editable sale","status":"DRAFT","order_date":"2030-02-03",
                                 "items":[{"item_id":%d,"quantity":2}]}
                                """.formatted(firstItemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.port").doesNotExist())
                .andExpect(jsonPath("$.order_date").value("2030-02-03"))
                .andExpect(jsonPath("$.confirmed_at").doesNotExist())
                .andReturn().getResponse().getContentAsString());
        long orderId = draft.get("id").asLong();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM items WHERE id = ?", Integer.class, firstItemId)).isEqualTo(3);

        mockMvc.perform(put("/api/orders/{id}/text", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/orders/{id}/text", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  Updated editable sale  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Updated editable sale"))
                .andExpect(jsonPath("$.modified_by").value("admin"))
                .andExpect(jsonPath("$.modified_at").isNotEmpty());

        JsonNode withExtra = objectMapper.readTree(mockMvc.perform(post("/api/orders/{id}/items", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"item_id":%d,"quantity":1}]}
                                """.formatted(secondItemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total_quantity").value(3))
                .andReturn().getResponse().getContentAsString());
        long extraLineId = withExtra.get("items").get(1).get("id").asLong();

        mockMvc.perform(delete("/api/orders/{id}/items/{lineId}", orderId, extraLineId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.removed_items[0].removed_by").value("admin"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM items WHERE id = ?", Integer.class, secondItemId)).isEqualTo(3);

        long remainingLineId = draft.get("items").get(0).get("id").asLong();
        mockMvc.perform(delete("/api/orders/{id}/items/{lineId}", orderId, remainingLineId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());


        mockMvc.perform(put("/api/orders/{id}/date", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_date\":\"2026-02-30\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/orders/{id}/date", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_date\":\"2025-12-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order_date").value("2025-12-31"))
                .andExpect(jsonPath("$.modified_by").value("admin"));

        mockMvc.perform(post("/api/orders/{id}/confirm", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.port").doesNotExist())
                .andExpect(jsonPath("$.confirmed_by").value("admin"))
                .andExpect(jsonPath("$.picked_up").value(false));
        mockMvc.perform(put("/api/orders/{id}/text", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Finalized overwrite\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_DRAFT"));
        mockMvc.perform(put("/api/orders/{id}/date", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_date\":\"2026-01-01\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_DRAFT"));
        mockMvc.perform(post("/api/orders/{id}/items", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"item_id":%d,"quantity":1}]}
                                """.formatted(secondItemId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_DRAFT"));

        long cancellableId = objectMapper.readTree(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Cancel me","status":"DRAFT",
                                 "items":[{"item_id":%d,"quantity":1}]}
                                """.formatted(secondItemId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(post("/api/orders/{id}/cancel", cancellableId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelled_by").value("admin"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM items WHERE id = ?", Integer.class, secondItemId)).isEqualTo(3);
        mockMvc.perform(get("/api/orders/cancelled/page")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(cancellableId));
    }

    @Test
    void administratorsCanPickUpConfirmedOrdersAndAtomicallyConfirmDrafts() throws Exception {
        String adminToken = login();
        jdbcTemplate.update("""
                INSERT INTO orders (text, status, confirmed_at, confirmed_by, created_by)
                VALUES ('Pickup sale', 'CONFIRMED', CURRENT_TIMESTAMP, 'admin', 'admin')
                """);
        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE text = 'Pickup sale'", Long.class);

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picked_up").value(false))
                .andExpect(jsonPath("$.picked_up_at").doesNotExist())
                .andExpect(jsonPath("$.modified_by").doesNotExist());

        mockMvc.perform(put("/api/orders/{id}/pickup", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"picked_up\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picked_up").value(true))
                .andExpect(jsonPath("$.picked_up_at").isNotEmpty())
                .andExpect(jsonPath("$.modified_at").isNotEmpty())
                .andExpect(jsonPath("$.modified_by").value("admin"));

        jdbcTemplate.update("UPDATE orders SET picked_up_at = '2001-02-03 04:05:06' WHERE id = ?", orderId);
        mockMvc.perform(put("/api/orders/{id}/pickup", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"picked_up\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picked_up_at").value("2001-02-03T04:05:06Z"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT picked_up_at FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("2001-02-03 04:05:06");

        jdbcTemplate.update("UPDATE orders SET modified_by = 'someone-else' WHERE id = ?", orderId);
        mockMvc.perform(put("/api/orders/{id}/pickup", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"picked_up\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picked_up").value(false))
                .andExpect(jsonPath("$.picked_up_at").doesNotExist())
                .andExpect(jsonPath("$.modified_by").value("admin"));

        mockMvc.perform(put("/api/orders/{id}/pickup", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        jdbcTemplate.update(
                "INSERT INTO users (username, password, role, created_by) VALUES (?, ?, 'CASHIER', 'test')",
                "pickup-cashier", new BCryptPasswordEncoder().encode("cashier-secret"));
        String cashierResponse = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pickup-cashier\",\"password\":\"cashier-secret\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String cashierToken = objectMapper.readTree(cashierResponse).get("token").asText();
        mockMvc.perform(put("/api/orders/{id}/pickup", orderId)
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"picked_up\":true}"))
                .andExpect(status().isForbidden());

        jdbcTemplate.update("""
                INSERT INTO orders (text, status, created_by)
                VALUES ('Pickup draft', 'DRAFT', 'admin')
                """);
        long draftId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE text = 'Pickup draft'", Long.class);
        mockMvc.perform(put("/api/orders/{id}/pickup", draftId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"picked_up\":true}"))
                .andExpect(status().isConflict());

        JsonNode item = objectMapper.readTree(mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Pickup Widget","quantity":2,"cost":10,"profit":2}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        JsonNode validDraft = objectMapper.readTree(mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Valid pickup draft","status":"DRAFT",
                                 "items":[{"item_id":%d,"quantity":1}]}
                                """.formatted(item.get("id").asLong())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        long validDraftId = validDraft.get("id").asLong();
        mockMvc.perform(put("/api/orders/{id}/pickup", validDraftId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"picked_up\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmed_by").value("admin"))
                .andExpect(jsonPath("$.confirmed_at").isNotEmpty())
                .andExpect(jsonPath("$.picked_up").value(true))
                .andExpect(jsonPath("$.picked_up_at").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT confirmed_at = picked_up_at FROM orders WHERE id = ?
                """, Integer.class, validDraftId)).isEqualTo(1);

        mockMvc.perform(put("/api/orders/{id}/pickup", validDraftId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"picked_up\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.picked_up").value(false))
                .andExpect(jsonPath("$.picked_up_at").doesNotExist());

        jdbcTemplate.update("UPDATE orders SET archived_at = CURRENT_TIMESTAMP, archived_by = 'admin' WHERE id = ?", orderId);
        mockMvc.perform(put("/api/orders/{id}/pickup", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"picked_up\":true}"))
                .andExpect(status().isConflict());
    }

    private String login() throws Exception {
        String response = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.get("token").asText();
    }
}
