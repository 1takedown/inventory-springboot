package com.example.inventory.config;

import com.example.inventory.repository.UserRepository;
import com.example.inventory.service.UserAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository userRepository;
    private final UserAccountService users;
    private final String username;
    private final String password;
    private final boolean reset;

    public AdminBootstrapRunner(
            UserRepository userRepository,
            UserAccountService users,
            @Value("${app.bootstrap.admin-username:}") String username,
            @Value("${app.bootstrap.admin-password:}") String password,
            @Value("${app.bootstrap.reset-admin:false}") boolean reset) {
        this.userRepository = userRepository;
        this.users = users;
        this.username = username.trim();
        this.password = password;
        this.reset = reset;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        int count = userRepository.countUsers();
        if (username.isEmpty() || password.isEmpty()) {
            if (count == 0) {
                LOGGER.warn("No users exist. Set BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD once.");
            }
            return;
        }
        var matching = userRepository.findByUsername(username);
        if (matching.isPresent()) {
            if (reset) {
                users.resetBootstrapAdministrator(matching.get().id(), password);
                LOGGER.info("Reset bootstrap administrator {}", username);
            }
            return;
        }
        if (count > 0) {
            throw new IllegalStateException(
                    "Bootstrap administrator creation is allowed only when the users table is empty");
        }
        users.createUser(username, password, "ADMIN", "bootstrap");
        LOGGER.info("Created bootstrap administrator {}", username);
    }
}
