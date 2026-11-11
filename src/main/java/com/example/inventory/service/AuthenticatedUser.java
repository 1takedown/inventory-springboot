package com.example.inventory.service;

public record AuthenticatedUser(
        long id,
        String username,
        String passwordHash,
        String role,
        boolean active,
        int authVersion) {
}
