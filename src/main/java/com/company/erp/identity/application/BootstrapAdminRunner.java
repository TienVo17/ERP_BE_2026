package com.company.erp.identity.application;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "erp.bootstrap-admin", name = "enabled", havingValue = "true")
public class BootstrapAdminRunner implements ApplicationRunner {

    private final IdentityJdbcRepository repository;
    private final PasswordService passwordService;
    private final BootstrapAdminProperties properties;

    public BootstrapAdminRunner(
            IdentityJdbcRepository repository,
            PasswordService passwordService,
            BootstrapAdminProperties properties) {
        this.repository = repository;
        this.passwordService = passwordService;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) throws Exception {
        if (!properties.enabled()) {
            return;
        }
        if (properties.loginId() == null || properties.loginId().isBlank()
                || properties.name() == null || properties.name().isBlank()
                || properties.secretFile() == null) {
            throw new IllegalStateException("Bootstrap admin requires login ID, name, and a mounted secret file");
        }
        if (arguments.containsOption("password") || arguments.containsOption("password-hash")) {
            throw new IllegalStateException("Bootstrap credentials cannot be supplied through command-line arguments");
        }
        if (repository.bootstrapAdminExists()
                || repository.findByCanonicalLoginId(properties.loginId()).isPresent()) {
            throw new IllegalStateException("Bootstrap admin is already used");
        }
        String password = readSecret();
        if (password.length() < 12) {
            throw new IllegalStateException("Bootstrap secret must contain at least 12 characters");
        }
        repository.createBootstrapAdmin(
                UUID.randomUUID(),
                properties.loginId(),
                properties.name(),
                passwordService.encode(password));
    }

    private String readSecret() {
        try {
            if (!Files.isRegularFile(properties.secretFile())) {
                throw new IllegalStateException("Bootstrap secret file is unavailable");
            }
            return Files.readString(properties.secretFile()).stripTrailing();
        } catch (IOException exception) {
            throw new IllegalStateException("Bootstrap secret file cannot be read", exception);
        }
    }
}
