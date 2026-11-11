package com.example.inventory.support;

public final class RequestValues {

    private RequestValues() {
    }

    public static String text(Object value) {
        return value instanceof String string ? string.trim() : "";
    }

    public static String optionalText(Object value) {
        String result = text(value);
        return result.isEmpty() ? null : result;
    }

    public static Long positiveId(Object value) {
        Long result = integer(value);
        return result != null && result > 0 ? result : null;
    }

    public static Integer quantity(Object value, boolean allowZero) {
        Long result = integer(value);
        if (result == null || result > Integer.MAX_VALUE || result < (allowZero ? 0 : 1)) {
            return null;
        }
        return result.intValue();
    }

    private static Long integer(Object value) {
        try {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return ((Number) value).longValue();
            }
            if (value instanceof Number number) {
                double candidate = number.doubleValue();
                return Double.isFinite(candidate) && candidate == Math.rint(candidate)
                        ? (long) candidate
                        : null;
            }
            String candidate = String.valueOf(value).trim();
            if (!candidate.matches("[+-]?\\d+")) {
                return null;
            }
            return Long.parseLong(candidate);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
