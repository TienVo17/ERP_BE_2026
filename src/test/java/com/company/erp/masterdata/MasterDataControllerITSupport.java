package com.company.erp.masterdata;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import com.company.erp.identity.application.PasswordService;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.identity.security.JwtTokenService;
import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Shared authenticated PostgreSQL fixture for master-data controller contracts. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
@Transactional
public abstract class MasterDataControllerITSupport {

    protected static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SALE_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    protected static final UUID SEEDED_UOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private IdentityJdbcRepository identityRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    protected UUID saleId;
    protected UUID saleSessionId;
    protected UUID adminId;
    protected UUID adminSessionId;

    @BeforeEach
    void createPrincipals() {
        saleId = createUser("master-sale");
        assignRole(saleId, SALE_ROLE_ID);
        saleSessionId = createSession(saleId);

        adminId = createUser("master-admin");
        assignRole(adminId, ADMIN_ROLE_ID);
        adminSessionId = createSession(adminId);
    }

    protected void grantSalePermissions(String... permissions) {
        for (String permission : permissions) {
            String[] values = permission.split(":", -1);
            if (values.length != 2) {
                throw new IllegalArgumentException("Permission must use MODULE:ACTION form");
            }
            jdbc.sql("""
                            INSERT INTO identity.role_permission (role_id, permission_id, granted_by)
                            SELECT :roleId, id, :actorId
                            FROM identity.permission
                            WHERE module_code = :moduleCode AND action_code = :actionCode
                            ON CONFLICT (role_id, permission_id) DO NOTHING
                            """)
                    .param("roleId", SALE_ROLE_ID)
                    .param("actorId", SYSTEM_USER_ID)
                    .param("moduleCode", values[0])
                    .param("actionCode", values[1])
                    .update();
        }
    }

    protected RequestPostProcessor saleJwt() {
        return jwtFor(saleId, saleSessionId);
    }

    protected RequestPostProcessor adminJwt() {
        return jwtFor(adminId, adminSessionId);
    }

    protected String json(Map<String, ?> payload) throws Exception {
        return objectMapper.writeValueAsString(payload);
    }

    protected UUID insertUom(String code) {
        UUID id = UUID.randomUUID();
        String persistedCode = code.substring(0, Math.min(code.length(), 30));
        jdbc.sql("""
                        INSERT INTO master_data.uom (id, code, name, created_by, updated_by)
                        VALUES (:id, :code, 'Fixture UOM', :actorId, :actorId)
                        """)
                .param("id", id)
                .param("code", persistedCode)
                .param("actorId", SYSTEM_USER_ID)
                .update();
        return id;
    }

    protected UUID insertExchangeRate(LocalDate month) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO master_data.monthly_exchange_rate (
                            id, effective_month, vnd_usd_rate, won_usd_rate, created_by, updated_by
                        ) VALUES (:id, :month, 25000.000000, 1300.000000, :actorId, :actorId)
                        """)
                .param("id", id)
                .param("month", month.withDayOfMonth(1))
                .param("actorId", SYSTEM_USER_ID)
                .update();
        return id;
    }

    protected UUID insertCustomer(String shortName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO master_data.customer (
                            id, short_name, name, currency_code, created_by, updated_by
                        ) VALUES (:id, :shortName, 'Fixture Customer', 'USD', :actorId, :actorId)
                        """)
                .param("id", id)
                .param("shortName", shortName)
                .param("actorId", SYSTEM_USER_ID)
                .update();
        return id;
    }

    protected UUID insertSupplier(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO master_data.supplier (id, name, created_by, updated_by)
                        VALUES (:id, :name, :actorId, :actorId)
                        """)
                .param("id", id)
                .param("name", name)
                .param("actorId", SYSTEM_USER_ID)
                .update();
        return id;
    }

    protected UUID insertCustomerContact(UUID customerId, String name, boolean defaultContact) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO master_data.customer_contact (
                            id, customer_id, name, is_default, created_by, updated_by
                        ) VALUES (:id, :customerId, :name, :defaultContact, :actorId, :actorId)
                        """)
                .param("id", id)
                .param("customerId", customerId)
                .param("name", name)
                .param("defaultContact", defaultContact)
                .param("actorId", SYSTEM_USER_ID)
                .update();
        return id;
    }

    protected UUID insertProcess(String name, String qrValue) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO master_data.process_master (
                            id, name, sequence_no, qr_value, created_by, updated_by
                        ) VALUES (:id, :name, 10, :qrValue, :actorId, :actorId)
                        """)
                .param("id", id)
                .param("name", name)
                .param("qrValue", qrValue)
                .param("actorId", SYSTEM_USER_ID)
                .update();
        return id;
    }

    protected void referenceUom(UUID uomId) {
        jdbc.sql("""
                        INSERT INTO master_data.raw_material (
                            id, code, name, uom_id, currency_code, created_by, updated_by
                        ) VALUES (:id, :code, 'Referenced raw material', :uomId, 'USD', :actorId, :actorId)
                        """)
                .param("id", UUID.randomUUID())
                .param("code", "RM-" + UUID.randomUUID())
                .param("uomId", uomId)
                .param("actorId", SYSTEM_USER_ID)
                .update();
    }

    protected void referenceSupplier(UUID supplierId) {
        jdbc.sql("""
                        INSERT INTO master_data.raw_material (
                            id, code, name, uom_id, currency_code, supplier_id, created_by, updated_by
                        ) VALUES (:id, :code, 'Supplier reference', :uomId, 'USD', :supplierId, :actorId, :actorId)
                        """)
                .param("id", UUID.randomUUID())
                .param("code", "RM-" + UUID.randomUUID())
                .param("uomId", SEEDED_UOM_ID)
                .param("supplierId", supplierId)
                .param("actorId", SYSTEM_USER_ID)
                .update();
    }

    protected void referenceCustomer(UUID customerId) {
        jdbc.sql("""
                        INSERT INTO sales.buyer_order (
                            id, sys_po_no, order_type, customer_id, customer_name_snapshot,
                            customer_short_name_snapshot, pic_source, pic_name_snapshot, buyer_po,
                            po_date, delivery_date, created_by, updated_by
                        ) VALUES (
                            :id, :orderNo, 'STANDARD', :customerId, 'Fixture Customer', 'FIXTURE',
                            'CUSTOM', 'Fixture PIC', 'BUYER-PO', CURRENT_DATE, CURRENT_DATE,
                            :actorId, :actorId
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("orderNo", orderNumber("SO"))
                .param("customerId", customerId)
                .param("actorId", SYSTEM_USER_ID)
                .update();
    }

    protected void referenceProcess(UUID processId) {
        UUID customerId = insertCustomer("PROCESS-" + UUID.randomUUID());
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO sales.buyer_order (
                            id, sys_po_no, order_type, customer_id, customer_name_snapshot,
                            customer_short_name_snapshot, pic_source, pic_name_snapshot, buyer_po,
                            po_date, delivery_date, created_by, updated_by
                        ) VALUES (
                            :id, :orderNo, 'STANDARD', :customerId, 'Fixture Customer', 'PROCESS',
                            'CUSTOM', 'Fixture PIC', 'BUYER-PO', CURRENT_DATE, CURRENT_DATE,
                            :actorId, :actorId
                        )
                        """)
                .param("id", orderId)
                .param("orderNo", orderNumber("SO"))
                .param("customerId", customerId)
                .param("actorId", SYSTEM_USER_ID)
                .update();
        UUID itemId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO sales.buyer_order_item (
                            id, buyer_order_id, line_no, is_custom, product_kind_snapshot,
                            style_no_snapshot, name_snapshot, uom_id, uom_code_snapshot, order_qty,
                            production_qty, unit_price, currency_code, amount, created_by, updated_by
                        ) VALUES (
                            :id, :orderId, 1, true, 'PRINT', 'STYLE', 'Fixture item', :uomId, 'EA', 1,
                            1, 0, 'USD', 0, :actorId, :actorId
                        )
                        """)
                .param("id", itemId)
                .param("orderId", orderId)
                .param("uomId", SEEDED_UOM_ID)
                .param("actorId", SYSTEM_USER_ID)
                .update();
        UUID productionOrderId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO production.production_order (
                            id, production_no, buyer_order_item_id, buyer_order_id, product_kind_snapshot,
                            product_no, qr_value, planned_qty, created_by, updated_by
                        ) VALUES (
                            :id, :productionNo, :itemId, :orderId, 'PRINT', 'FIXTURE-PRODUCT',
                            :qrValue, 1, :actorId, :actorId
                        )
                        """)
                .param("id", productionOrderId)
                .param("productionNo", orderNumber("PR"))
                .param("itemId", itemId)
                .param("orderId", orderId)
                .param("qrValue", "PRODUCTION-" + UUID.randomUUID())
                .param("actorId", SYSTEM_USER_ID)
                .update();
        jdbc.sql("""
                        INSERT INTO production.production_order_process (production_order_id, process_id, sequence_no)
                        VALUES (:productionOrderId, :processId, 1)
                        """)
                .param("productionOrderId", productionOrderId)
                .param("processId", processId)
                .update();
    }

    /** The constraint trigger is deferred; the test transaction is always rolled back. */
    protected void referenceExchangeRateWithPostedDelivery(UUID exchangeRateId) {
        UUID customerId = insertCustomer("RATE-" + UUID.randomUUID());
        jdbc.sql("""
                        INSERT INTO delivery.delivery_note (
                            id, delivery_no, customer_id, customer_name_snapshot, delivery_date,
                            currency_code, exchange_rate_id, vnd_usd_rate_snapshot, won_usd_rate_snapshot,
                            total_qty, total_amount, status, posted_at, posted_by, created_by, updated_by
                        ) VALUES (
                            :id, :deliveryNo, :customerId, 'Fixture Customer', DATE '2026-06-15',
                            'USD', :exchangeRateId, 25000.000000, 1300.000000, 0, 0, 'POSTED',
                            clock_timestamp(), :actorId, :actorId, :actorId
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("deliveryNo", orderNumber("DN"))
                .param("customerId", customerId)
                .param("exchangeRateId", exchangeRateId)
                .param("actorId", SYSTEM_USER_ID)
                .update();
    }

    private UUID createUser(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status,
                            must_change_password, created_by, updated_by
                        ) VALUES (
                            :id, 'USER', :loginId, :passwordHash, 'QA', :loginId, 'ACTIVE',
                            false, :actorId, :actorId
                        )
                        """)
                .param("id", id)
                .param("loginId", prefix + "-" + id)
                .param("passwordHash", passwordService.encode("a valid master data test password"))
                .param("actorId", SYSTEM_USER_ID)
                .update();
        return id;
    }

    private void assignRole(UUID userId, UUID roleId) {
        jdbc.sql("""
                        INSERT INTO identity.user_role (user_id, role_id, assigned_by)
                        VALUES (:userId, :roleId, :actorId)
                        """)
                .param("userId", userId)
                .param("roleId", roleId)
                .param("actorId", SYSTEM_USER_ID)
                .update();
    }

    private UUID createSession(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO identity.auth_session (
                            id, user_id, absolute_expires_at, idle_expires_at,
                            created_at, last_refreshed_at, client_ip, user_agent
                        ) VALUES (
                            :id, :userId, :absoluteExpiresAt, :idleExpiresAt,
                            :now, :now, CAST('198.51.100.99' AS inet), 'master-data-test'
                        )
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("absoluteExpiresAt", now.plusHours(8))
                .param("idleExpiresAt", now.plusHours(1))
                .param("now", now)
                .update();
        return sessionId;
    }

    private RequestPostProcessor jwtFor(UUID userId, UUID sessionId) {
        var user = identityRepository.findById(userId).orElseThrow();
        String token = jwtTokenService.issueSessionAccessToken(user, sessionId).token();
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private static String orderNumber(String prefix) {
        int value = Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000);
        return "%s-2026-%06d".formatted(prefix, value);
    }
}
