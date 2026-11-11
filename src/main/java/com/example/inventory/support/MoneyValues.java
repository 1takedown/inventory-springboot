package com.example.inventory.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyValues {

    private MoneyValues() {
    }

    public static Long cents(Object value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(value).trim());
            if (decimal.signum() < 0 || decimal.stripTrailingZeros().scale() > 2) {
                return null;
            }
            return decimal.setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .longValueExact();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public static BigDecimal amount(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    public static BigDecimal amount(Long cents) {
        return cents == null ? null : amount(cents.longValue());
    }

    public static long add(long left, long right) {
        return Math.addExact(left, right);
    }

    public static long subtract(long left, long right) {
        return Math.subtractExact(left, right);
    }

    public static long multiply(long cents, int quantity) {
        return Math.multiplyExact(cents, quantity);
    }
}