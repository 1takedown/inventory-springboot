package com.example.inventory.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserPermissionsTest {

    @Test
    void onlyAdministratorsAndCashiersCanViewPrices() {
        for (String role : new String[]{"ADMIN", "CASHIER"}) {
            assertThat(UserPermissions.canViewPrices(role)).as("role %s", role).isTrue();
        }
        for (String role : new String[]{"AUDITOR", "GUEST", "admin", "", null}) {
            assertThat(UserPermissions.canViewPrices(role)).as("role %s", role).isFalse();
        }
    }

    @Test
    void onlyAdministratorsAndAuditorsCanViewArchivedAndCancelledOrders() {
        for (String role : new String[]{"ADMIN", "AUDITOR"}) {
            assertThat(UserPermissions.canViewAllOrders(role)).as("role %s", role).isTrue();
        }
        for (String role : new String[]{"CASHIER", "GUEST", "auditor", "", null}) {
            assertThat(UserPermissions.canViewAllOrders(role)).as("role %s", role).isFalse();
        }
    }
}
