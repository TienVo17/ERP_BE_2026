package com.company.erp.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationImmutabilityTest {

    private static final Map<String, String> BASELINE_HASHES = new LinkedHashMap<>();

    static {
        BASELINE_HASHES.put("V001__create_schemas_and_system.sql", "498f636d1a9f8979b88673afd4dd9338aa42634b2c4c2e91201c76825cb4c48c");
        BASELINE_HASHES.put("V002__create_identity_and_audit.sql", "4c841f647c4cf0311ca18108df5ed30643c817d6ecb38255eeaadc35504f40bd");
        BASELINE_HASHES.put("V003__create_master_data.sql", "1be38ba959cf46e6739ea83a779c5c0ec63e66f32187f5ec0229d93c682ba023");
        BASELINE_HASHES.put("V004__create_sales.sql", "1603cf478491f45c4290676961869e8bff7ee8ec9ab712b2cae8e1369c3b7fc1");
        BASELINE_HASHES.put("V005__create_production.sql", "539b6bd559e63f663940de68a4fa57c2075333ed8a7eb2bab21a6c1df7b1f9d4");
        BASELINE_HASHES.put("V006__create_inventory.sql", "72e72272f535e861637b9a7e463a1660fa3c1f17b243f2a9564703f041a9c446");
        BASELINE_HASHES.put("V007__create_delivery.sql", "52f6f9d368530f6213103665cdce62b19972801d349173c46d99bb5f519df488");
        BASELINE_HASHES.put("V008__create_debit_projection_and_indexes.sql", "33be2e013033c7bb2d8896a990a1afd041c5ab0a7ca9bec67da67d8b485fb9db");
        BASELINE_HASHES.put("V009__seed_reference_data.sql", "c38c846a7e17a940bb5563680c71a4b85c7d171efc4c0351ff0ef33a84a2bd1c");
        BASELINE_HASHES.put("V010__harden_runtime_privileges.sql", "ecdb4b714d52daadfa8e02107f0b1fde25c65ba41c612698b30fa7ab7d35ad54");
        BASELINE_HASHES.put("V011__add_identity_sessions_and_sale_master_grants.sql", "f07d3ebe9e4964668e12bbe124954f827ceae3566796ca2f25f801aa16857f68");
    }

    @Test
    void preservesV001ThroughV011ByteForByte() throws Exception {
        for (Map.Entry<String, String> migration : BASELINE_HASHES.entrySet()) {
            assertThat(hash("db/migration/" + migration.getKey()))
                    .as(migration.getKey())
                    .isEqualTo(migration.getValue());
        }
    }

    private static String hash(String resource) throws IOException, NoSuchAlgorithmException {
        try (InputStream input = MigrationImmutabilityTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            byte[] content = input.readAllBytes();
            String normalized = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
            return toHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
