package com.example.inventory.service;

import com.example.inventory.repository.ItemRepository;
import com.example.inventory.repository.StockMovementRepository;
import com.example.inventory.repository.ItemRepository.Item;
import com.example.inventory.repository.OrderRepository;
import com.example.inventory.repository.OrderRepository.OrderLine;
import com.example.inventory.repository.OrderRepository.OrderSummary;
import com.example.inventory.repository.OrderRepository.ReturnLine;
import com.example.inventory.repository.OrderRepository.ReturnSummary;
import com.example.inventory.support.ApiException;
import com.example.inventory.support.DatabaseTimestamps;
import com.example.inventory.support.Messages;
import com.example.inventory.support.MoneyValues;
import com.example.inventory.support.RequestValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class InventoryService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ItemRepository items;
    private final OrderRepository orders;
    private final Messages messages;
    private final Clock clock;
    private final StockMovementRepository movements;

    @Autowired
    public InventoryService(ItemRepository items, OrderRepository orders, Messages messages, StockMovementRepository movements) {
        this(items, orders, messages, movements, Clock.systemUTC());
    }

    InventoryService(ItemRepository items, OrderRepository orders, Messages messages, StockMovementRepository movements, Clock clock) {
        this.items = items;
        this.orders = orders;
        this.messages = messages;
        this.clock = clock;
        this.movements = movements;
    }

    public List<Map<String, Object>> listItems(boolean pricesVisible) {
        return items.findAll().stream().map(item -> itemMap(item, pricesVisible)).toList();
    }

    public Map<String, Object> getItem(long id) {
        return items.findById(id).map(this::itemMap).orElse(null);
    }

    public Map<String, Object> getItemByBarcode(String barcode, boolean pricesVisible) {
        return itemMap(items.findByBarcode(barcode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "api.item.notFound")), pricesVisible);
    }

    @Transactional
    public Map<String, Object> createItem(Map<String, Object> body, String actor) {
        String title = RequestValues.text(body.getOrDefault("title", body.get("name")));
        String description = RequestValues.optionalText(body.get("description"));
        Integer quantity = RequestValues.quantity(body.get("quantity"), true);
        Long cost = MoneyValues.cents(body.get("cost"));
        Long profit = MoneyValues.cents(body.get("profit"));
        String barcode = RequestValues.optionalText(body.get("barcode"));
        validateItem(title, quantity, cost, profit);

        long id = items.insert(title, description, quantity, cost, profit, barcode, actor);
        return itemMap(recordMovement(id, quantity, "OPENING_STOCK", actor, null, null, null));
    }

    @Transactional
    public Map<String, Object> updateItem(long id, Map<String, Object> body, String actor) {
        Item existing = items.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "api.item.notFound"));

        Integer expectedVersion = RequestValues.quantity(body.get("version"), true);
        if (expectedVersion == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.versionRequired");
        }
        if (expectedVersion != existing.version()) {
            throw itemChanged();
        }

        if (body.keySet().stream().noneMatch(
                key -> List.of("title", "name", "description", "quantity", "cost", "profit", "barcode")
                        .contains(key))) {
            return itemMap(existing);
        }

        String title = existing.title();
        String description = existing.description();
        int quantity = existing.quantity();
        long cost = existing.cost();
        long profit = existing.profit();
        String barcode = existing.barcode();

        if (body.containsKey("title") || body.containsKey("name")) {
            title = RequestValues.text(body.getOrDefault("title", body.get("name")));
            if (title.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.titleRequired");
            }
        }
        if (body.containsKey("description")) {
            description = RequestValues.optionalText(body.get("description"));
        }
        if (body.containsKey("quantity")) {
            Integer value = RequestValues.quantity(body.get("quantity"), true);
            if (value == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.quantityInvalid");
            }
            quantity = value;
        }
        if (body.containsKey("cost")) {
            Long value = MoneyValues.cents(body.get("cost"));
            if (value == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.costInvalid");
            }
            cost = value;
        }
        if (body.containsKey("profit")) {
            Long value = MoneyValues.cents(body.get("profit"));
            if (value == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.profitInvalid");
            }
            profit = value;
        }
        if (body.containsKey("barcode")) {
            barcode = RequestValues.optionalText(body.get("barcode"));
        }

        validateMoneySum(cost, profit);
        if (items.update(id, title, description, quantity, cost, profit, barcode, actor, expectedVersion) != 1) {
            throw itemChanged();
        }
        if (quantity != existing.quantity()) {
            return itemMap(recordMovement(id, quantity - existing.quantity(), "MANUAL_ADJUSTMENT", actor, null, null, null));
        }
        return itemMap(items.findById(id)
                .orElseThrow(() -> new IllegalStateException("Updated item could not be read")));
    }

    @Transactional
    public void deleteItem(long id, String actor) {
        Item item = items.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "api.item.notFound"));
        if (items.countOrderReferences(id) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "api.item.inUse");
        }
        if (item.quantity() > 0) {
            if (items.update(id, item.title(), item.description(), 0, item.cost(), item.profit(),
                    item.barcode(), actor, item.version()) != 1) {
                throw itemChanged();
            }
            recordMovement(id, -item.quantity(), "ITEM_DELETED", actor, null, null, null);
        }
        items.delete(id);
    }

    public List<Map<String, Object>> listOrders(boolean pricesVisible) {
        return orders.findAll().stream().map(order -> orderMap(order, pricesVisible)).toList();
    }

    public Map<String, Object> pageOrders(int page, int size, String search, boolean pricesVisible) {
        return pageOrders(page, size, false, normalizeSearch(search), pricesVisible);
    }

    public Map<String, Object> pageArchivedOrders(int page, int size, String search, boolean pricesVisible) {
        return pageOrders(page, size, true, normalizeSearch(search), pricesVisible);
    }

    public Map<String, Object> pageCancelledOrders(int page, int size, String search, boolean pricesVisible) {
        String normalizedSearch = normalizeSearch(search);
        long offset;
        try {
            offset = Math.multiplyExact((long) page, size);
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.pageInvalid");
        }
        long totalItems = orders.countCancelledOrders(normalizedSearch);
        long totalPages = totalItems == 0 ? 0 : (totalItems + size - 1) / size;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", orders.findCancelledPage(size, offset, normalizedSearch).stream()
                .map(order -> orderMap(order, pricesVisible)).toList());
        result.put("page", page);
        result.put("size", size);
        result.put("totalItems", totalItems);
        result.put("totalPages", totalPages);
        return result;
    }

    private Map<String, Object> pageOrders(int page, int size, boolean archived, String search, boolean pricesVisible) {
        long offset;
        try {
            offset = Math.multiplyExact((long) page, size);
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.pageInvalid");
        }
        long totalItems = archived ? orders.countArchivedOrders(search) : orders.countOrders(search);
        long totalPages = totalItems == 0 ? 0 : (totalItems + size - 1) / size;
        Map<String, Object> result = new LinkedHashMap<>();
        List<OrderSummary> matches = archived
                ? orders.findArchivedPage(size, offset, search)
                : orders.findPage(size, offset, search);
        result.put("items", matches.stream().map(order -> orderMap(order, pricesVisible)).toList());
        result.put("page", page);
        result.put("size", size);
        result.put("totalItems", totalItems);
        result.put("totalPages", totalPages);
        return result;
    }

    private String normalizeSearch(String search) {
        return search == null ? "" : search.trim();
    }

    public Map<String, Object> getOrder(long id, String locale) {
        return orders.findById(id)
                .map(order -> orderMap(
                        order,
                        orders.findLines(id),
                        orders.findRemovedLines(id),
                        orders.findReturns(id),
                        orders.findReturnLines(id),
                        locale, true))
                .orElse(null);
    }

    public Map<String, Object> getOrderForUser(long id, String locale, boolean allOrdersVisible, boolean pricesVisible) {
        Optional<OrderSummary> match = allOrdersVisible
                ? orders.findById(id)
                : orders.findActiveById(id);
        return match.map(order -> orderMap(
                        order,
                        orders.findLines(id),
                        orders.findRemovedLines(id),
                        orders.findReturns(id),
                        orders.findReturnLines(id),
                        locale, pricesVisible))
                .orElse(null);
    }

    @Transactional
    public Map<String, Object> createOrder(
            String text,
            List<Map<String, Object>> rawItems,
            String status,
            String actor,
            String locale) {
        return createOrder(text, rawItems, status, null, actor, locale);
    }

    @Transactional
    public Map<String, Object> createOrder(
            String text,
            List<Map<String, Object>> rawItems,
            String status,
            String rawOrderDate,
            String actor,
            String locale) {
        if (text.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.textRequired");
        }
        if (!"DRAFT".equals(status) && !"CONFIRMED".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.statusInvalid");
        }

        LocalDate orderDate = parseOrderDate(rawOrderDate, true);
        Map<Long, Integer> quantities = orderQuantities(rawItems);
        long orderId = orders.insert(text, orderDate.toString(), status, actor);
        addLines(orderId, quantities, actor);
        return getOrder(orderId, locale);
    }

    @Transactional
    public Map<String, Object> updateOrderText(
            long orderId,
            String text,
            String actor,
            String locale) {
        if (text.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.textRequired");
        }
        requireDraft(orderId);
        if (orders.updateDraftText(orderId, text, actor) != 1) {
            throw orderNotDraft();
        }
        return getOrder(orderId, locale);
    }

    @Transactional
    public Map<String, Object> updateOrderDate(
            long orderId,
            String rawOrderDate,
            String actor,
            String locale) {
        requireDraft(orderId);
        LocalDate orderDate = parseOrderDate(rawOrderDate, false);
        if (orders.updateDraftOrderDate(orderId, orderDate.toString(), actor) != 1) {
            throw orderNotDraft();
        }
        return getOrder(orderId, locale);
    }

    @Transactional
    public Map<String, Object> addOrderItems(
            long orderId,
            List<Map<String, Object>> rawItems,
            String actor,
            String locale) {
        requireDraft(orderId);
        Map<Long, Integer> quantities = orderQuantities(rawItems);
        addLines(orderId, quantities, actor);
        if (orders.touchDraft(orderId, actor) != 1) {
            throw orderNotDraft();
        }
        return getOrder(orderId, locale);
    }

    @Transactional
    public Map<String, Object> removeOrderItem(
            long orderId,
            long lineId,
            String actor,
            String locale) {
        requireDraft(orderId);
        OrderLine line = orders.findLines(orderId).stream()
                .filter(candidate -> candidate.id() == lineId)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "api.order.lineNotFound"));
        if (orders.removeLineIfActive(orderId, lineId, actor) != 1) {
            if (orders.countActiveLines(orderId) <= 1) {
                throw new ApiException(HttpStatus.CONFLICT, "api.order.lastItem");
            }
            throw orderNotDraft();
        }
        if (items.incrementStock(line.itemId(), line.quantity(), actor) != 1) {
            throw stockOverflow();
        }
        recordMovement(line.itemId(), line.quantity(), "ORDER_ITEM_REMOVED", actor, orderId, line.id(), null);
        if (orders.touchDraft(orderId, actor) != 1) {
            throw orderNotDraft();
        }
        return getOrder(orderId, locale);
    }

    @Transactional
    public Map<String, Object> confirmOrder(long orderId, String actor, String locale) {
        requireDraft(orderId);
        if (orders.countActiveLines(orderId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "api.order.itemRequired");
        }
        if (orders.confirmDraft(orderId, actor) != 1) {
            throw orderNotDraft();
        }
        return getOrder(orderId, locale);
    }

    @Transactional
    public Map<String, Object> cancelOrder(long orderId, String actor, String locale) {
        requireDraft(orderId);
        List<OrderLine> lines = orders.findLines(orderId);
        if (orders.cancelDraft(orderId, actor) != 1) {
            throw orderNotDraft();
        }
        for (OrderLine line : lines) {
            if (items.incrementStock(line.itemId(), line.quantity(), actor) != 1) {
                throw stockOverflow();
            }
            recordMovement(line.itemId(), line.quantity(), "ORDER_CANCELLED", actor, orderId, line.id(), null);
        }
        return getOrder(orderId, locale);
    }

    @Transactional
    public Map<String, Object> updateOrderPickup(
            long orderId,
            boolean pickedUp,
            String actor,
            String locale) {
        OrderSummary order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "api.order.notFound"));
        if (order.archivedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "api.order.notConfirmed");
        }
        if ("DRAFT".equals(order.status())) {
            if (!pickedUp) {
                return getOrder(orderId, locale);
            }
            if (orders.countActiveLines(orderId) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "api.order.itemRequired");
            }
            if (orders.confirmDraftAsPickedUp(orderId, actor) != 1) {
                throw orderNotDraft();
            }
            return getOrder(orderId, locale);
        }
        if (!"CONFIRMED".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "api.order.notConfirmed");
        }
        if ((order.pickedUpAt() != null) == pickedUp) {
            return getOrder(orderId, locale);
        }
        if (orders.setPickupState(orderId, pickedUp, actor) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "api.order.notConfirmed");
        }
        return getOrder(orderId, locale);
    }

    private Map<Long, Integer> orderQuantities(List<Map<String, Object>> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.itemRequired");
        }
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (Map<String, Object> rawItem : rawItems) {
            Long itemId = RequestValues.positiveId(rawItem.get("item_id"));
            Integer quantity = RequestValues.quantity(rawItem.get("quantity"), false);
            if (itemId == null || quantity == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.lineInvalid");
            }
            try {
                quantities.merge(itemId, quantity, Math::addExact);
            } catch (ArithmeticException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.lineInvalid");
            }
        }
        return quantities;
    }

    private void addLines(long orderId, Map<Long, Integer> quantities, String actor) {
        for (Map.Entry<Long, Integer> requested : quantities.entrySet()) {
            Item item = items.findById(requested.getKey()).orElseThrow(() -> new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "api.item.missingInOrder",
                    Map.of("itemId", requested.getKey())));
            int available = item.quantity();
            if (requested.getValue() > available) {
                throw unavailable(item, available);
            }

            checkedMoneyMultiply(item.price(), requested.getValue());
            checkedMoneyMultiply(item.cost(), requested.getValue());
            checkedMoneyMultiply(item.profit(), requested.getValue());

            long lineId = orders.insertLine(
                    orderId,
                    item.id(),
                    item.title(),
                    item.barcode(),
                    requested.getValue(),
                    item.price(),
                    item.cost(),
                    item.profit(),
                    actor);
            if (items.decrementStock(item.id(), requested.getValue(), actor) != 1) {
                throw unavailable(item, available);
            }
            recordMovement(item.id(), -requested.getValue(), "ORDER_RESERVED", actor, orderId, lineId, null);
        }
    }

    private OrderSummary requireDraft(long orderId) {
        OrderSummary order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "api.order.notFound"));
        if (!"DRAFT".equals(order.status()) || order.archivedAt() != null) {
            throw orderNotDraft();
        }
        return order;
    }

    private ApiException orderNotDraft() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "api.order.notDraft",
                Map.of(),
                "ORDER_NOT_DRAFT");
    }

    @Transactional
    public Map<String, Object> archiveOrder(long orderId, String actor, String locale) {
        OrderSummary order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "api.order.notFound"));
        if (!"CONFIRMED".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "api.order.notConfirmed");
        }
        if (order.archivedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "api.order.alreadyArchived");
        }
        if (!"FULLY_RETURNED".equals(returnStatus(order))) {
            throw new ApiException(HttpStatus.CONFLICT, "api.order.archiveNotFullyReturned");
        }
        if (orders.archiveIfActive(orderId, actor) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "api.order.alreadyArchived");
        }
        return getOrder(orderId, locale);
    }

    @Transactional
    public Map<String, Object> returnOrder(
            long orderId,
            boolean full,
            List<Map<String, Object>> rawItems,
            String reason,
            String actor,
            String locale) {
        OrderSummary order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "api.order.notFound"));
        if (!"CONFIRMED".equals(order.status()) || order.archivedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "api.order.notConfirmed");
        }
        if (reason != null && reason.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.return.reasonTooLong");
        }

        List<OrderLine> lines = orders.findLines(orderId);
        Map<Long, OrderLine> linesById = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            linesById.put(line.id(), line);
        }

        Map<Long, Integer> requested = new LinkedHashMap<>();
        if (full) {
            if (rawItems != null && !rawItems.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "api.return.invalid");
            }
            for (OrderLine line : lines) {
                if (line.remainingQuantity() > 0) {
                    requested.put(line.id(), line.remainingQuantity());
                }
            }
        } else {
            if (rawItems == null || rawItems.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "api.return.itemRequired");
            }
            for (Map<String, Object> rawItem : rawItems) {
                Long orderItemId = RequestValues.positiveId(rawItem.get("order_item_id"));
                Integer quantity = RequestValues.quantity(rawItem.get("quantity"), false);
                if (orderItemId == null || quantity == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "api.return.lineInvalid");
                }
                try {
                    requested.merge(orderItemId, quantity, Math::addExact);
                } catch (ArithmeticException exception) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "api.return.lineInvalid");
                }
            }
        }

        if (requested.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "api.return.nothingRemaining");
        }
        for (Map.Entry<Long, Integer> entry : requested.entrySet()) {
            OrderLine line = linesById.get(entry.getKey());
            if (line == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "api.return.lineNotFound");
            }
            if (entry.getValue() > line.remainingQuantity()) {
                throw new ApiException(HttpStatus.CONFLICT, "api.return.quantityExceeded");
            }
        }

        long returnId = orders.insertReturn(orderId, reason, actor);
        for (Map.Entry<Long, Integer> entry : requested.entrySet()) {
            OrderLine line = linesById.get(entry.getKey());
            int quantity = entry.getValue();
            if (orders.addReturnedQuantity(orderId, line.id(), quantity, actor) != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "api.return.quantityExceeded");
            }
            orders.insertReturnLine(returnId, line.id(), quantity, checkedMoneyMultiply(line.paidAmount(), quantity));
            if (items.incrementStock(line.itemId(), quantity, actor) != 1) {
                throw stockOverflow();
            }
            recordMovement(line.itemId(), quantity, "ORDER_RETURNED", actor, orderId, line.id(), returnId);
        }
        return getOrder(orderId, locale);
    }

    private Item recordMovement(long itemId, int delta, String reason, String actor,
                                Long orderId, Long lineId, Long returnId) {
        Item item = items.findById(itemId)
                .orElseThrow(() -> new IllegalStateException("Changed item could not be read"));
        movements.insert(item, delta, reason, actor, orderId, lineId, returnId);
        return item;
    }

    private ApiException stockOverflow() {
        return new ApiException(HttpStatus.CONFLICT, "api.item.stockOverflow");
    }

    private long checkedMoneyMultiply(long cents, int quantity) {
        try {
            return MoneyValues.multiply(cents, quantity);
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.money.outOfRange");
        }
    }

    private ApiException unavailable(Item item, int available) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "api.item.stockUnavailable",
                Map.of("quantity", available, "title", item.title()));
    }

    private ApiException itemChanged() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "api.item.changed",
                Map.of(),
                "ITEM_CHANGED");
    }

    private void validateItem(String title, Integer quantity, Long cost, Long profit) {
        if (title.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.titleRequired");
        }
        if (quantity == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.quantityInvalid");
        }
        if (cost == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.costInvalid");
        }
        if (profit == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.profitInvalid");
        }
        validateMoneySum(cost, profit);
    }

    private void validateMoneySum(long cost, long profit) {
        try {
            MoneyValues.add(cost, profit);
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.money.outOfRange");
        }
    }

    private Map<String, Object> itemMap(Item item) {
        return itemMap(item, true);
    }

    private Map<String, Object> itemMap(Item item, boolean pricesVisible) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.id());
        result.put("title", item.title());
        result.put("description", item.description());
        result.put("quantity", item.quantity());
        if (pricesVisible) {
            result.put("cost", MoneyValues.amount(item.cost()));
            result.put("profit", MoneyValues.amount(item.profit()));
            result.put("price", MoneyValues.amount(item.price()));
        }
        result.put("barcode", item.barcode());
        result.put("created_at", DatabaseTimestamps.utcIso(item.createdAt()));
        result.put("created_by", item.createdBy());
        result.put("modified_at", DatabaseTimestamps.utcIso(item.modifiedAt()));
        result.put("modified_by", item.modifiedBy());
        result.put("version", item.version());
        return result;
    }

    private Map<String, Object> orderMap(OrderSummary order, boolean pricesVisible) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", order.id());
        result.put("text", order.text());
        result.put("status", order.status());
        result.put("order_date", order.orderDate());
        result.put("created_at", DatabaseTimestamps.utcIso(order.createdAt()));
        result.put("created_by", order.createdBy());
        result.put("modified_at", DatabaseTimestamps.utcIso(order.modifiedAt()));
        result.put("modified_by", order.modifiedBy());
        result.put("confirmed_at", DatabaseTimestamps.utcIso(order.confirmedAt()));
        result.put("confirmed_by", order.confirmedBy());
        result.put("cancelled_at", DatabaseTimestamps.utcIso(order.cancelledAt()));
        result.put("cancelled_by", order.cancelledBy());
        result.put("archived_at", DatabaseTimestamps.utcIso(order.archivedAt()));
        result.put("archived_by", order.archivedBy());
        result.put("picked_up", order.pickedUpAt() != null);
        result.put("picked_up_at", DatabaseTimestamps.utcIso(order.pickedUpAt()));
        result.put("total_quantity", order.totalQuantity());
        result.put("returned_quantity", order.returnedQuantity());
        result.put("net_quantity", order.totalQuantity() - order.returnedQuantity());
        if (pricesVisible) {
            result.put("total_amount", MoneyValues.amount(order.totalAmount()));
            result.put("returned_amount", MoneyValues.amount(order.returnedAmount()));
            result.put("net_amount", MoneyValues.amount(MoneyValues.subtract(order.totalAmount(), order.returnedAmount())));
            result.put("total_profit", MoneyValues.amount(order.totalProfit()));
            result.put("returned_profit", MoneyValues.amount(order.returnedProfit()));
            result.put("net_profit", MoneyValues.amount(order.netProfit()));
            result.put("total_cost", MoneyValues.amount(order.totalCost()));
            result.put("returned_cost", MoneyValues.amount(order.returnedCost()));
            result.put("net_cost", MoneyValues.amount(order.netCost()));
        }
        result.put("return_status", returnStatus(order));
        return result;
    }

    private LocalDate parseOrderDate(String value, boolean defaultWhenMissing) {
        if (value == null && defaultWhenMissing) {
            return LocalDate.now(clock.withZone(BUSINESS_ZONE));
        }
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.dateInvalid");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.dateInvalid");
        }
    }

    private Map<String, Object> orderMap(
            OrderSummary order,
            List<OrderLine> lines,
            List<OrderLine> removedLines,
            List<ReturnSummary> returns,
            List<ReturnLine> returnLines,
            String locale,
            boolean pricesVisible) {
        Map<String, Object> result = orderMap(order, pricesVisible);
        result.put("items", lines.stream().map(line -> orderLineMap(line, locale, pricesVisible)).toList());
        result.put("removed_items", removedLines.stream().map(line -> orderLineMap(line, locale, pricesVisible)).toList());
        Map<Long, List<ReturnLine>> linesByReturn = new LinkedHashMap<>();
        for (ReturnLine line : returnLines) {
            linesByReturn.computeIfAbsent(line.returnId(), ignored -> new java.util.ArrayList<>()).add(line);
        }
        result.put("returns", returns.stream()
                .map(orderReturn -> returnMap(
                        orderReturn,
                        linesByReturn.getOrDefault(orderReturn.id(), List.of()),
                        locale, pricesVisible))
                .toList());
        return result;
    }

    private Map<String, Object> orderLineMap(OrderLine line, String locale, boolean pricesVisible) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", line.id());
        result.put("item_id", line.itemId());
        result.put("title", line.title() == null
                ? messages.get(locale, "api.item.deleted", Map.of())
                : line.title());
        result.put("barcode", line.barcode());
        result.put("quantity", line.quantity());
        result.put("returned_quantity", line.returnedQuantity());
        result.put("remaining_quantity", line.remainingQuantity());
        if (pricesVisible) {
            result.put("paid_amount", MoneyValues.amount(line.paidAmount()));
            result.put("subtotal", MoneyValues.amount(line.subtotal()));
            result.put("cost", MoneyValues.amount(line.cost()));
            result.put("cost_subtotal", MoneyValues.amount(line.costSubtotal()));
            result.put("profit", MoneyValues.amount(line.profit()));
            result.put("profit_subtotal", MoneyValues.amount(line.profitSubtotal()));
        }
        result.put("created_at", DatabaseTimestamps.utcIso(line.createdAt()));
        result.put("created_by", line.createdBy());
        result.put("removed_at", DatabaseTimestamps.utcIso(line.removedAt()));
        result.put("removed_by", line.removedBy());
        return result;
    }

    private Map<String, Object> returnMap(
            ReturnSummary orderReturn,
            List<ReturnLine> lines,
            String locale,
            boolean pricesVisible) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", orderReturn.id());
        result.put("reason", orderReturn.reason());
        result.put("created_at", DatabaseTimestamps.utcIso(orderReturn.createdAt()));
        result.put("created_by", orderReturn.createdBy());
        result.put("total_quantity", orderReturn.totalQuantity());
        if (pricesVisible) result.put("total_amount", MoneyValues.amount(orderReturn.totalAmount()));
        result.put("items", lines.stream().map(line -> returnLineMap(line, locale, pricesVisible)).toList());
        return result;
    }

    private Map<String, Object> returnLineMap(ReturnLine line, String locale, boolean pricesVisible) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_item_id", line.orderItemId());
        result.put("item_id", line.itemId());
        result.put("title", line.title() == null
                ? messages.get(locale, "api.item.deleted", Map.of())
                : line.title());
        result.put("barcode", line.barcode());
        result.put("quantity", line.quantity());
        if (pricesVisible) result.put("refund_amount", MoneyValues.amount(line.refundAmount()));
        return result;
    }

    private String returnStatus(OrderSummary order) {
        if (order.returnedQuantity() == 0) {
            return "NOT_RETURNED";
        }
        return order.returnedQuantity() >= order.totalQuantity()
                ? "FULLY_RETURNED"
                : "PARTIALLY_RETURNED";
    }
}
