package com.example.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "app.auth.jwt-secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.bootstrap.admin-username=proxy-admin",
        "app.bootstrap.admin-password=proxy-test-password",
        "app.auth.require-https=true",
        // A leftover legacy setting must not re-enable application-level header parsing.
        "app.auth.trust-forwarded-for=true",
        "server.address=127.0.0.1",
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.remote-ip-header=CF-Connecting-IP",
        "server.tomcat.remoteip.internal-proxies=127[.]0[.]0[.]1",
        "server.tomcat.remoteip.protocol-header=X-Forwarded-Proto",
        "server.tomcat.redirect-context-root=false"
})
class ForwardedClientIpIntegrationTest {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"203.0.113.10", "2001:db8::10"})
    void spoofedHeadersCannotResetLoginThrottling(String clientIp) throws Exception {
        for (int attempt = 0; attempt <= 5; attempt++) {
            var response = post(request("/api/auth/token", clientIp, attempt),
                    Map.of("username", "proxy-admin", "password", "wrong-password"));
            assertThat(response.statusCode()).isEqualTo(attempt < 5 ? 401 : 429);
            if (attempt == 5) {
                assertThat(response.headers().firstValue("Retry-After")).isPresent();
            }
        }
        // Another visitor has an independent bucket even though both use the same tunnel.
        assertThat(post(request("/api/auth/token", "203.0.113.20", 6),
                Map.of("username", "proxy-admin", "password", "wrong-password")).statusCode())
                .isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.30", "2001:db8::30"})
    void loginAndRefreshAuditUseTheVerifiedAddress(String clientIp) throws Exception {
        String agent = "proxy-audit-" + clientIp;
        var login = post(request("/api/auth/token", clientIp, 1).header("User-Agent", agent),
                Map.of("username", "proxy-admin", "password", "proxy-test-password"));
        assertThat(login.statusCode()).isEqualTo(200);
        String refreshToken = objectMapper.readTree(login.body()).get("refreshToken").asText();
        var refresh = post(request("/api/auth/token/refresh", clientIp, 2).header("User-Agent", agent),
                Map.of("refreshToken", refreshToken));
        assertThat(refresh.statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForList(
                "SELECT created_ip FROM auth_sessions WHERE user_agent = ?", String.class, agent))
                .containsExactly(clientIp, clientIp);
    }

    @Test
    void httpsUsesTheTunnelProtocolInsteadOfTheForwardedHeader() throws Exception {
        var secure = client.send(request("/api/auth/csrf", "203.0.113.40", 1).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(secure.statusCode()).isEqualTo(200);
        assertThat(secure.headers().firstValue("Strict-Transport-Security")).isPresent();
        assertThat(secure.headers().allValues("Set-Cookie"))
                .anySatisfy(cookie -> assertThat(cookie).contains("XSRF-TOKEN=", "Secure"));

        var insecure = client.send(request("/api/auth/csrf", "203.0.113.40", 2)
                        .setHeader("X-Forwarded-Proto", "http")
                        .setHeader("Forwarded", "for=198.51.100.2;proto=https;host=attacker.invalid")
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(insecure.statusCode()).isEqualTo(302);
        assertThat(insecure.headers().firstValue("Location").orElseThrow())
                .startsWith("https://127.0.0.1").doesNotContain("attacker.invalid");
    }

    private HttpRequest.Builder request(String path, String clientIp, int attempt) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-For", "198.51.100." + attempt + ", 203.0.113.99")
                .header("Forwarded", "for=198.51.100." + attempt + ";proto=http;host=attacker.invalid");
        if (clientIp != null) {
            request.header("CF-Connecting-IP", clientIp);
        }
        return request;
    }

    private HttpResponse<String> post(HttpRequest.Builder request, Map<String, String> body) throws Exception {
        return client.send(request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
