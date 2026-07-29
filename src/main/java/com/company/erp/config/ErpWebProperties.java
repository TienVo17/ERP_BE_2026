package com.company.erp.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("erp.web")
@Validated
public record ErpWebProperties(@NotNull @AssertTrue Boolean failOnUnknownProperties) {

    public ErpWebProperties {
        failOnUnknownProperties = failOnUnknownProperties == null ? Boolean.TRUE : failOnUnknownProperties;
    }
}
