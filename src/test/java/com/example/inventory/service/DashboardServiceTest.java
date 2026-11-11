package com.example.inventory.service;

import com.example.inventory.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    @Test
    void usesTheAsiaShanghaiDayAndBuildsDashboardTotals() {
        OrderRepository orders = mock(OrderRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-25T18:00:00Z"), ZoneOffset.UTC);
        when(orders.findDailyOutItemsOnDate("2026-07-26")).thenReturn(List.of(
                new OrderRepository.DailyOutItem(
                        1, "Widget", "ABC", 5, 1, 4, 4800L, 4000L, 800L),
                new OrderRepository.DailyOutItem(
                        2, "Cable", null, 3, 3, 0, 0L, 0L, 0L)));

        Map<String, Object> result = new DashboardService(orders, clock).dailyOutItems(null, true);

        verify(orders).findDailyOutItemsOnDate("2026-07-26");
        assertThat(result).containsEntry("date", "2026-07-26")
                .containsEntry("time_zone", "Asia/Shanghai");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.get("totals");
        assertThat(totals)
                .containsEntry("gross_quantity", 8)
                .containsEntry("restored_quantity", 4)
                .containsEntry("net_quantity", 4)
                .containsEntry("net_amount", new java.math.BigDecimal("48.00"))
                .containsEntry("net_cost", new java.math.BigDecimal("40.00"))
                .containsEntry("net_profit", new java.math.BigDecimal("8.00"));
        assertThat((List<?>) result.get("items")).hasSize(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mappedItems = (List<Map<String, Object>>) result.get("items");
        assertThat(mappedItems).extracting(item -> item.get("item_id"))
                .containsExactly(1L, 2L);
        assertThat(mappedItems).allSatisfy(item -> assertThat(item).doesNotContainKey("port"));
    }

    @Test
    void usesAnExplicitSelectedDateInsteadOfToday() {
        OrderRepository orders = mock(OrderRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T03:00:00Z"), ZoneOffset.UTC);
        when(orders.findDailyOutItemsOnDate("2026-07-25")).thenReturn(List.of());

        Map<String, Object> result = new DashboardService(orders, clock)
                .dailyOutItems(LocalDate.parse("2026-07-25"), true);

        verify(orders).findDailyOutItemsOnDate("2026-07-25");
        assertThat(result).containsEntry("date", "2026-07-25")
                .containsEntry("time_zone", "Asia/Shanghai");
    }

    @Test
    void omitsMonetaryTotalsAndItemFieldsWhenPricesAreHidden() {
        OrderRepository orders = mock(OrderRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T03:00:00Z"), ZoneOffset.UTC);
        when(orders.findDailyOutItemsOnDate("2026-07-25")).thenReturn(List.of(
                new OrderRepository.DailyOutItem(
                        1, "Widget", "ABC", 5, 1, 4, 4800L, 4000L, 800L)));

        Map<String, Object> result = new DashboardService(orders, clock)
                .dailyOutItems(LocalDate.parse("2026-07-25"), false);

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.get("totals");
        assertThat(totals)
                .containsEntry("gross_quantity", 5)
                .containsEntry("restored_quantity", 1)
                .containsEntry("net_quantity", 4)
                .doesNotContainKeys("net_amount", "net_cost", "net_profit");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mappedItems = (List<Map<String, Object>>) result.get("items");
        assertThat(mappedItems).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("title", "Widget")
                .containsEntry("net_quantity", 4)
                .doesNotContainKeys("net_amount", "net_cost", "net_profit"));
    }

    @Test
    void returnsOrderContributionsForAnItemOnTheSelectedDay() {
        OrderRepository orders = mock(OrderRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T03:00:00Z"), ZoneOffset.UTC);
        when(orders.findDailyOutOrdersForItemOnDate(
                7,
                "2026-07-25")).thenReturn(List.of(
                new OrderRepository.DailyOutOrder(
                        12, "Cancelled sale", "CANCELLED",
                        null, 3, 3, 0)));

        Map<String, Object> result = new DashboardService(orders, clock)
                .dailyOutOrders(7, LocalDate.parse("2026-07-25"));

        verify(orders).findDailyOutOrdersForItemOnDate(
                7,
                "2026-07-25");
        assertThat(result)
                .containsEntry("date", "2026-07-25")
                .containsEntry("time_zone", "Asia/Shanghai")
                .containsEntry("item_id", 7L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mappedOrders = (List<Map<String, Object>>) result.get("orders");
        assertThat(mappedOrders).hasSize(1);
        assertThat(mappedOrders.get(0))
                .containsEntry("id", 12L)
                .containsEntry("status", "CANCELLED")
                .containsEntry("gross_quantity", 3)
                .containsEntry("restored_quantity", 3)
                .containsEntry("net_quantity", 0);
        assertThat(mappedOrders.get(0)).doesNotContainKey("created_at");
    }

    @Test
    void returnsRelatedOrdersInRepositoryOrder() {
        OrderRepository orders = mock(OrderRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T03:00:00Z"), ZoneOffset.UTC);
        when(orders.findDailyOutOrdersForItemOnDate(7, "2026-07-25")).thenReturn(List.of(
                new OrderRepository.DailyOutOrder(
                        13, "Second sale", "CONFIRMED",
                        null, 2, 0, 2),
                new OrderRepository.DailyOutOrder(
                        12, "First sale", "CONFIRMED",
                        null, 1, 0, 1)));

        Map<String, Object> result = new DashboardService(orders, clock)
                .dailyOutOrders(7, LocalDate.parse("2026-07-25"));

        verify(orders).findDailyOutOrdersForItemOnDate(7, "2026-07-25");
        assertThat(result).doesNotContainKey("port");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mappedOrders = (List<Map<String, Object>>) result.get("orders");
        assertThat(mappedOrders).extracting(order -> order.get("id"))
                .containsExactly(13L, 12L);
        assertThat(mappedOrders).allSatisfy(order -> assertThat(order).doesNotContainKey("port"));
    }
}
