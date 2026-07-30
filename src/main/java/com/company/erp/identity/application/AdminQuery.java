package com.company.erp.identity.application;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;

public final class AdminQuery {

    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private AdminQuery() {
    }

    public static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("Page must be non-negative and size must be between 1 and 100.");
        }
    }

    public static long parseIfMatch(String value) {
        if (value == null || !value.matches("\"[0-9]+\"")) {
            throw invalid("If-Match must contain a quoted non-negative version.");
        }
        try {
            return Long.parseLong(value.substring(1, value.length() - 1));
        } catch (NumberFormatException exception) {
            throw invalid("If-Match contains an invalid version.");
        }
    }

    public static List<String> sort(
            List<String> requested,
            Map<String, String> columns,
            List<String> defaults) {
        if (requested == null || requested.isEmpty()) {
            return defaults;
        }
        List<String> tokens = new ArrayList<>();
        for (String term : requested) {
            if (term == null || term.isBlank()) {
                throw invalidSort(columns);
            }
            for (String part : term.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) {
                    throw invalidSort(columns);
                }
                tokens.add(token);
            }
        }
        List<String> normalized = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            String field = tokens.get(index);
            if (!columns.containsKey(field)) {
                throw invalidSort(columns);
            }
            String direction = "asc";
            if (index + 1 < tokens.size()
                    && DIRECTIONS.contains(tokens.get(index + 1).toLowerCase(Locale.ROOT))) {
                direction = tokens.get(++index).toLowerCase(Locale.ROOT);
            }
            normalized.add(field + "," + direction);
        }
        if (columns.containsKey("id") && normalized.stream().noneMatch(term -> term.startsWith("id,"))) {
            normalized.add("id,asc");
        }
        return List.copyOf(normalized);
    }

    public static String orderBy(List<String> normalized, Map<String, String> columns) {
        List<String> order = new ArrayList<>();
        for (String term : normalized) {
            String[] parts = term.split(",", 2);
            String column = columns.get(parts[0]);
            if (column == null || !DIRECTIONS.contains(parts[1])) {
                throw invalidSort(columns);
            }
            order.add(column + ("desc".equals(parts[1]) ? " DESC" : " ASC"));
        }
        return String.join(", ", order);
    }

    public static Map<String, Object> filters(Object... namesAndValues) {
        Map<String, Object> filters = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            Object value = namesAndValues[index + 1];
            if (value != null && (!(value instanceof String string) || !string.isBlank())) {
                filters.put(namesAndValues[index].toString(), value);
            }
        }
        return Map.copyOf(filters);
    }

    public static String enumValue(String value, Set<String> allowed, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw invalid(field + " has an unsupported value.");
        }
        return normalized;
    }

    public static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid(field + " must be a UTC RFC 3339 instant.");
        }
    }

    public static UUID uuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " must be a UUID.");
        }
    }

    public static int totalPages(long total, int size) {
        return total == 0 ? 0 : (int) ((total + size - 1) / size);
    }

    private static ApiException invalidSort(Map<String, String> columns) {
        return invalid("Sort must use the allowlisted fields " + String.join(", ", columns.keySet()) + ".");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, detail);
    }
}
