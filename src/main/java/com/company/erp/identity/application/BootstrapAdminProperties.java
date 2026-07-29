package com.company.erp.identity.application;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("erp.bootstrap-admin")
public record BootstrapAdminProperties(
        boolean enabled,
        String loginId,
        String name,
        Path secretFile) {
}
