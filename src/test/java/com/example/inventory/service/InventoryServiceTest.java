package com.example.inventory.service;

import com.example.inventory.repository.ItemRepository;
import com.example.inventory.repository.StockMovementRepository;
import com.example.inventory.repository.ItemRepository.Item;
import com.example.inventory.repository.OrderRepository;
import com.example.inventory.support.ApiException;
import com.example.inventory.support.Messages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryServiceTest {

    private final ItemRepository items = mock(ItemRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final InventoryService service = new InventoryService(items, orders, mock(Messages.class), mock(StockMovementRepository.class));

    @Test
    void mergesPatchWithPersistedItemBeforeUpdating() {
        Item before = item(4, 1000L, 200L);
        Item after = item(7, 1000L, 200L, 1);
        when(items.findById(1)).thenReturn(Optional.of(before), Optional.of(after));
        when(items.update(1, "Widget", "Description", 7, 1000L, 200L, "ABC-1", "admin", 0))
                .thenReturn(1);

        service.updateItem(1, Map.of("quantity", 7, "version", 0), "admin");

        verify(items).update(
                1,
                "Widget",
                "Description",
                7,
                1000L,
                200L,
                "ABC-1",
                "admin",
                0);
    }

    @Test
    void mergesDuplicateOrderLinesBeforeWriting() {
        Item item = item(10, 1000L, 200L);
        when(orders.insert(eq("Sale"), anyString(), eq("CONFIRMED"), eq("cashier")))
                .thenReturn(9L);
        when(items.findById(1)).thenReturn(Optional.of(item));
        when(items.decrementStock(1, 5, "cashier")).thenReturn(1);
        when(orders.findById(9)).thenReturn(Optional.of(new OrderRepository.OrderSummary(
                9, "Sale", "now", "cashier", null, null, null, null, 5, 6000L, 0, 0L,
                1000L, 0L, 1000L, 5000L, 0L, 5000L)));
        when(orders.findLines(9)).thenReturn(List.of());
        when(orders.findReturns(9)).thenReturn(List.of());
        when(orders.findReturnLines(9)).thenReturn(List.of());

        service.createOrder(
                "Sale",
                List.of(Map.of("item_id", 1, "quantity", 2), Map.of("item_id", 1, "quantity", 3)),
                "CONFIRMED",
                "cashier",
                "en");

        verify(orders).insertLine(9, 1, "Widget", "ABC-1", 5, 1200L, 1000L, 200L, "cashier");
        verify(items).decrementStock(1, 5, "cashier");
    }

    @Test
    void rejectsOrderWhenConditionalStockUpdateLosesRace() {
        Item item = item(5, 1000L, 200L);
        when(orders.insert(eq("Sale"), anyString(), eq("CONFIRMED"), eq("cashier")))
                .thenReturn(9L);
        when(items.findById(1)).thenReturn(Optional.of(item));
        when(items.decrementStock(1, 5, "cashier")).thenReturn(0);

        assertThatThrownBy(() -> service.createOrder(
                "Sale",
                List.of(Map.of("item_id", 1, "quantity", 5)),
                "CONFIRMED",
                "cashier",
                "en"))
                .isInstanceOf(ApiException.class);
    }


    @Test
    void confirmsADraftWithItems() {
        when(orders.findById(9)).thenReturn(Optional.of(draftSummary()));
        when(orders.countActiveLines(9)).thenReturn(1);
        when(orders.confirmDraft(9, "admin")).thenReturn(1);

        service.confirmOrder(9, "admin", "en");

        verify(orders).confirmDraft(9, "admin");
    }

    @Test
    void mergesDuplicatePartialReturnLinesAndRestoresStock() {
        var before = new OrderRepository.OrderSummary(
                9, "Sale", "now", "cashier", null, null, null, null, 3, 3600L, 0, 0L,
                600L, 0L, 600L, 3000L, 0L, 3000L);
        var after = new OrderRepository.OrderSummary(
                9, "Sale", "now", "cashier", null, null, null, null, 3, 3600L, 2, 2400L,
                600L, 400L, 200L, 3000L, 2000L, 1000L);
        var line = new OrderRepository.OrderLine(
                7, 1, "Widget", "ABC-1", 3, 0, 3, 1200L, 3600L, 1000L, 3000L, 200L, 600L);
        when(orders.findById(9)).thenReturn(Optional.of(before), Optional.of(after));
        when(orders.findLines(9)).thenReturn(List.of(line), List.of(
                new OrderRepository.OrderLine(
                        7, 1, "Widget", "ABC-1", 3, 2, 1, 1200L, 3600L,
                        1000L, 3000L, 200L, 600L)));
        when(orders.insertReturn(9, "Damaged", "cashier")).thenReturn(4L);
        when(orders.addReturnedQuantity(9, 7, 2, "cashier")).thenReturn(1);
        when(items.incrementStock(1, 2, "cashier")).thenReturn(1);
        when(items.findById(1)).thenReturn(Optional.of(item(2, 1000L, 200L)));
        when(orders.findReturns(9)).thenReturn(List.of());
        when(orders.findReturnLines(9)).thenReturn(List.of());

        service.returnOrder(
                9,
                false,
                List.of(
                        Map.of("order_item_id", 7, "quantity", 1),
                        Map.of("order_item_id", 7, "quantity", 1)),
                "Damaged",
                "cashier",
                "en");

        verify(orders).addReturnedQuantity(9, 7, 2, "cashier");
        verify(orders).insertReturnLine(4, 7, 2, 2400L);
        verify(items).incrementStock(1, 2, "cashier");
    }



    @Test
    void rejectsArchiveWhenConditionalUpdateLosesRace() {
        when(orders.findById(9)).thenReturn(Optional.of(orderSummary(1, null)));
        when(orders.archiveIfActive(9, "admin")).thenReturn(0);
        assertThatThrownBy(() -> service.archiveOrder(9, "admin", "en"))
                .isInstanceOf(ApiException.class);
    }

    private OrderRepository.OrderSummary orderSummary(int returnedQuantity, String archivedAt) {
        return new OrderRepository.OrderSummary(
                9, "Sale", "now", "cashier", null, null, archivedAt,
                archivedAt == null ? null : "admin", 1, 1200L, returnedQuantity,
                returnedQuantity * 1200L, 200L, returnedQuantity * 200L,
                (1 - returnedQuantity) * 200L, 1000L, returnedQuantity * 1000L,
                (1 - returnedQuantity) * 1000L);
    }

    private OrderRepository.OrderSummary draftSummary() {
        return new OrderRepository.OrderSummary(
                9, "Sale", "DRAFT", "now", "cashier", null, null,
                null, null, null, null, null, null, 1, 1200L, 0, 0L,
                200L, 0L, 200L, 1000L, 0L, 1000L);
    }

    private Item item(int quantity, long cost, long profit) {
        return item(quantity, cost, profit, 0);
    }

    private Item item(int quantity, long cost, long profit, int version) {
        return new Item(
                1,
                "Widget",
                "Description",
                quantity,
                cost,
                profit,
                "ABC-1",
                "now",
                "admin",
                null,
                null,
                version,
                cost + profit);
    }
}
