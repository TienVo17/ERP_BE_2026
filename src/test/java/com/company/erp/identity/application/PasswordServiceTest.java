package com.company.erp.identity.application;

import com.company.erp.identity.api.AuthRequests;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordServiceTest {

    private final PasswordService passwordService = new PasswordService();

    @Test
    void encodesWithArgon2DelegatingFormatAndMatchesWithoutExposingRawValue() {
        String rawPassword = "correct horse battery staple";

        String encoded = passwordService.encode(rawPassword);

        assertThat(encoded)
                .startsWith("{argon2}")
                .doesNotContain(rawPassword);
        assertThat(passwordService.matches(rawPassword, encoded)).isTrue();
        assertThat(passwordService.matches("wrong password", encoded)).isFalse();
        assertThat(passwordService.needsUpgrade(encoded)).isFalse();
    }

    @Test
    void authRequestStringRepresentationsRedactPasswords() {
        String secret = "temporary secret value";

        assertThat(new AuthRequests.LoginRequest("admin", secret).toString())
                .doesNotContain(secret)
                .contains("[REDACTED]");
        assertThat(new AuthRequests.ChangePasswordRequest(secret, secret + " changed").toString())
                .doesNotContain(secret)
                .contains("[REDACTED]");
    }
}
