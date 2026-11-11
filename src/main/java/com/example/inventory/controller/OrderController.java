package com.example.inventory.controller;

import com.example.inventory.service.InventoryService;
import com.example.inventory.support.ApiException;
import com.example.inventory.support.Messages;
import com.example.inventory.support.RequestValues;
import com.example.inventory.support.UserPermissions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final InventoryService inventoryService;
    private final Messages messages;

    public OrderController(InventoryService inventoryService, Messages messages) {
        this.inventoryService = inventoryService;
        this.messages = messages;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
        return inventoryService.listOrders(UserPermissions.canViewPrices(jwt.getClaimAsString("role")));
    }

    @GetMapping("/page")
    public Map<String, Object> page(
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "50") String size,
            @RequestParam(defaultValue = "") String q,
            @AuthenticationPrincipal Jwt jwt) {
        Integer pageNumber = RequestValues.quantity(page, true);
        Integer pageSize = RequestValues.quantity(size, false);
        if (pageNumber == null || pageSize == null || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.pageInvalid");
        }
        return inventoryService.pageOrders(pageNumber, pageSize, q, UserPermissions.canViewPrices(jwt.getClaimAsString("role")));
    }

    @GetMapping("/archived/page")
    public Map<String, Object> archivedPage(
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "50") String size,
            @RequestParam(defaultValue = "") String q,
            @AuthenticationPrincipal Jwt jwt) {
        Integer pageNumber = RequestValues.quantity(page, true);
        Integer pageSize = RequestValues.quantity(size, false);
        if (pageNumber == null || pageSize == null || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.pageInvalid");
        }
        return inventoryService.pageArchivedOrders(
                pageNumber, pageSize, q, UserPermissions.canViewPrices(jwt.getClaimAsString("role")));
    }

    @GetMapping("/cancelled/page")
    public Map<String, Object> cancelledPage(
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "50") String size,
            @RequestParam(defaultValue = "") String q,
            @AuthenticationPrincipal Jwt jwt) {
        Integer pageNumber = RequestValues.quantity(page, true);
        Integer pageSize = RequestValues.quantity(size, false);
        if (pageNumber == null || pageSize == null || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.pageInvalid");
        }
        return inventoryService.pageCancelledOrders(
                pageNumber, pageSize, q, UserPermissions.canViewPrices(jwt.getClaimAsString("role")));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(
            @PathVariable String id,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> order = inventoryService.getOrderForUser(
                orderId(id), messages.locale(request), UserPermissions.canViewAllOrders(jwt.getClaimAsString("role")),
                UserPermissions.canViewPrices(jwt.getClaimAsString("role")));
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "api.order.notFound");
        }
        return order;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) Map<String, Object> requestBody,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
        String text = RequestValues.text(body.get("text"));
        String status = body.get("status") == null
                ? "CONFIRMED"
                : RequestValues.text(body.get("status"));
        String orderDate = body.containsKey("order_date")
                ? RequestValues.text(body.get("order_date"))
                : null;

        Map<String, Object> order = inventoryService.createOrder(
                text,
                items(body.get("items")),
                status,
                orderDate,
                actor(jwt),
                messages.locale(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @PutMapping("/{id}/text")
    public Map<String, Object> updateText(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> requestBody,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
        return inventoryService.updateOrderText(
                orderId(id), RequestValues.text(body.get("text")), actor(jwt), messages.locale(request));
    }

    @PutMapping("/{id}/date")
    public Map<String, Object> updateDate(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> requestBody,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
        return inventoryService.updateOrderDate(
                orderId(id), RequestValues.text(body.get("order_date")), actor(jwt), messages.locale(request));
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<Map<String, Object>> addItems(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> requestBody,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
        Map<String, Object> order = inventoryService.addOrderItems(
                orderId(id), items(body.get("items")), actor(jwt), messages.locale(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @DeleteMapping("/{id}/items/{lineId}")
    public Map<String, Object> removeItem(
            @PathVariable String id,
            @PathVariable String lineId,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.removeOrderItem(
                orderId(id), lineId(lineId), actor(jwt), messages.locale(request));
    }

    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(
            @PathVariable String id,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.confirmOrder(orderId(id), actor(jwt), messages.locale(request));
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(
            @PathVariable String id,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.cancelOrder(orderId(id), actor(jwt), messages.locale(request));
    }

    @PutMapping("/{id}/pickup")
    public Map<String, Object> updatePickup(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> requestBody,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Object pickedUp = requestBody == null ? null : requestBody.get("picked_up");
        if (!(pickedUp instanceof Boolean value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.pickupInvalid");
        }
        return inventoryService.updateOrderPickup(
                orderId(id), value, actor(jwt), messages.locale(request));
    }

    @PostMapping("/{id}/returns")
    public ResponseEntity<Map<String, Object>> createReturn(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> requestBody,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
        Object rawFull = body.get("full");
        if (rawFull != null && !(rawFull instanceof Boolean)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.return.invalid");
        }
        Map<String, Object> order = inventoryService.returnOrder(
                orderId(id),
                Boolean.TRUE.equals(rawFull),
                items(body.get("items")),
                RequestValues.optionalText(body.get("reason")),
                actor(jwt),
                messages.locale(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(
            @PathVariable String id,
            HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.archiveOrder(
                orderId(id), actor(jwt), messages.locale(request));
    }

    private List<Map<String, Object>> items(Object rawItems) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (rawItems instanceof List<?> list) {
            for (Object candidate : list) {
                if (candidate instanceof Map<?, ?> rawMap) {
                    Map<String, Object> item = new java.util.HashMap<>();
                    rawMap.forEach((key, value) -> item.put(String.valueOf(key), value));
                    items.add(item);
                } else {
                    items.add(Map.of());
                }
            }
        }
        return items;
    }

    private long orderId(String value) {
        Long id = RequestValues.positiveId(value);
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.invalidId");
        }
        return id;
    }

    private long lineId(String value) {
        Long id = RequestValues.positiveId(value);
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.order.lineInvalid");
        }
        return id;
    }

    private String actor(Jwt jwt) {
        return jwt.getClaimAsString("username");
    }
}
