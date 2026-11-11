package com.example.inventory.config;

import com.example.inventory.repository.TokenRepository;
import com.example.inventory.repository.UserRepository;
import com.example.inventory.support.Messages;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        SecretKeySpec key = new SecretKeySpec(properties.signingKey(), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(
            AuthProperties properties,
            UserRepository users,
            TokenRepository tokens) {
        SecretKeySpec key = new SecretKeySpec(properties.signingKey(), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(properties.getAudience())
                ? OAuth2TokenValidatorResult.success()
                : failure("The token audience is invalid");
        OAuth2TokenValidator<Jwt> issuedAt = jwt -> jwt.getIssuedAt() != null
                && jwt.getExpiresAt() != null
                && !jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(30))
                && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                ? OAuth2TokenValidatorResult.success()
                : failure("The token timestamps are invalid");
        OAuth2TokenValidator<Jwt> account = jwt -> validateAccount(jwt, users, tokens);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuer()),
                audience,
                issuedAt,
                account));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthProperties properties,
            CorsConfigurationSource corsConfigurationSource,
            ObjectMapper objectMapper,
            Messages messages) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie
                .sameSite("Strict")
                .secure(properties.isCookieSecure())
                .path("/"));

        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .requireCsrfProtectionMatcher(request -> {
                            String path = request.getRequestURI();
                            String method = request.getMethod();
                            return path.startsWith("/api/auth/session")
                                    && !List.of("GET", "HEAD", "OPTIONS", "TRACE").contains(method);
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/session",
                                "/api/auth/session/refresh",
                                "/api/auth/token",
                                "/api/auth/token/refresh",
                                "/api/auth/token/revoke",
                                "/api/login").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/auth/session").permitAll()
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET,
                                "/api/dashboard/**",
                                "/api/stock-movements",
                                "/api/orders/archived/page",
                                "/api/orders/cancelled/page").hasAnyRole("ADMIN", "AUDITOR")
                        .requestMatchers(HttpMethod.PUT, "/api/auth/password").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/items/**",
                                "/api/orders/**").hasAnyRole("ADMIN", "CASHIER", "AUDITOR")
                        .requestMatchers(HttpMethod.GET,
                                "/api/receipt-templates/**").hasAnyRole("ADMIN", "CASHIER")
                        .requestMatchers(HttpMethod.POST, "/api/items", "/api/orders").hasAnyRole("ADMIN", "CASHIER")
                        .requestMatchers(HttpMethod.PUT, "/api/items/**").hasAnyRole("ADMIN", "CASHIER")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/items").hasAnyRole("ADMIN", "CASHIER")
                        .requestMatchers(HttpMethod.DELETE, "/api/orders/*/items/*").hasAnyRole("ADMIN", "CASHIER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/orders/*/archive").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT,
                                "/api/orders/*/pickup").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, objectMapper, messages.get(request, "api.auth.invalid"),
                                        HttpServletResponse.SC_UNAUTHORIZED, null)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, objectMapper, messages.get(request, "api.auth.required"),
                                        HttpServletResponse.SC_UNAUTHORIZED, null))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                writeError(
                                        response,
                                        objectMapper,
                                        messages.get(request, "api.auth.csrfInvalid"),
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "CSRF_INVALID");
                                return;
                            }
                            writeError(response, objectMapper, messages.get(request, "api.auth.forbidden"),
                                    HttpServletResponse.SC_FORBIDDEN, null);
                        }))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
                                        + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'"))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .preload(true)
                                .maxAgeInSeconds(31_536_000)));
        if (properties.isRequireHttps()) {
            http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        }
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!properties.allowedOriginList().isEmpty()) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(properties.allowedOriginList());
            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            configuration.setAllowedHeaders(List.of(
                    HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-XSRF-TOKEN", "X-Locale"));
            configuration.setAllowCredentials(true);
            configuration.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", configuration);
        }
        return source;
    }

    private org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
    jwtAuthenticationConverter() {
        var authorities = new org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName("username");
        return converter;
    }

    private OAuth2TokenValidatorResult validateAccount(
            Jwt jwt,
            UserRepository users,
            TokenRepository tokens) {
        try {
            long userId = Long.parseLong(jwt.getSubject());
            Number version = jwt.getClaim("ver");
            String username = jwt.getClaimAsString("username");
            String role = jwt.getClaimAsString("role");
            String jti = jwt.getId();
            var account = users.findById(userId).orElse(null);
            if (account == null || version == null || jti == null) {
                return failure("The token account is invalid");
            }
            boolean matches = username.equals(account.username())
                    && role.equals(account.role())
                    && version.intValue() == account.authVersion();
            boolean revoked = tokens.isAccessTokenRevoked(jti, Instant.now().getEpochSecond());
            return account.active() && matches && !revoked
                    ? OAuth2TokenValidatorResult.success()
                    : failure("The token account is invalid");
        } catch (RuntimeException exception) {
            return failure("The token account is invalid");
        }
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }

    private static void writeError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            String message,
            int status,
            String code) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        Map<String, String> body = code == null
                ? Map.of("error", message)
                : Map.of("code", code, "error", message);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
