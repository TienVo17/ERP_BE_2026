package com.company.erp.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class TransactionOpenApiContractTest {

    private static final Path OPEN_API = Path.of("docs", "api", "erp-v1-openapi.yaml");

    private static final Set<String> TRANSACTION_PATHS = Set.of(
            "/api/v1/buyer-orders",
            "/api/v1/buyer-orders/{id}",
            "/api/v1/buyer-orders/{id}/copy",
            "/api/v1/buyer-orders/{id}/confirm",
            "/api/v1/buyer-orders/{id}/reopen",
            "/api/v1/production-orders",
            "/api/v1/production-orders/{id}",
            "/api/v1/production-orders/{id}/configuration",
            "/api/v1/production-orders/{id}/finish",
            "/api/v1/production-orders/{id}/events",
            "/api/v1/production-orders/{id}/document.pdf",
            "/api/v1/production-groups",
            "/api/v1/production-groups/{id}/ungroup",
            "/api/v1/stock-positions",
            "/api/v1/stock-positions/{id}",
            "/api/v1/stock-positions/{id}/movements",
            "/api/v1/stock-positions/{id}/eligible-return-sources",
            "/api/v1/stock-positions/{id}/returns",
            "/api/v1/stock-positions/{id}/disposals",
            "/api/v1/delivery-notes",
            "/api/v1/delivery-notes/{id}",
            "/api/v1/delivery-notes/{id}/post",
            "/api/v1/delivery-notes/{id}/reverse",
            "/api/v1/delivery-notes/{id}/events",
            "/api/v1/delivery-notes/{id}/document.pdf",
            "/api/v1/delivery-sources",
            "/api/v1/debit-notes",
            "/api/v1/debit-notes/export.xlsx");

    private static final Map<String, String> REQUIRED_PERMISSIONS = Map.ofEntries(
            permission("get /api/v1/buyer-orders", "BUYER_ORDER:VIEW"),
            permission("post /api/v1/buyer-orders", "BUYER_ORDER:CREATE"),
            permission("get /api/v1/buyer-orders/{id}", "BUYER_ORDER:VIEW"),
            permission("put /api/v1/buyer-orders/{id}", "BUYER_ORDER:UPDATE"),
            permission("post /api/v1/buyer-orders/{id}/copy", "BUYER_ORDER:CREATE"),
            permission("post /api/v1/buyer-orders/{id}/confirm", "BUYER_ORDER:CONFIRM"),
            permission("post /api/v1/buyer-orders/{id}/reopen", "BUYER_ORDER:REOPEN"),
            permission("get /api/v1/production-orders", "PRODUCTION:VIEW"),
            permission("get /api/v1/production-orders/{id}", "PRODUCTION:VIEW"),
            permission("put /api/v1/production-orders/{id}/configuration", "PRODUCTION:CONFIGURE"),
            permission("post /api/v1/production-orders/{id}/finish", "PRODUCTION:FINISH"),
            permission("get /api/v1/production-orders/{id}/events", "PRODUCTION:VIEW"),
            permission("get /api/v1/production-orders/{id}/document.pdf", "PRODUCTION:VIEW"),
            permission("post /api/v1/production-groups", "PRODUCTION:GROUP"),
            permission("post /api/v1/production-groups/{id}/ungroup", "PRODUCTION:GROUP"),
            permission("get /api/v1/stock-positions", "STOCK:VIEW"),
            permission("get /api/v1/stock-positions/{id}", "STOCK:VIEW"),
            permission("get /api/v1/stock-positions/{id}/movements", "STOCK:VIEW"),
            permission("get /api/v1/stock-positions/{id}/eligible-return-sources", "STOCK:RETURN"),
            permission("post /api/v1/stock-positions/{id}/returns", "STOCK:RETURN"),
            permission("post /api/v1/stock-positions/{id}/disposals", "STOCK:DISPOSE"),
            permission("get /api/v1/delivery-notes", "DELIVERY:VIEW"),
            permission("post /api/v1/delivery-notes", "DELIVERY:CREATE"),
            permission("get /api/v1/delivery-notes/{id}", "DELIVERY:VIEW"),
            permission("put /api/v1/delivery-notes/{id}", "DELIVERY:CREATE"),
            permission("post /api/v1/delivery-notes/{id}/post", "DELIVERY:POST"),
            permission("post /api/v1/delivery-notes/{id}/reverse", "DELIVERY:REVERSE"),
            permission("get /api/v1/delivery-notes/{id}/events", "DELIVERY:VIEW"),
            permission("get /api/v1/delivery-notes/{id}/document.pdf", "DELIVERY:PRINT"),
            permission("get /api/v1/delivery-sources", "DELIVERY:CREATE"),
            permission("get /api/v1/debit-notes", "DELIVERY:VIEW"),
            permission("get /api/v1/debit-notes/export.xlsx", "DELIVERY:EXPORT"));

    private static final Set<String> IDEMPOTENT_OPERATIONS = Set.of(
            "post /api/v1/buyer-orders",
            "put /api/v1/buyer-orders/{id}",
            "post /api/v1/buyer-orders/{id}/copy",
            "post /api/v1/buyer-orders/{id}/confirm",
            "post /api/v1/buyer-orders/{id}/reopen",
            "put /api/v1/production-orders/{id}/configuration",
            "post /api/v1/production-orders/{id}/finish",
            "post /api/v1/production-groups",
            "post /api/v1/production-groups/{id}/ungroup",
            "post /api/v1/stock-positions/{id}/returns",
            "post /api/v1/stock-positions/{id}/disposals",
            "post /api/v1/delivery-notes",
            "put /api/v1/delivery-notes/{id}",
            "post /api/v1/delivery-notes/{id}/post",
            "post /api/v1/delivery-notes/{id}/reverse");

    private static final Set<String> VERSIONED_OPERATIONS = Set.of(
            "put /api/v1/buyer-orders/{id}",
            "post /api/v1/buyer-orders/{id}/copy",
            "post /api/v1/buyer-orders/{id}/confirm",
            "post /api/v1/buyer-orders/{id}/reopen",
            "put /api/v1/production-orders/{id}/configuration",
            "post /api/v1/production-orders/{id}/finish",
            "post /api/v1/production-groups/{id}/ungroup",
            "post /api/v1/stock-positions/{id}/returns",
            "post /api/v1/stock-positions/{id}/disposals",
            "put /api/v1/delivery-notes/{id}",
            "post /api/v1/delivery-notes/{id}/post",
            "post /api/v1/delivery-notes/{id}/reverse");

    @Test
    void publishesTheCompleteApprovedTransactionSurface() throws IOException {
        Map<String, Object> paths = paths();

        assertThat(paths.keySet()).containsAll(TRANSACTION_PATHS);
        assertThat(TRANSACTION_PATHS).allSatisfy(path -> assertThat(paths.get(path)).isNotNull());
    }

    @Test
    void mapsEveryOperationToTheApprovedSeededPermission() throws IOException {
        Map<String, Object> paths = paths();

        REQUIRED_PERMISSIONS.forEach((key, permission) -> {
            OperationKey operationKey = operationKey(key);
            assertThat(operation(paths, operationKey).get("x-required-permission"))
                    .as(key)
                    .isEqualTo(permission);
        });

        assertThat(Set.copyOf(REQUIRED_PERMISSIONS.values())).containsExactlyInAnyOrderElementsOf(Set.of(
                "BUYER_ORDER:VIEW", "BUYER_ORDER:CREATE", "BUYER_ORDER:UPDATE", "BUYER_ORDER:CONFIRM",
                "BUYER_ORDER:REOPEN", "PRODUCTION:VIEW", "PRODUCTION:GROUP", "PRODUCTION:CONFIGURE",
                "PRODUCTION:FINISH", "DELIVERY:VIEW", "DELIVERY:CREATE", "DELIVERY:POST",
                "DELIVERY:REVERSE", "DELIVERY:PRINT", "DELIVERY:EXPORT", "STOCK:VIEW",
                "STOCK:RETURN", "STOCK:DISPOSE"));
    }

    @Test
    void requiresQuotedVersionsAndDurableIdempotencyWhereTheContractNeedsThem() throws IOException {
        Map<String, Object> paths = paths();

        IDEMPOTENT_OPERATIONS.forEach(key -> assertParameter(operation(paths, operationKey(key)), "IdempotencyKey"));
        VERSIONED_OPERATIONS.forEach(key -> assertParameter(operation(paths, operationKey(key)), "IfMatch"));

        Map<String, Object> parameters = components("parameters");
        assertThat(map(parameters.get("IdempotencyKey")))
                .containsEntry("required", true)
                .containsEntry("in", "header");
        assertThat(map(map(parameters.get("IdempotencyKey")).get("schema")))
                .containsEntry("type", "string")
                .containsEntry("maxLength", 120);
        assertThat(map(map(parameters.get("IfMatch")).get("schema")).get("pattern")).isEqualTo("^\"[0-9]+\"$");
    }

    @Test
    void closesTransactionRequestsAndRejectsServerOwnedFields() throws IOException {
        Map<String, Object> schemas = schemas();
        Set<String> serverOwned = Set.of(
                "id", "version", "status", "sysPoNo", "productionNo", "productNo", "qrValue", "groupNo",
                "deliveryNo", "amount", "totalQty", "totalAmount", "balanceAfter", "currentQty", "producedQty",
                "createdAt", "createdBy", "updatedAt", "updatedBy", "postedAt", "postedBy", "reversedAt",
                "reversedBy", "actorUserId", "customerNameSnapshot", "exchangeRateId");
        Set<String> requests = Set.of(
                "BuyerOrderCreateRequest", "BuyerOrderUpdateRequest", "BuyerOrderCopyRequest",
                "BuyerOrderCommandRequest", "StandardBuyerOrderItemRequest", "CustomBuyerOrderItemRequest",
                "ProductionGroupCreateRequest", "ProductionConfigurationRequest", "ProductionCommandRequest",
                "StockReturnRequest", "StockDisposalRequest",
                "DeliveryNoteCreateRequest", "DeliveryNoteUpdateRequest", "DeliveryCommandRequest");

        requests.forEach(name -> {
            Map<String, Object> schema = map(schemas.get(name));
            assertThat(schema.get("additionalProperties")).as(name).isEqualTo(false);
            assertThat(properties(schema).keySet()).as(name).doesNotContainAnyElementsOf(serverOwned);
        });

        assertThat(properties(map(schemas.get("BuyerOrderItemRequest"))).keySet())
                .doesNotContain("lineNo", "useStockQty", "productionQty", "amount");
        assertThat(properties(map(schemas.get("DeliveryNoteItemRequest"))).keySet())
                .doesNotContain("lineNo", "amount", "deliveryMovementId", "reversalMovementId");
    }

    @Test
    void separatesStandardFinishedGoodReferencesFromCustomSnapshots() throws IOException {
        Map<String, Object> schemas = schemas();
        Map<String, Object> item = map(schemas.get("BuyerOrderItemRequest"));

        assertThat(list(item.get("oneOf")))
                .extracting(variant -> map(variant).get("$ref"))
                .containsExactlyInAnyOrder(
                        "#/components/schemas/StandardBuyerOrderItemRequest",
                        "#/components/schemas/CustomBuyerOrderItemRequest");

        Map<String, Object> standard = map(schemas.get("StandardBuyerOrderItemRequest"));
        assertThat(list(standard.get("required"))).contains("finishedGoodId", "orderQty", "unitPrice");
        assertThat(properties(standard).keySet())
                .contains("finishedGoodId")
                .doesNotContain("productKind", "styleNo", "name", "size", "color", "uomId", "currencyCode");
        assertThat(map(properties(standard).get("isCustom"))).containsEntry("const", false);
        assertThat(standard.get("description").toString())
                .contains("Customer", "currency")
                .doesNotContain("currency snapshots are derived from the referenced ACTIVE Finished Good");

        Map<String, Object> custom = map(schemas.get("CustomBuyerOrderItemRequest"));
        assertThat(list(custom.get("required")))
                .contains("productKind", "styleNo", "name", "uomId", "currencyCode", "orderQty", "unitPrice");
        assertThat(properties(custom).keySet()).doesNotContain("finishedGoodId");
        assertThat(map(properties(custom).get("isCustom"))).containsEntry("const", true);
    }

    @Test
    void boundsBuyerOrderTextToKeepCommandResponsesReplayable() throws IOException {
        Map<String, Object> schemas = schemas();

        assertThat(map(properties(map(schemas.get("StandardBuyerOrderItemRequest"))).get("remark")))
                .containsEntry("maxLength", 200);
        Map<String, Object> custom = properties(map(schemas.get("CustomBuyerOrderItemRequest")));
        assertThat(map(custom.get("styleNo"))).containsEntry("maxLength", 120);
        assertThat(map(custom.get("name"))).containsEntry("maxLength", 200);
        assertThat(map(custom.get("size"))).containsEntry("maxLength", 120);
        assertThat(map(custom.get("color"))).containsEntry("maxLength", 120);
        assertThat(map(custom.get("remark"))).containsEntry("maxLength", 200);
        assertThat(map(properties(map(schemas.get("BuyerOrderCommandRequest"))).get("reason")))
                .containsEntry("maxLength", 200);
        for (String name : List.of("BuyerOrderCreateRequest", "BuyerOrderUpdateRequest")) {
            Map<String, Object> properties = properties(map(schemas.get(name)));
            assertThat(map(properties.get("orderType"))).containsEntry("maxLength", 80);
            assertThat(map(properties.get("picName"))).containsEntry("maxLength", 200);
            assertThat(map(properties.get("buyerPo"))).containsEntry("maxLength", 120);
        }
    }

    @Test
    void capsCollectionsAndCarriesEveryBusinessDecimalAsAString() throws IOException {
        Map<String, Object> schemas = schemas();

        assertArrayCap(schemas, "BuyerOrderCreateRequest", "items", 100);
        assertArrayCap(schemas, "BuyerOrderUpdateRequest", "items", 100);
        assertArrayCap(schemas, "DeliveryNoteCreateRequest", "items", 100);
        assertArrayCap(schemas, "DeliveryNoteUpdateRequest", "items", 100);
        assertArrayCap(schemas, "ProductionGroupCreateRequest", "members", 50);
        assertArrayCap(schemas, "ProductionConfigurationRequest", "processes", 100);
        assertArrayCap(schemas, "ProductionConfigurationRequest", "yarnLines", 100);

        Set<String> decimalSchemas = Set.of("DecimalQuantity", "DecimalPrice", "DecimalRate", "DecimalAmount", "DecimalPercent");
        decimalSchemas.forEach(name -> assertThat(map(schemas.get(name)).get("type")).as(name).isEqualTo("string"));

        String percentPattern = map(schemas.get("DecimalPercent")).get("pattern").toString();
        assertThat(Set.of("0", "0.0000", "99.9999", "100", "100.0000"))
                .allSatisfy(value -> assertThat(value).matches(percentPattern));
        assertThat(Set.of("-1", "100.0001", "101", "999.9999"))
                .allSatisfy(value -> assertThat(value).doesNotMatch(percentPattern));
    }

    @Test
    void publishesMinimalSourceViewsWithoutConferringDeliveryView() throws IOException {
        Map<String, Object> schemas = schemas();
        Map<String, Object> paths = paths();

        assertThat(operation(paths, operationKey("get /api/v1/delivery-sources")).get("x-required-permission"))
                .isEqualTo("DELIVERY:CREATE");
        assertThat(properties(map(schemas.get("DeliverySourceResponse"))).keySet())
                .contains("stockPositionId", "buyerOrderId", "productionOrderId", "customerId", "currencyCode", "availableQty")
                .doesNotContain("deliveryNote", "deliveryEvents", "exchangeRate", "audit");

        assertThat(operation(paths, operationKey("get /api/v1/stock-positions/{id}/eligible-return-sources"))
                .get("x-required-permission")).isEqualTo("STOCK:RETURN");
        assertThat(properties(map(schemas.get("EligibleReturnSourceResponse"))).keySet())
                .containsExactlyInAnyOrder(
                        "deliveryNoteItemId", "deliveryNo", "deliveryDate", "deliveredQty", "netReturnableQty", "uomCode");
    }

    @Test
    void definesBinaryReportsAndNoRetentionResponseHeaders() throws IOException {
        Map<String, Object> paths = paths();

        assertBinaryReport(paths, "/api/v1/production-orders/{id}/document.pdf", "application/pdf");
        assertBinaryReport(paths, "/api/v1/delivery-notes/{id}/document.pdf", "application/pdf");
        assertBinaryReport(paths, "/api/v1/debit-notes/export.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        assertThat(operation(paths, operationKey("get /api/v1/debit-notes/export.xlsx")).get("description").toString())
                .contains("50,000", "formula", "never persisted");
    }

    @Test
    void documentsStableTransactionErrorsAndRetryAfterSemantics() throws IOException {
        Map<String, Object> schemas = schemas();
        Set<String> requiredCodes = Set.of(
                "IF_MATCH_REQUIRED", "INVALID_IF_MATCH", "IDEMPOTENCY_KEY_REQUIRED", "INVALID_IDEMPOTENCY_KEY",
                "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS", "IDEMPOTENCY_RESULT_EXPIRED",
                "INVALID_STATE_TRANSITION", "DOWNSTREAM_ACTIVITY_EXISTS", "GROUP_MEMBERSHIP_INVALID",
                "PRODUCTION_ALREADY_FINISHED", "INSUFFICIENT_STOCK", "DELIVERY_ALREADY_POSTED",
                "DELIVERY_ALREADY_REVERSED", "DELIVERY_HAS_RETURNS", "RETURN_LIMIT_EXCEEDED",
                "DISPOSAL_EXCEEDS_STOCK", "EXCHANGE_RATE_MISSING", "REPORT_LIMIT_EXCEEDED", "REPORT_BUSY");

        assertThat(list(map(properties(map(schemas.get("ProblemDetail"))).get("code")).get("enum")))
                .extracting(Object::toString)
                .containsAll(requiredCodes);

        Map<String, Object> responses = components("responses");
        assertRetryAfter(responses, "IdempotencyInProgress", 2);
        assertRetryAfter(responses, "ReportBusy", 5);
    }

    @Test
    void keepsDebitListAndExportFiltersAndSortsInLockstep() throws IOException {
        Map<String, Object> paths = paths();
        Map<String, Object> listOperation = operation(paths, operationKey("get /api/v1/debit-notes"));
        Map<String, Object> exportOperation = operation(paths, operationKey("get /api/v1/debit-notes/export.xlsx"));

        assertThat(parameterNames(exportOperation))
                .containsExactlyInAnyOrderElementsOf(parameterNames(listOperation).stream()
                        .filter(name -> !Set.of("Page", "Size").contains(name))
                        .toList());
        assertThat(exportOperation.get("description").toString()).contains("50,000", "formula", "never persisted");
    }

    @Test
    void documentsSinglePredecessorAndSuccessorReplacementLineage() throws IOException {
        Map<String, Object> delivery = map(schemas().get("DeliveryNoteResponse"));

        assertThat(delivery.get("description").toString())
                .contains("one predecessor", "one successor", "linear", "acyclic");
    }

    @Test
    void modelsReplacementLineageHistoryAndServerGeneratedProductionIdentifiers() throws IOException {
        Map<String, Object> schemas = schemas();

        assertThat(properties(map(schemas.get("DeliveryReverseResponse"))).keySet())
                .containsExactlyInAnyOrder("reversedDelivery", "replacementDraft");
        assertThat(properties(map(schemas.get("DeliveryNoteResponse"))).keySet())
                .contains("replacesDeliveryId", "replacementDeliveryId", "events");
        assertThat(properties(map(schemas.get("ProductionOrderResponse"))).keySet())
                .contains("productionNo", "productNo", "qrValue", "groupNo");
        assertThat(properties(map(schemas.get("ProductionConfigurationRequest"))).keySet())
                .doesNotContain("productionNo", "productNo", "qrValue", "groupNo");
        Map<String, Object> groupMembers = map(
                properties(map(schemas.get("ProductionGroupResponse"))).get("members"));
        assertThat(map(groupMembers.get("items")).get("$ref").toString())
                .endsWith("/ProductionGroupMemberResponse");
        assertThat(properties(map(schemas.get("ProductionGroupMemberResponse"))).keySet())
                .containsExactlyInAnyOrder(
                        "id", "version", "productionNo", "productNo", "groupId", "groupNo",
                        "buyerOrderId", "buyerOrderItemId", "productKind", "plannedQty", "status");
        assertThat(properties(map(schemas.get("ProductionGroupMemberResponse"))).keySet())
                .doesNotContain("configuration", "events");
        assertBoundedText(schemas, "ProductionCommandRequest", "reason", 200);
        assertBoundedText(schemas, "ProductionYarnLineRequest", "yarn", 120);
        assertBoundedText(schemas, "ProductionYarnLineRequest", "yarnCode", 120);
        assertBoundedText(schemas, "ProductionYarnLineRequest", "denier", 120);
        assertBoundedText(schemas, "StockReturnRequest", "reason", 200);
        assertBoundedText(schemas, "StockDisposalRequest", "reason", 200);
        assertBoundedText(schemas, "ProductionGroupCreateRequest", "reason", 200);
        assertBoundedText(schemas, "ProductionConfigurationRequest", "tenDia", 120);
        assertBoundedText(schemas, "ProductionConfigurationRequest", "matDo", 120);
        assertBoundedText(schemas, "ProductionConfigurationRequest", "pick", 120);
        assertBoundedText(schemas, "ProductionConfigurationRequest", "remark", 200);
    }

    private static void assertBinaryReport(Map<String, Object> paths, String path, String mediaType) {
        Map<String, Object> response = map(operation(paths, new OperationKey("get", path)).get("responses"));
        Map<String, Object> success = map(response.get("200"));
        assertThat(map(success.get("headers")).keySet()).contains("Content-Disposition", "Cache-Control");
        Map<String, Object> media = map(map(success.get("content")).get(mediaType));
        assertThat(map(media.get("schema"))).containsEntry("type", "string").containsEntry("format", "binary");
    }

    private static void assertRetryAfter(Map<String, Object> responses, String responseName, int seconds) {
        Map<String, Object> retryAfter = map(map(map(responses.get(responseName)).get("headers")).get("Retry-After"));
        assertThat(list(map(retryAfter.get("schema")).get("enum")))
                .containsExactly(seconds);
    }

    private static Set<String> parameterNames(Map<String, Object> operation) {
        return list(operation.get("parameters")).stream()
                .map(TransactionOpenApiContractTest::map)
                .map(parameter -> parameter.containsKey("name")
                        ? parameter.get("name").toString()
                        : parameter.get("$ref").toString().substring("#/components/parameters/".length()))
                .collect(Collectors.toSet());
    }

    /** Java bean validation and the frozen contract must publish the same text bounds. */
    private static void assertBoundedText(
            Map<String, Object> schemas, String schemaName, String field, int maxLength) {
        Map<String, Object> property = map(properties(map(schemas.get(schemaName))).get(field));
        assertThat(property).as(schemaName + "." + field).containsEntry("maxLength", maxLength);
    }

    private static void assertArrayCap(Map<String, Object> schemas, String schemaName, String field, int max) {
        Map<String, Object> array = map(properties(map(schemas.get(schemaName))).get(field));
        assertThat(array).containsEntry("type", "array").containsEntry("maxItems", max);
    }

    private static void assertParameter(Map<String, Object> operation, String expectedName) {
        assertThat(parameterNames(operation)).contains(expectedName);
    }

    private static Map.Entry<String, String> permission(String operation, String permission) {
        return Map.entry(operation, permission);
    }

    private static OperationKey operationKey(String key) {
        int separator = key.indexOf(' ');
        return new OperationKey(key.substring(0, separator), key.substring(separator + 1));
    }

    private static Map<String, Object> operation(Map<String, Object> paths, OperationKey key) {
        return map(map(paths.get(key.path())).get(key.method()));
    }

    private static Map<String, Object> paths() throws IOException {
        return map(load().get("paths"));
    }

    private static Map<String, Object> schemas() throws IOException {
        return components("schemas");
    }

    private static Map<String, Object> components(String name) throws IOException {
        return map(map(load().get("components")).get(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() throws IOException {
        Object value = new Yaml().load(Files.readString(OPEN_API));
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value == null ? new LinkedHashMap<>() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static Map<String, Object> properties(Map<String, Object> schema) {
        return map(schema.get("properties"));
    }

    private record OperationKey(String method, String path) {}
}
