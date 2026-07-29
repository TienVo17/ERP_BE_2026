package com.company.erp.config;

import java.time.Duration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErpPropertiesTest {

    @Test
    void securityDefaultsMatchTheFrozenContract() {
        ErpSecurityProperties properties = bindSecurity(new MapConfigurationPropertySource());

        assertThat(properties.issuer()).isEqualTo("https://erp.example.invalid");
        assertThat(properties.audience()).isEqualTo("erp-api");
        assertThat(properties.accessTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refreshAbsoluteTtl()).isEqualTo(Duration.ofHours(8));
        assertThat(properties.refreshIdleTtl()).isEqualTo(Duration.ofMinutes(60));
        assertThat(properties.loginRateLimitPerMinute()).isEqualTo(10);
        assertThat(properties.refreshRateLimitPerMinute()).isEqualTo(120);
        assertThat(properties.refreshCookie().name()).isEqualTo("ERP_REFRESH");
        assertThat(properties.refreshCookie().path()).isEqualTo("/api/v1/auth");
        assertThat(properties.refreshCookie().secure()).isTrue();
        assertThat(properties.refreshCookie().httpOnly()).isTrue();
        assertThat(properties.refreshCookie().sameSite()).isEqualTo("Strict");
        assertThat(properties.keyRing().algorithm()).isEqualTo("RS256");
        assertThat(properties.keyRing().rsaBits()).isEqualTo(3072);
        assertThat(properties.keyRing().activeKid()).isNull();
        assertThat(properties.keyRing().privateKeyLocation()).isNull();
        assertThat(properties.keyRing().publicKeyLocations()).isEmpty();
    }

    @Test
    void rejectsUnsafeSecurityConfiguration() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("erp.security.access-ttl", "PT0S");
        source.put("erp.security.refresh-cookie.http-only", "false");
        source.put("erp.security.key-ring.rsa-bits", "2048");

        assertThatThrownBy(() -> bindSecurity(source))
                .hasMessageContaining("erp.security");
    }

    @Test
    void webDefaultsRejectUnknownRequestProperties() {
        ErpWebProperties properties = bindWeb(new MapConfigurationPropertySource());
        JsonMapper.Builder builder = JsonMapper.builder();
        new ErpJacksonConfiguration().rejectUnknownRequestProperties(properties).customize(builder);
        JsonMapper mapper = builder.build();

        assertThat(properties.failOnUnknownProperties()).isTrue();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThatThrownBy(() -> mapper.readValue(
                "{\"code\":\"PCS\",\"passwordHash\":\"forbidden\"}",
                StrictRequest.class))
                .hasMessageContaining("passwordHash");
    }

    @Test
    void rejectsConfigurationThatDisablesStrictRequestParsing() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("erp.web.fail-on-unknown-properties", "false");

        assertThatThrownBy(() -> bindWeb(source))
                .hasMessageContaining("erp.web");
    }

    private static ErpSecurityProperties bindSecurity(MapConfigurationPropertySource source) {
        return new Binder(source).bindOrCreate(
                "erp.security",
                Bindable.of(ErpSecurityProperties.class),
                validationBindHandler());
    }

    private static ErpWebProperties bindWeb(MapConfigurationPropertySource source) {
        return new Binder(source).bindOrCreate(
                "erp.web",
                Bindable.of(ErpWebProperties.class),
                validationBindHandler());
    }

    private record StrictRequest(String code) {
    }

    private static ValidationBindHandler validationBindHandler() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new MessageInterpolatorFactory().getObject());
        validator.afterPropertiesSet();
        return new ValidationBindHandler(validator);
    }
}
