package com.company.erp.database;

import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reopen preserves the confirmed history. The database has to hold that line on its own, because the
 * command writes a header row and its item rows in an order the trigger cannot dictate: a handler
 * that advances the header first must not thereby unlock the items it is about to retire.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class BuyerOrderHistoryGuardIT {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SEEDED_UOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Autowired
    private DataSource dataSource;

    @Test
    void supersededItemsStayImmutableEvenWhenTheHeaderAdvancesFirst() throws SQLException {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID orderId = insertConfirmedOrder(jdbc);
        UUID itemId = itemOf(jdbc, orderId);

        // The reopen handler advances the header before touching any item.
        jdbc.sql("""
                        UPDATE sales.buyer_order
                        SET status = 'STANDBY', active_revision = active_revision + 1, updated_by = :actor
                        WHERE id = :id
                        """)
                .param("actor", SYSTEM_USER_ID)
                .param("id", orderId)
                .update();

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE sales.buyer_order_item
                        SET order_qty = 999, production_qty = 999, amount = 999, updated_by = :actor
                        WHERE id = :id
                        """)
                        .param("actor", SYSTEM_USER_ID)
                        .param("id", itemId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("retired");

        assertThat(jdbc.sql("SELECT order_qty FROM sales.buyer_order_item WHERE id = :id")
                .param("id", itemId)
                .query(java.math.BigDecimal.class)
                .single())
                .isEqualByComparingTo("10");
    }

    @Test
    void retiringASupersededItemRemainsTheOnePermittedChange() throws SQLException {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID orderId = insertConfirmedOrder(jdbc);
        UUID itemId = itemOf(jdbc, orderId);

        jdbc.sql("""
                        UPDATE sales.buyer_order
                        SET status = 'STANDBY', active_revision = active_revision + 1, updated_by = :actor
                        WHERE id = :id
                        """)
                .param("actor", SYSTEM_USER_ID)
                .param("id", orderId)
                .update();

        assertThat(jdbc.sql("""
                        UPDATE sales.buyer_order_item
                        SET status = 'CANCELLED', active_revision = false, updated_by = :actor
                        WHERE id = :id
                        """)
                .param("actor", SYSTEM_USER_ID)
                .param("id", itemId)
                .update()).isOne();

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM sales.buyer_order_item WHERE id = :id")
                .param("id", itemId)
                .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reopeningWithoutAdvancingTheRevisionIsRefused() throws SQLException {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID orderId = insertConfirmedOrder(jdbc);

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE sales.buyer_order
                        SET status = 'STANDBY', updated_by = :actor
                        WHERE id = :id
                        """)
                        .param("actor", SYSTEM_USER_ID)
                        .param("id", orderId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("active revision");
    }

    private UUID insertConfirmedOrder(JdbcClient jdbc) {
        UUID customerId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO master_data.customer (
                            id, short_name, name, currency_code, created_by, updated_by
                        ) VALUES (:id, :shortName, 'Guard Customer', 'USD', :actor, :actor)
                        """)
                .param("id", customerId)
                .param("shortName", "GUARD-" + customerId.toString().substring(0, 8))
                .param("actor", SYSTEM_USER_ID)
                .update();

        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO sales.buyer_order (
                            id, sys_po_no, order_type, customer_id, customer_name_snapshot,
                            customer_short_name_snapshot, pic_source, pic_name_snapshot, buyer_po,
                            po_date, delivery_date, created_by, updated_by
                        ) VALUES (
                            :id, :sysPoNo, 'STANDARD', :customerId, 'Guard Customer', 'GUARD',
                            'CUSTOM', 'PIC', 'PO', CURRENT_DATE, CURRENT_DATE, :actor, :actor
                        )
                        """)
                .param("id", orderId)
                .param("sysPoNo", "SO-2036-%06d".formatted(Math.floorMod(orderId.hashCode(), 1_000_000)))
                .param("customerId", customerId)
                .param("actor", SYSTEM_USER_ID)
                .update();

        jdbc.sql("""
                        INSERT INTO sales.buyer_order_item (
                            id, buyer_order_id, line_no, is_custom, product_kind_snapshot,
                            style_no_snapshot, name_snapshot, uom_id, uom_code_snapshot, order_qty,
                            production_qty, unit_price, currency_code, amount, created_by, updated_by
                        ) VALUES (
                            :id, :orderId, 1, true, 'PRINT', 'STYLE', 'Guard Item', :uomId, 'EA', 10,
                            10, 0, 'USD', 0, :actor, :actor
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("orderId", orderId)
                .param("uomId", SEEDED_UOM_ID)
                .param("actor", SYSTEM_USER_ID)
                .update();

        jdbc.sql("UPDATE sales.buyer_order SET status = 'CONFIRMED', updated_by = :actor WHERE id = :id")
                .param("actor", SYSTEM_USER_ID)
                .param("id", orderId)
                .update();
        return orderId;
    }

    private static UUID itemOf(JdbcClient jdbc, UUID orderId) {
        return jdbc.sql("SELECT id FROM sales.buyer_order_item WHERE buyer_order_id = :orderId")
                .param("orderId", orderId)
                .query(UUID.class)
                .single();
    }
}
