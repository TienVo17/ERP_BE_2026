package com.company.erp.identity.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class BootstrapAdminIT {

    @Autowired
    private IdentityJdbcRepository identityRepository;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private JdbcClient jdbc;

    @TempDir
    Path tempDir;

    @Test
    void disabledBootstrapDoesNothing() throws Exception {
        String loginId = "bootstrap-disabled-" + UUID.randomUUID();
        BootstrapAdminRunner runner = new BootstrapAdminRunner(
                identityRepository,
                passwordService,
                new BootstrapAdminProperties(false, loginId, "Disabled Bootstrap", null));

        runner.run(new DefaultApplicationArguments());

        assertThat(identityRepository.findByCanonicalLoginId(loginId)).isEmpty();
    }

    @Test
    void rejectsCommandLinePasswordAndPrecomputedHashInputs() throws Exception {
        String loginId = "bootstrap-cli-" + UUID.randomUUID();
        Path secretFile = tempDir.resolve("admin-password-cli");
        Files.writeString(secretFile, "bootstrap password value");
        BootstrapAdminRunner runner = new BootstrapAdminRunner(
                identityRepository,
                passwordService,
                new BootstrapAdminProperties(true, loginId, "Bootstrap Admin", secretFile));

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("--password=forbidden-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command-line");
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("--password-hash={argon2}forbidden")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command-line");
        assertThat(identityRepository.findByCanonicalLoginId(loginId)).isEmpty();
    }

    @Test
    void enabledBootstrapConsumesOnlySecretFileCreatesForcedChangeAdminAndRefusesRerun() throws Exception {
        String loginId = "bootstrap-" + UUID.randomUUID();
        Path secretFile = tempDir.resolve("admin-password");
        Files.writeString(secretFile, "bootstrap password value");
        BootstrapAdminRunner runner = new BootstrapAdminRunner(
                identityRepository,
                passwordService,
                new BootstrapAdminProperties(true, loginId, "Bootstrap Admin", secretFile));

        runner.run(new DefaultApplicationArguments());

        var admin = identityRepository.findByCanonicalLoginId(loginId).orElseThrow();
        assertThat(admin.mustChangePassword()).isTrue();
        assertThat(passwordService.matches("bootstrap password value", admin.passwordHash())).isTrue();
        assertThat(jdbc.sql("""
                        SELECT r.code
                        FROM identity.user_role ur
                        JOIN identity.app_role r ON r.id = ur.role_id
                        WHERE ur.user_id = :userId
                        ORDER BY r.code
                        """)
                .param("userId", admin.id())
                .query(String.class)
                .list()).containsExactly("ADMIN");
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already used");

        BootstrapAdminRunner differentLoginRunner = new BootstrapAdminRunner(
                identityRepository,
                passwordService,
                new BootstrapAdminProperties(
                        true,
                        "second-bootstrap-" + UUID.randomUUID(),
                        "Second Bootstrap Admin",
                        secretFile));
        assertThatThrownBy(() -> differentLoginRunner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already used");
    }
}
