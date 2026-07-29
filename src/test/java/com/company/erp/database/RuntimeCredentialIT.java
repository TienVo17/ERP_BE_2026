package com.company.erp.database;

import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;

import com.company.erp.ErpApplication;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeCredentialIT {

    @Test
    void runtimeApplicationStartsWithoutMigrationOwnerCredential() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("erp_runtime_startup")
                .withUsername("erp_migration_runtime")
                .withPassword("erp_migration_runtime")
                .withInitScript("db/testcontainer/init-runtime-startup-role.sql")) {
            postgres.start();

            org.flywaydb.core.Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .load()
                    .migrate();

            Map<String, Object> properties = new HashMap<>();
            properties.put("spring.datasource.url", postgres.getJdbcUrl());
            properties.put("spring.datasource.username", "erp_runtime_startup");
            properties.put("spring.datasource.password", "runtime-startup-test-only");
            properties.put("spring.flyway.enabled", "false");
            properties.put("spring.main.web-application-type", "none");

            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ErpApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(properties.entrySet().stream()
                            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                            .toArray(String[]::new))) {
                javax.sql.DataSource dataSource = context.getBean(javax.sql.DataSource.class);
                try (var connection = dataSource.getConnection();
                        var statement = connection.createStatement();
                        var result = statement.executeQuery("SELECT current_user")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("erp_runtime_startup");
                }
            }

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(),
                    "erp_runtime_startup",
                    "runtime-startup-test-only")) {
                assertThatThrownBy(() -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("DELETE FROM public.flyway_schema_history");
                    }
                }).isInstanceOf(java.sql.SQLException.class);
            }
        }
    }
}
