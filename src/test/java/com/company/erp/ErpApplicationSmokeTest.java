package com.company.erp;

import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class ErpApplicationSmokeTest {

    @Test
    void contextLoads() {
    }
}
