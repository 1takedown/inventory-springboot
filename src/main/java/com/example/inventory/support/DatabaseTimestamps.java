package com.example.inventory.support;

import java.util.regex.Pattern;

public final class DatabaseTimestamps {

    private static final Pattern SQLITE_UTC = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?");

    private DatabaseTimestamps() {
    }

    public static String utcIso(String value) {
        if (value == null || !SQLITE_UTC.matcher(value).matches()) {
            return value;
        }
        return value.replace(' ', 'T') + "Z";
    }
}
