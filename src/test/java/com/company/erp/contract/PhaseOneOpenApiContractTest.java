package com.company.erp.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PhaseOneOpenApiContractTest {

    private static final Path OPEN_API = Path.of("docs", "api", "erp-v1-openapi.yaml");
    private static final Set<String> REQUIRED_PATHS = Set.of(
            "/api/v1/auth/csrf",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/me",
            "/api/v1/auth/sessions",
            "/api/v1/auth/sessions/{sessionId}/revoke",
            "/api/v1/auth/change-password",
            "/api/v1/admin/users",
            "/api/v1/admin/users/{userId}",
            "/api/v1/admin/users/{userId}/status",
            "/api/v1/admin/users/{userId}/roles",
            "/api/v1/admin/users/{userId}/permission-overrides",
            "/api/v1/admin/users/{userId}/reset-password",
            "/api/v1/admin/roles",
            "/api/v1/admin/roles/{roleId}",
            "/api/v1/admin/permissions",
            "/api/v1/admin/login-events",
            "/api/v1/admin/ip-allowlist",
            "/api/v1/admin/ip-allowlist/{entryId}",
            "/api/v1/master-data/currencies",
            "/api/v1/master-data/uoms",
            "/api/v1/master-data/uoms/{id}",
            "/api/v1/master-data/uoms/{id}/archive",
            "/api/v1/master-data/exchange-rates",
            "/api/v1/master-data/exchange-rates/{id}",
            "/api/v1/master-data/exchange-rates/{id}/archive",
            "/api/v1/master-data/customers",
            "/api/v1/master-data/customers/{id}",
            "/api/v1/master-data/customers/{id}/archive",
            "/api/v1/master-data/customers/{id}/contacts",
            "/api/v1/master-data/customers/{id}/contacts/{contactId}",
            "/api/v1/master-data/customers/{id}/contacts/{contactId}/archive",
            "/api/v1/master-data/suppliers",
            "/api/v1/master-data/suppliers/{id}",
            "/api/v1/master-data/suppliers/{id}/archive",
            "/api/v1/master-data/suppliers/{id}/contacts",
            "/api/v1/master-data/suppliers/{id}/contacts/{contactId}",
            "/api/v1/master-data/suppliers/{id}/contacts/{contactId}/archive",
            "/api/v1/master-data/processes",
            "/api/v1/master-data/processes/{id}",
            "/api/v1/master-data/processes/{id}/archive",
            "/api/v1/master-data/raw-materials",
            "/api/v1/master-data/raw-materials/{id}",
            "/api/v1/master-data/raw-materials/{id}/archive",
            "/api/v1/master-data/finished-goods",
            "/api/v1/master-data/finished-goods/{id}",
            "/api/v1/master-data/finished-goods/{id}/archive");

    @Test
    void publishesAllPhaseOneContractDocuments() {
        assertThat(List.of(
                        Path.of("docs", "operational-core-v1-spec.md"),
                        Path.of("docs", "api", "api-conventions.md"),
                        OPEN_API,
                        Path.of("docs", "security", "authentication-and-authorization.md")))
                .allSatisfy(path -> assertThat(path).exists().isRegularFile());
    }

    @Test
    void isSemanticallyValidOpenApi31() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser()
                .readLocation(OPEN_API.toAbsolutePath().toUri().toString(), null, options);

        assertThat(result.getMessages()).isEmpty();
        OpenAPI parsed = result.getOpenAPI();
        assertThat(parsed).isNotNull();
        assertThat(parsed.getOpenapi()).isEqualTo("3.1.0");
    }

    @Test
    void definesTheCompletePhaseOneApiSurface() throws IOException {
        Map<String, Object> document = loadOpenApi();

        assertThat(document.get("openapi")).isEqualTo("3.1.0");
        assertThat(map(document.get("paths")).keySet()).containsExactlyInAnyOrderElementsOf(REQUIRED_PATHS);
    }

    @Test
    void declaresCookieBearerAndRestrictedChallengeFlows() throws IOException {
        Map<String, Object> document = loadOpenApi();
        Map<String, Object> schemes = map(map(document.get("components")).get("securitySchemes"));
        Map<String, Object> schemas = schemas(document);
        Map<String, Object> paths = map(document.get("paths"));

        assertThat(map(schemes.get("refreshCookie")))
                .containsEntry("type", "apiKey")
                .containsEntry("in", "cookie")
                .containsEntry("name", "ERP_REFRESH");
        assertThat(map(schemes.get("bearerAuth"))).containsEntry("scheme", "bearer");
        assertThat(map(schemes.get("csrfToken"))).containsEntry("name", "X-CSRF-Token");

        Map<String, Object> loginResponse = map(schemas.get("LoginResponse"));
        assertThat(list(loginResponse.get("oneOf"))).extracting(value -> map(value).get("$ref"))
                .containsExactlyInAnyOrder(
                        "#/components/schemas/NormalAuthResponse",
                        "#/components/schemas/PasswordChangeChallengeResponse");
        assertThat(properties(schemas, "NormalAuthResponse")).containsKeys("accessToken", "purpose");
        assertThat(map(properties(schemas, "NormalAuthResponse").get("accessToken")))
                .doesNotContainKey("writeOnly");
        assertThat(properties(schemas, "PasswordChangeChallengeResponse"))
                .containsKeys("accessToken", "purpose", "expiresAt");
        assertThat(map(loginResponse.get("discriminator")).get("propertyName")).isEqualTo("purpose");
        assertThat(required(schemas, "NormalAuthResponse")).contains("purpose");
        assertThat(required(schemas, "PasswordChangeChallengeResponse")).contains("purpose");

        assertCombinedSecurity(paths, "/api/v1/auth/refresh", "post", "refreshCookie", "csrfToken");
        assertThat(security(paths, "/api/v1/auth/logout", "post"))
                .anySatisfy(requirement -> assertThat(requirement.keySet()).contains("refreshCookie", "csrfToken"))
                .anySatisfy(requirement -> assertThat(requirement.keySet()).containsExactly("bearerAuth"));
    }

    @Test
    void mapsAdminCommandsToSeparatePermissionBoundaries() throws IOException {
        Map<String, Object> document = loadOpenApi();
        Map<String, Object> schemas = schemas(document);
        Map<String, Object> paths = map(document.get("paths"));

        assertThat(properties(schemas, "UserCreateRequest"))
                .containsKeys("kind", "loginId", "temporaryPassword", "position", "name")
                .doesNotContainKeys("roleIds", "permissionOverrides");
        assertThat(properties(schemas, "UserProfileUpdateRequest"))
                .doesNotContainKeys("loginId", "temporaryPassword", "roleIds", "permissionOverrides");
        assertThat(properties(schemas, "PermissionOverrideRequest"))
                .containsKeys("permissionId", "effect")
                .doesNotContainKeys("module", "action");

        assertThat(operation(paths, "/api/v1/admin/users/{userId}/status", "put").get("x-required-permission"))
                .isEqualTo("ADMIN:MANAGE_USERS");
        assertThat(operation(paths, "/api/v1/admin/users/{userId}/roles", "put").get("x-required-permission"))
                .isEqualTo("ADMIN:MANAGE_ROLES");
        assertThat(operation(paths, "/api/v1/admin/users/{userId}/permission-overrides", "put")
                .get("x-required-permission")).isEqualTo("ADMIN:MANAGE_ROLES");
    }

    @Test
    void matchesMonthlyRateAndContactPersistenceAggregates() throws IOException {
        Map<String, Object> schemas = schemas(loadOpenApi());

        assertThat(properties(schemas, "ExchangeRateRequest"))
                .containsKeys("month", "vndUsdRate", "wonUsdRate")
                .doesNotContainKeys("currency", "rate");
        assertThat(required(schemas, "ExchangeRateRequest"))
                .contains("month", "vndUsdRate", "wonUsdRate");

        assertThat(properties(schemas, "ContactCreateRequest"))
                .containsKeys("division", "name", "isDefault");
        assertThat(properties(schemas, "ContactUpdateRequest"))
                .containsKeys("division", "name", "isDefault");
        assertThat(properties(schemas, "ContactResponse"))
                .containsKeys("id", "version", "status", "division", "name", "isDefault", "createdAt", "updatedAt");
    }

    @Test
    void preservesDatabaseNullabilityAndImmutableBusinessKeys() throws IOException {
        Map<String, Object> schemas = schemas(loadOpenApi());

        assertThat(required(schemas, "ProcessCreateRequest")).contains("qrValue");
        assertThat(properties(schemas, "ProcessUpdateRequest")).doesNotContainKey("qrValue");
        assertThat(properties(schemas, "CustomerUpdateRequest")).doesNotContainKey("shortName");
        assertThat(required(schemas, "RawMaterialRequest")).doesNotContain("referencePrice");
        assertThat(required(schemas, "FinishedGoodRequest")).doesNotContain("referencePrice", "currencyCode");
        assertThat(map(properties(schemas, "RawMaterialRequest").get("referencePrice")).get("$ref"))
                .isEqualTo("#/components/schemas/DecimalPrice");
    }

    @Test
    void parsesRepresentativeDecimalMonthAndSortValues() throws IOException {
        Map<String, Object> document = loadOpenApi();
        Map<String, Object> schemas = schemas(document);
        Map<String, Object> parameters = map(map(document.get("components")).get("parameters"));

        assertMatches(schemas, "DecimalQuantity", "0", "12345678901234.1234");
        assertMatches(schemas, "DecimalPrice", "0", "123456789012.123456");
        assertMatches(schemas, "DecimalRate", "0.000001", "123456789012.123456");
        assertThat(Pattern.matches((String) map(schemas.get("YearMonth")).get("pattern"), "2026-07")).isTrue();

        Map<String, Object> sort = map(parameters.get("Sort"));
        assertThat(map(sort.get("schema")).get("type")).isEqualTo("array");
        assertThat(sort.get("explode")).isEqualTo(true);
        assertThat(map(map(sort.get("schema")).get("items")).get("example")).isInstanceOf(String.class);
    }

    @Test
    void bindsEveryDeclaredPermissionToTheSeededCatalog() throws IOException {
        Set<String> seeded = seededPermissions();
        Map<String, Object> paths = map(loadOpenApi().get("paths"));

        assertThat(seeded).contains("ADMIN:MANAGE_USERS", "ADMIN:MANAGE_ROLES", "ETC:VIEW", "FINISHED_GOODS:ARCHIVE");
        paths.forEach((path, rawItem) -> map(rawItem).forEach((method, rawOperation) -> {
            Object declared = map(rawOperation).get("x-required-permission");
            if (declared != null) {
                assertThat(seeded)
                        .as("permission declared by %s %s", method, path)
                        .contains(declared.toString());
            }
        }));
    }

    @Test
    void documentsAuthorizationFailuresOnEveryPermissionGuardedOperation() throws IOException {
        Map<String, Object> paths = map(loadOpenApi().get("paths"));

        paths.forEach((path, rawItem) -> map(rawItem).forEach((method, rawOperation) -> {
            Map<String, Object> operation = map(rawOperation);
            if (operation.containsKey("x-required-permission")) {
                assertThat(map(operation.get("responses")).keySet())
                        .as("Problem responses for %s %s", method, path)
                        .contains("401", "403");
            }
        }));
    }

    @Test
    void documentsSortAllowlistFieldsThatActuallyExist() throws IOException {
        Map<String, Object> document = loadOpenApi();
        Map<String, Object> schemas = schemas(document);
        Pattern allowlist = Pattern.compile("Sort allowlist is ([^.]+)\\.");

        map(document.get("paths")).forEach((path, rawItem) -> map(rawItem).forEach((method, rawOperation) -> {
            Map<String, Object> operation = map(rawOperation);
            if (!"get".equals(method) || !isPagedList(operation)) {
                return;
            }
            String description = describeSuccess(operation);
            assertThat(allowlist.matcher(description).find())
                    .as("documented sort allowlist for %s", operation.get("operationId"))
                    .isTrue();

            java.util.regex.Matcher matcher = allowlist.matcher(description);
            matcher.find();
            Set<String> itemProperties = pagedItemProperties(schemas, operation);
            for (String field : matcher.group(1).split(",| and ")) {
                String candidate = field.trim();
                if (candidate.isEmpty() || "id".equals(candidate)) {
                    continue;
                }
                assertThat(itemProperties)
                        .as("sort field %s of %s", candidate, operation.get("operationId"))
                        .contains(candidate);
            }
        }));
    }

    private static boolean isPagedList(Map<String, Object> operation) {
        Object parameters = operation.get("parameters");
        return parameters instanceof List<?> values
                && values.stream().map(PhaseOneOpenApiContractTest::map)
                        .anyMatch(parameter -> "#/components/parameters/Sort".equals(parameter.get("$ref")));
    }

    private static String describeSuccess(Map<String, Object> operation) {
        Object description = map(map(operation.get("responses")).get("200")).get("description");
        return description == null ? "" : description.toString();
    }

    private static Set<String> pagedItemProperties(Map<String, Object> schemas, Map<String, Object> operation) {
        String pageRef = map(map(map(map(map(operation.get("responses")).get("200")).get("content"))
                        .get("application/json")).get("schema")).get("$ref").toString();
        Map<String, Object> pageSchema = map(schemas.get(schemaName(pageRef)));
        String itemRef = map(map(map(pageSchema.get("properties")).get("items")).get("items")).get("$ref").toString();
        return propertyNames(map(schemas.get(schemaName(itemRef))));
    }

    private static String schemaName(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    private static Set<String> seededPermissions() throws IOException {
        String seed = Files.readString(Path.of("src", "main", "resources", "db", "migration", "V009__seed_reference_data.sql"));
        java.util.regex.Matcher matcher = Pattern
                .compile("'[0-9a-f-]{36}', '([A-Z][A-Z0-9_]*)', '([A-Z][A-Z0-9_]*)'")
                .matcher(seed);
        Set<String> permissions = new java.util.HashSet<>();
        while (matcher.find()) {
            permissions.add(matcher.group(1) + ":" + matcher.group(2));
        }
        return permissions;
    }

    @Test
    void declaresSecurityErrorPaginationAndAllowlistedFilterContracts() throws IOException {
        Map<String, Object> document = loadOpenApi();
        Map<String, Object> schemas = schemas(document);
        Map<String, Object> paths = map(document.get("paths"));

        assertThat(schemas.keySet()).contains(
                "ProblemDetail", "PageMetadata", "DecimalQuantity", "DecimalPrice", "DecimalRate");
        assertThat(map(schemas.get("ProblemDetail")).get("required").toString())
                .contains("code", "traceId");

        assertParameters(paths, "/api/v1/master-data/uoms", "get", "page", "size", "sort", "status", "code");
        assertParameters(paths, "/api/v1/master-data/exchange-rates", "get", "page", "size", "sort", "month", "status");
        assertParameters(paths, "/api/v1/master-data/customers", "get", "page", "size", "sort", "status", "name", "shortName");
        assertParameters(paths, "/api/v1/admin/login-events", "get", "page", "size", "sort", "loginId", "outcome", "from", "to");

        Set<String> protectedPaths = Set.of(
                "/api/v1/admin/users", "/api/v1/admin/roles", "/api/v1/admin/ip-allowlist",
                "/api/v1/master-data/uoms", "/api/v1/master-data/customers", "/api/v1/master-data/raw-materials");
        protectedPaths.forEach(path -> map(paths.get(path)).values().forEach(rawOperation -> {
            Map<String, Object> operation = map(rawOperation);
            assertThat(map(operation.get("responses")).keySet())
                    .as("Problem responses for %s %s", path, operation.get("operationId"))
                    .contains("401", "403");
        }));
    }

    @Test
    void pagesEveryScopedListAndProvidesCutoverViews() throws IOException {
        Map<String, Object> schemas = schemas(loadOpenApi());

        assertThat(Set.of(
                        "SessionPageResponse", "UserPageResponse", "RolePageResponse", "PermissionPageResponse",
                        "LoginEventPageResponse", "IpAllowlistPageResponse", "CurrencyPageResponse", "UomPageResponse",
                        "ExchangeRatePageResponse", "CustomerPageResponse", "SupplierPageResponse", "ProcessPageResponse",
                        "RawMaterialPageResponse", "FinishedGoodPageResponse"))
                .allSatisfy(name -> assertThat(properties(schemas, name)).containsKeys("items", "page", "filters", "sort"));

        assertThat(properties(schemas, "LoginEventResponse"))
                .containsKeys("id", "userId", "loginId", "userName", "outcome", "clientIp", "ipName", "occurredAt");
        assertThat(properties(schemas, "SessionResponse"))
                .containsKeys("id", "clientIp", "userAgent", "createdAt", "lastRefreshedAt", "idleExpiresAt", "absoluteExpiresAt", "current");
        assertThat(properties(schemas, "IpAllowlistEntryResponse"))
                .containsKeys("id", "version", "active", "network", "name", "createdAt", "updatedAt");
    }

    @Test
    void closesEveryRequestAndKeepsSensitiveOrMediaMaterialOutOfResponses() throws IOException {
        Map<String, Object> schemas = schemas(loadOpenApi());
        Set<String> forbiddenResponseProperties = Set.of(
                "password", "passwordHash", "refreshToken", "temporaryPassword", "pictureDataUrl", "imageDataUrl", "imageName");

        schemas.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("Request"))
                .forEach(entry -> assertThat(map(entry.getValue()).get("additionalProperties"))
                        .as("closed request schema %s", entry.getKey())
                        .isEqualTo(false));
        schemas.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("Response") || entry.getKey().endsWith("View"))
                .forEach(entry -> assertThat(propertyNames(map(entry.getValue())))
                        .as("response schema %s", entry.getKey())
                        .doesNotContainAnyElementsOf(forbiddenResponseProperties));
    }

    private static void assertMatches(Map<String, Object> schemas, String schemaName, String... validValues) {
        String pattern = (String) map(schemas.get(schemaName)).get("pattern");
        assertThat(validValues).allSatisfy(value -> assertThat(Pattern.matches(pattern, value)).isTrue());
    }

    private static void assertParameters(
            Map<String, Object> paths, String path, String method, String... expectedNames) {
        Set<String> names = list(operation(paths, path, method).get("parameters")).stream()
                .map(PhaseOneOpenApiContractTest::map)
                .map(parameter -> parameter.containsKey("name") ? parameter.get("name").toString() : parameter.get("$ref").toString())
                .map(value -> value.startsWith("#/components/parameters/")
                        ? value.substring("#/components/parameters/".length()).toLowerCase()
                        : value)
                .collect(Collectors.toSet());
        assertThat(names).contains(expectedNames);
    }

    private static void assertCombinedSecurity(
            Map<String, Object> paths, String path, String method, String... schemeNames) {
        assertThat(security(paths, path, method))
                .anySatisfy(requirement -> assertThat(requirement.keySet()).contains(schemeNames));
    }

    private static List<Map<String, Object>> security(Map<String, Object> paths, String path, String method) {
        return list(operation(paths, path, method).get("security")).stream()
                .map(PhaseOneOpenApiContractTest::map)
                .toList();
    }

    private static Map<String, Object> operation(
            Map<String, Object> paths, String path, String method) {
        return map(map(paths.get(path)).get(method));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadOpenApi() throws IOException {
        Object value = new Yaml().load(Files.readString(OPEN_API));
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> schemas(Map<String, Object> document) {
        return map(map(document.get("components")).get("schemas"));
    }

    private static Map<String, Object> properties(Map<String, Object> schemas, String schemaName) {
        return map(map(schemas.get(schemaName)).get("properties"));
    }

    private static Set<String> required(Map<String, Object> schemas, String schemaName) {
        return list(map(schemas.get(schemaName)).get("required")).stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Set<String> propertyNames(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        return properties instanceof Map<?, ?> propertyMap
                ? propertyMap.keySet().stream().map(Object::toString).collect(Collectors.toSet())
                : Set.of();
    }
}
