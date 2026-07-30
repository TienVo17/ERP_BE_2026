package com.company.erp.audit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AuditEventWriter {

    private static final Set<String> REDACTED_KEYS = Set.of(
            "password",
            "passwordhash",
            "hash",
            "temporarypassword",
            "currentpassword",
            "newpassword",
            "token",
            "accesstoken",
            "refreshtoken",
            "cookie",
            "authorization");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AuditEventWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void write(
            UUID actorUserId,
            String action,
            String entityType,
            UUID entityId,
            String requestId,
            String reason,
            Map<String, ?> before,
            Map<String, ?> after) {
        jdbc.sql("""
                        INSERT INTO audit.audit_event (
                            id, actor_user_id, actor_type, action, entity_type, entity_id,
                            request_id, reason, before_data, after_data
                        ) VALUES (
                            :id, :actorUserId, 'USER', :action, :entityType, :entityId,
                            :requestId, :reason, CAST(:beforeData AS jsonb), CAST(:afterData AS jsonb)
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("actorUserId", actorUserId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("requestId", requestId)
                .param("reason", reason)
                .param("beforeData", json(redact(before)))
                .param("afterData", json(redact(after)))
                .update();
    }

    private Map<String, ?> redact(Map<String, ?> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!isSensitive(key)) {
                result.put(key, redactValue(value));
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> copy = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> {
                String name = String.valueOf(key);
                if (!isSensitive(name)) {
                    copy.put(name, redactValue(nestedValue));
                }
            });
            return copy;
        }
        if (value instanceof Iterable<?> values) {
            java.util.List<Object> copy = new java.util.ArrayList<>();
            values.forEach(item -> copy.add(redactValue(item)));
            return copy;
        }
        return value;
    }

    private boolean isSensitive(String key) {
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return REDACTED_KEYS.stream().anyMatch(normalized::contains);
    }

    private String json(Map<String, ?> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not serialize redacted audit data", exception);
        }
    }
}
