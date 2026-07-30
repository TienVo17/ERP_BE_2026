package com.company.erp.masterdata.application;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class MasterDataSupport {

    private MasterDataSupport() {
    }

    public static ErpPrincipal principal(JwtAuthenticationToken authentication) {
        return (ErpPrincipal) authentication.getPrincipal();
    }

    public static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? null : value.substring(0, Math.min(value.length(), 120));
    }

    public static long version(String ifMatch) {
        return AdminQuery.parseIfMatch(ifMatch);
    }

    public static String canonicalCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public static String requiredText(String value) {
        return value.trim();
    }

    public static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String status(String value) {
        return AdminQuery.enumValue(value, java.util.Set.of("ACTIVE", "ARCHIVED"), "status");
    }

    public static <T> com.company.erp.masterdata.api.MasterDataModels.PageResponse<T> page(
            List<T> items, int number, int size, long total, Map<String, Object> filters, List<String> sort) {
        return new com.company.erp.masterdata.api.MasterDataModels.PageResponse<>(
                items,
                new com.company.erp.masterdata.api.MasterDataModels.PageMetadata(
                        number, size, total, AdminQuery.totalPages(total, size)),
                filters,
                sort);
    }

    public static void staleOrMissing(boolean exists) {
        if (!exists) {
            throw new com.company.erp.api.ResourceNotFoundException("master data", "missing");
        }
        throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "The resource changed after it was read.");
    }

    public static void inUse(String resource) {
        throw new ApiException(ApiErrorCode.MASTER_IN_USE, resource + " is referenced by business data.");
    }

    public static ApiException mapUsageRace(DataAccessException exception, String resource) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("MASTER_IN_USE:")) {
                return new ApiException(ApiErrorCode.MASTER_IN_USE, resource + " is referenced by business data.");
            }
            if (current instanceof SQLException sqlException
                    && "55000".equals(sqlException.getSQLState())
                    && message != null
                    && message.contains("Exchange Rate used by a posted Delivery cannot be changed or deleted")) {
                return new ApiException(ApiErrorCode.MASTER_IN_USE, resource + " is referenced by business data.");
            }
        }
        throw exception;
    }

    public static Map<String, Object> summary(UUID id, long version, String status, String key, Object value) {
        return Map.of("id", id, "version", version, "status", status, key, value);
    }
}
