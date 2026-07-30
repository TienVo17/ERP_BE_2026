package com.company.erp.audit;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventWriterTest {

    @Test
    @SuppressWarnings("unchecked")
    void recursivelyRemovesCredentialHeadersTokensPasswordsAndHashKeys() throws Exception {
        AuditEventWriter writer = new AuditEventWriter(null, null);
        Method redact = AuditEventWriter.class.getDeclaredMethod("redact", Map.class);
        redact.setAccessible(true);

        Map<String, ?> result = (Map<String, ?>) redact.invoke(writer, Map.of(
                "safe", "kept",
                "hash", "secret-hash",
                "password_hash", "secret-password-hash",
                "nested", Map.of(
                        "accessToken", "secret-token",
                        "Authorization", "Bearer secret",
                        "cookie", "secret-cookie",
                        "reason", "kept too")));

        assertThat(result.get("safe")).isEqualTo("kept");
        assertThat(result.toString())
                .contains("reason=kept too")
                .doesNotContain("secret-hash")
                .doesNotContain("secret-password-hash")
                .doesNotContain("secret-token")
                .doesNotContain("Bearer secret")
                .doesNotContain("secret-cookie");
    }
}
