package com.example.inventory.controller;

import com.example.inventory.service.UserAccountService;
import com.example.inventory.support.ApiException;
import com.example.inventory.support.RequestValues;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserAccountService users;

    public UserController(UserAccountService users) {
        this.users = users;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return users.listUsers();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> input = body == null ? Map.of() : body;
        String username = RequestValues.text(input.get("username"));
        String password = input.get("password") instanceof String value ? value : "";
        String role = RequestValues.text(input.get("role")).toUpperCase();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(users.createUser(username, password, role, jwt.getClaimAsString("username")));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> update(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> input = body == null ? Map.of() : body;
        String role = input.containsKey("role") ? RequestValues.text(input.get("role")).toUpperCase() : null;
        Boolean active = input.get("active") instanceof Boolean value ? value : null;
        users.updateUser(
                userId(id), role, active, Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("username"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        String password = body != null && body.get("newPassword") instanceof String value ? value : "";
        users.resetPassword(userId(id), password, jwt.getClaimAsString("username"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/revoke-sessions")
    public ResponseEntity<Void> revokeSessions(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt) {
        users.revokeAllSessions(userId(id), jwt.getClaimAsString("username"));
        return ResponseEntity.noContent().build();
    }

    private long userId(String value) {
        Long id = RequestValues.positiveId(value);
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.user.invalidId");
        }
        return id;
    }
}
