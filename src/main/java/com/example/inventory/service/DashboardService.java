package com.example.inventory.service;

import com.example.inventory.repository.OrderRepository;
import com.example.inventory.repository.OrderRepository.DailyOutItem;
import com.example.inventory.repository.OrderRepository.DailyOutOrder;
import com.example.inventory.support.DatabaseTimestamps;
import com.example.inventory.support.MoneyValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final OrderRepository orders;
    private final Clock clock;

    @Autowired
    public DashboardService(OrderRepository orders) {
        this(orders, Clock.systemUTC());
    }

    DashboardService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    public Map<String, Object> dailyOutItems(LocalDate requestedDate, boolean pricesVisible) {
        LocalDate date = requestedDate == null
                ? LocalDate.now(clock.withZone(BUSINESS_ZONE))
                : requestedDate;
        List<DailyOutItem> rows = orders.findDailyOutItemsOnDate(date.toString());

        int grossQuantity = 0;
        int restoredQuantity = 0;
        int netQuantity = 0;
        long netAmount = 0;
        long netCost = 0;
        long netProfit = 0;
        boolean costKnown = true;
        boolean profitKnown = true;
        for (DailyOutItem row : rows) {
            grossQuantity = Math.addExact(grossQuantity, row.grossQuantity());
            restoredQuantity = Math.addExact(restoredQuantity, row.restoredQuantity());
            netQuantity = Math.addExact(netQuantity, row.netQuantity());
            netAmount = MoneyValues.add(netAmount, row.netAmount());
            if (row.netCost() == null) {
                costKnown = false;
            } else {
                netCost = MoneyValues.add(netCost, row.netCost());
            }
            if (row.netProfit() == null) {
                profitKnown = false;
            } else {
                netProfit = MoneyValues.add(netProfit, row.netProfit());
            }
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("gross_quantity", grossQuantity);
        totals.put("restored_quantity", restoredQuantity);
        totals.put("net_quantity", netQuantity);
        if (pricesVisible) {
            totals.put("net_amount", MoneyValues.amount(netAmount));
            totals.put("net_cost", costKnown ? MoneyValues.amount(netCost) : null);
            totals.put("net_profit", profitKnown ? MoneyValues.amount(netProfit) : null);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("time_zone", BUSINESS_ZONE.getId());
        result.put("totals", totals);
        result.put("items", rows.stream().map(item -> itemMap(item, pricesVisible)).toList());
        return result;
    }

    public Map<String, Object> dailyOutOrders(
            long itemId,
            LocalDate requestedDate) {
        LocalDate date = requestedDate == null
                ? LocalDate.now(clock.withZone(BUSINESS_ZONE))
                : requestedDate;
        List<DailyOutOrder> rows = orders.findDailyOutOrdersForItemOnDate(itemId, date.toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("time_zone", BUSINESS_ZONE.getId());
        result.put("item_id", itemId);
        result.put("orders", rows.stream().map(this::orderMap).toList());
        return result;
    }

    private Map<String, Object> itemMap(DailyOutItem item, boolean pricesVisible) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("item_id", item.itemId());
        result.put("title", item.title());
        result.put("barcode", item.barcode());
        result.put("gross_quantity", item.grossQuantity());
        result.put("restored_quantity", item.restoredQuantity());
        result.put("net_quantity", item.netQuantity());
        if (pricesVisible) {
            result.put("net_amount", MoneyValues.amount(item.netAmount()));
            result.put("net_cost", MoneyValues.amount(item.netCost()));
            result.put("net_profit", MoneyValues.amount(item.netProfit()));
        }
        return result;
    }

    private Map<String, Object> orderMap(DailyOutOrder order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", order.id());
        result.put("text", order.text());
        result.put("status", order.status());
        result.put("archived_at", DatabaseTimestamps.utcIso(order.archivedAt()));
        result.put("gross_quantity", order.grossQuantity());
        result.put("restored_quantity", order.restoredQuantity());
        result.put("net_quantity", order.netQuantity());
        return result;
    }
}
