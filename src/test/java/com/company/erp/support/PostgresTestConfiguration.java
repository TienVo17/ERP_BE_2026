package com.company.erp.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("erp_test")
                .withUsername("erp_migration_test")
                .withPassword("erp_migration_test")
                .withInitScript("db/testcontainer/init-runtime-role.sql");
    }

    @Bean
    RSAKey testRsaKey(Environment environment) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate())
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(environment.getRequiredProperty("erp.security.key-ring.active-kid"))
                    .build();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA key generation is unavailable", exception);
        }
    }
}
