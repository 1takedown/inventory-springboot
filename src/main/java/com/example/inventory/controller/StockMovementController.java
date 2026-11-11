package com.example.inventory.controller;

import com.example.inventory.repository.StockMovementRepository;
import com.example.inventory.support.ApiException;
import com.example.inventory.support.RequestValues;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
public class StockMovementController {
    private final StockMovementRepository movements;

    public StockMovementController(StockMovementRepository movements) {
        this.movements = movements;
    }

    @GetMapping("/api/stock-movements")
    public Map<String, Object> page(
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "50") String size,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) String itemId,
            @RequestParam(defaultValue = "") String reason,
            @RequestParam(required = false) String date) {
        LocalDate day = null;
        if (date != null && !date.isEmpty()) {
            try {
                if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    throw new DateTimeParseException("Invalid date", date, 0);
                }
                day = LocalDate.parse(date);
            } catch (DateTimeParseException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "api.request.invalid");
            }
        }
        Integer number = RequestValues.quantity(page, true);
        Integer count = RequestValues.quantity(size, false);
        Long item = itemId == null ? null : RequestValues.positiveId(itemId);
        if (number == null || count == null || count > 100
                || (itemId != null && item == null)
                || (!reason.isEmpty() && !StockMovementRepository.REASONS.contains(reason))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "api.request.invalid");
        }
        return movements.page(number, count, q.trim(), item, reason, day);
    }
}
