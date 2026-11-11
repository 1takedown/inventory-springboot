package com.example.inventory.controller;

import com.example.inventory.service.DashboardService;
import com.example.inventory.support.ApiException;
import com.example.inventory.support.RequestValues;
import com.example.inventory.support.UserPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/daily-out-items")
    public Map<String, Object> dailyOutItems(
            @RequestParam(required = false) LocalDate date,
            @AuthenticationPrincipal Jwt jwt) {
        return dashboardService.dailyOutItems(date, UserPermissions.canViewPrices(jwt.getClaimAsString("role")));
    }

    @GetMapping("/daily-out-items/{itemId}/orders")
    public Map<String, Object> dailyOutOrders(
            @PathVariable String itemId,
            @RequestParam(required = false) LocalDate date) {
        Long parsedItemId = RequestValues.positiveId(itemId);
        if (parsedItemId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.invalidId");
        }
        return dashboardService.dailyOutOrders(
                parsedItemId,
                date);
    }
}
