package com.example.inventory.support;

public final class UserPermissions {

    private UserPermissions() {
    }

    public static boolean canViewPrices(String role) {
        return "ADMIN".equals(role) || "CASHIER".equals(role);
    }

    public static boolean canViewAllOrders(String role) {
        return "ADMIN".equals(role) || "AUDITOR".equals(role);
    }
}
