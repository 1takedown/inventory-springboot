package com.example.inventory.controller;

import com.example.inventory.service.InventoryService;
import com.example.inventory.support.ApiException;
import com.example.inventory.support.RequestValues;
import com.example.inventory.support.UserPermissions;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final InventoryService inventoryService;

    public ItemController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
        return inventoryService.listItems(UserPermissions.canViewPrices(jwt.getClaimAsString("role")));
    }

    @GetMapping("/by-barcode/{barcode}")
    public Map<String, Object> byBarcode(@PathVariable String barcode, @AuthenticationPrincipal Jwt jwt) {
        String cleanBarcode = RequestValues.text(barcode);
        if (cleanBarcode.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.barcodeRequired");
        }
        return inventoryService.getItemByBarcode(cleanBarcode, UserPermissions.canViewPrices(jwt.getClaimAsString("role")));
    }

    @GetMapping("/by-barcode")
    public Map<String, Object> byBarcodeQuery(
            @RequestParam(required = false) String barcode, @AuthenticationPrincipal Jwt jwt) {
        String cleanBarcode = RequestValues.text(barcode);
        if (cleanBarcode.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.barcodeRequired");
        }
        return inventoryService.getItemByBarcode(cleanBarcode, UserPermissions.canViewPrices(jwt.getClaimAsString("role")));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> item = inventoryService.createItem(
                body == null ? Map.of() : body,
                actor(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.updateItem(
                itemId(id),
                body == null ? Map.of() : body,
                actor(jwt));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        inventoryService.deleteItem(itemId(id), actor(jwt));
        return ResponseEntity.noContent().build();
    }

    private long itemId(String value) {
        Long id = RequestValues.positiveId(value);
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.item.invalidId");
        }
        return id;
    }

    private String actor(Jwt jwt) {
        return jwt.getClaimAsString("username");
    }
}
