package com.ticket.queue.infra;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class RedisValues {

    static String asString(final Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static long asLong(final Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            return Long.parseLong(stringValue);
        }
        throw new IllegalStateException("Redis script returned non numeric value");
    }

    static long parseLong(final String value, final long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }
}
