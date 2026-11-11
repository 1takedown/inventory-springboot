package com.example.inventory.service;

import com.example.inventory.repository.TokenRepository;
import com.example.inventory.repository.UserRepository;
import com.example.inventory.repository.UserRepository.UserAccount;
import com.example.inventory.repository.UserRepository.UserView;
import com.example.inventory.support.ApiException;
import com.example.inventory.support.DatabaseTimestamps;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserAccountService {

    private final UserRepository users;
    private final TokenRepository tokens;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public UserAccountService(
            UserRepository users,
            TokenRepository tokens,
            BCryptPasswordEncoder passwordEncoder) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode("inventory-dummy-password-that-is-never-used");
    }

    public AuthenticatedUser authenticate(String username, String password) {
        UserAccount account = users.findByUsername(username).orElse(null);
        String hash = account == null ? dummyPasswordHash : account.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password, hash);
        if (account == null || !account.active() || !passwordMatches) {
            return null;
        }
        AuthenticatedUser user = authenticated(account);
        if (passwordEncoder.upgradeEncoding(hash)) {
            users.updatePasswordHash(user.id(), passwordEncoder.encode(password), user.username(), false);
        }
        return user;
    }

    public AuthenticatedUser requireActive(long userId) {
        UserAccount account = users.findById(userId).orElse(null);
        if (account == null || !account.active()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "api.auth.invalid");
        }
        return authenticated(account);
    }

    public List<Map<String, Object>> listUsers() {
        return users.findAll().stream().map(this::viewMap).toList();
    }

    @Transactional
    public Map<String, Object> createUser(String username, String password, String role, String actor) {
        validateUsername(username);
        validatePassword(password);
        validateRole(role);
        users.insert(username, passwordEncoder.encode(password), role, actor);
        return viewMap(users.findViewByUsername(username));
    }

    @Transactional
    public void updateUser(long userId, String role, Boolean active, long actorId, String actor) {
        AuthenticatedUser target = requireExisting(userId);
        String nextRole = role == null ? target.role() : role;
        boolean nextActive = active == null ? target.active() : active;
        validateRole(nextRole);
        if (userId == actorId && (!nextActive || !"ADMIN".equals(nextRole))) {
            throw new ApiException(HttpStatus.CONFLICT, "api.user.cannotChangeSelf");
        }
        if ("ADMIN".equals(target.role()) && (!nextActive || !"ADMIN".equals(nextRole))
                && users.countActiveAdministrators() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "api.user.lastAdmin");
        }
        users.updateRoleAndActive(userId, nextRole, nextActive, actor);
        revokeRefreshSessions(userId);
    }

    @Transactional
    public void resetPassword(long userId, String password, String actor) {
        requireExisting(userId);
        validatePassword(password);
        users.updatePasswordHash(userId, passwordEncoder.encode(password), actor, true);
        revokeRefreshSessions(userId);
    }

    @Transactional
    public void resetBootstrapAdministrator(long userId, String password) {
        requireExisting(userId);
        validatePassword(password);
        users.updatePasswordHash(userId, passwordEncoder.encode(password), "bootstrap", true);
        users.activateAdministrator(userId, "bootstrap");
        revokeRefreshSessions(userId);
    }

    @Transactional
    public void changeOwnPassword(long userId, String currentPassword, String newPassword) {
        AuthenticatedUser user = requireExisting(userId);
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.password.invalidCurrent");
        }
        validatePassword(newPassword);
        users.updatePasswordHash(userId, passwordEncoder.encode(newPassword), user.username(), true);
        revokeRefreshSessions(userId);
    }

    public void revokeRefreshSessions(long userId) {
        tokens.revokeUserSessions(userId, Instant.now().getEpochSecond());
    }

    @Transactional
    public void revokeAllSessions(long userId, String actor) {
        requireExisting(userId);
        users.incrementAuthVersion(userId, actor);
        revokeRefreshSessions(userId);
    }

    public void validatePassword(String password) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 12 || bytes > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.password.policy");
        }
    }

    private void validateUsername(String username) {
        if (!username.matches("[A-Za-z0-9._-]{3,64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.user.invalidUsername");
        }
    }

    private void validateRole(String role) {
        if (!List.of("ADMIN", "CASHIER", "AUDITOR").contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.user.invalidRole");
        }
    }

    private AuthenticatedUser requireExisting(long userId) {
        return users.findById(userId)
                .map(this::authenticated)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "api.user.notFound"));
    }

    private AuthenticatedUser authenticated(UserAccount account) {
        return new AuthenticatedUser(
                account.id(),
                account.username(),
                account.passwordHash(),
                account.role(),
                account.active(),
                account.authVersion());
    }

    private Map<String, Object> viewMap(UserView user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.id());
        result.put("username", user.username());
        result.put("role", user.role());
        result.put("active", user.active());
        result.put("auth_version", user.authVersion());
        result.put("created_at", DatabaseTimestamps.utcIso(user.createdAt()));
        result.put("created_by", user.createdBy());
        result.put("modified_at", DatabaseTimestamps.utcIso(user.modifiedAt()));
        result.put("modified_by", user.modifiedBy());
        return result;
    }
}
