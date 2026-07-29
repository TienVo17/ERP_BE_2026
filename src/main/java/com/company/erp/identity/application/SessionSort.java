package com.company.erp.identity.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;

/**
 * Allowlisted sort terms for the current-user session list, matching the frozen OpenAPI contract.
 * Field names are mapped to columns here so a client can never reach a column that is not listed.
 */
public final class SessionSort {

    private static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "createdAt", "created_at",
            "lastRefreshedAt", "last_refreshed_at",
            "id", "id");

    private static final List<String> DEFAULT_SORT = List.of("createdAt,desc", "id,asc");

    private SessionSort() {
    }

    /** Returns the normalized, allowlisted sort terms, always ending with a stable id tiebreaker. */
    public static List<String> normalize(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return DEFAULT_SORT;
        }
        // Spring splits every comma itself, so "createdAt,desc" arrives as two separate elements.
        // Walk the flattened token stream and pair each field with an optional direction.
        List<String> tokens = new ArrayList<>();
        for (String term : requested) {
            if (term == null || term.isBlank()) {
                throw invalidSort();
            }
            for (String token : term.split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    throw invalidSort();
                }
                tokens.add(trimmed);
            }
        }

        List<String> normalized = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            String field = tokens.get(index);
            if (!SORTABLE_COLUMNS.containsKey(field)) {
                throw invalidSort();
            }
            String direction = "asc";
            if (index + 1 < tokens.size() && isDirection(tokens.get(index + 1))) {
                direction = tokens.get(index + 1).toLowerCase(Locale.ROOT);
                index++;
            }
            normalized.add(field + "," + direction);
        }
        if (normalized.stream().noneMatch(term -> term.startsWith("id,"))) {
            normalized.add("id,asc");
        }
        return List.copyOf(normalized);
    }

    /** Translates already-normalized terms into an ORDER BY clause. */
    public static String toOrderByClause(List<String> normalizedSort) {
        List<String> columns = new ArrayList<>();
        for (String term : normalizedSort) {
            String[] parts = term.split(",");
            columns.add(SORTABLE_COLUMNS.get(parts[0]) + ("desc".equals(parts[1]) ? " DESC" : " ASC"));
        }
        return String.join(", ", columns);
    }

    private static boolean isDirection(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        return "asc".equals(value) || "desc".equals(value);
    }

    private static ApiException invalidSort() {
        return new ApiException(
                ApiErrorCode.VALIDATION_FAILED,
                "Sort must use the allowlisted fields createdAt, lastRefreshedAt or id.");
    }
}
