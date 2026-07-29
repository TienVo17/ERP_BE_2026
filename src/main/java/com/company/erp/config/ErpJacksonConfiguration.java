package com.company.erp.config;

import tools.jackson.databind.DeserializationFeature;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ErpJacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer rejectUnknownRequestProperties(ErpWebProperties properties) {
        return builder -> builder.configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                properties.failOnUnknownProperties());
    }
}
