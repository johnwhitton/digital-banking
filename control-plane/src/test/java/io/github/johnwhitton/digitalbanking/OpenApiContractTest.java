package io.github.johnwhitton.digitalbanking;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.johnwhitton.digitalbanking.controlplane.api.TokenOperationController;
import io.github.johnwhitton.digitalbanking.controlplane.api.TokenOperationResponse;
import io.github.johnwhitton.digitalbanking.controlplane.api.TransferController;
import io.github.johnwhitton.digitalbanking.controlplane.api.TransferResponse;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferParticipant;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiContractTest {

    private static final String CONTRACT =
            "static/openapi/token-operations-v1.yaml";

    @Test
    void authoritativeContractParsesAndDefinesTheRuntimeSurface() throws IOException {
        String text;
        Map<String, Object> document;
        try (InputStream input = new ClassPathResource(CONTRACT).getInputStream()) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            document = map(new Yaml().load(text));
        }

        assertEquals("3.1.0", document.get("openapi"));
        Map<String, Object> paths = map(document.get("paths"));
        assertTrue(map(paths.get("/v1/token-operations/mints")).containsKey("post"));
        assertTrue(map(paths.get("/v1/token-operations/burns")).containsKey("post"));
        assertTrue(map(paths.get("/v1/token-operations/{operationId}")).containsKey("get"));
        assertTrue(map(paths.get("/v1/transfers")).containsKey("post"));
        assertTrue(map(paths.get("/v1/transfers/{transferId}")).containsKey("get"));
        assertTrue(map(paths.get("/openapi/token-operations-v1.yaml")).containsKey("get"));
        assertFalse(paths.keySet().stream().anyMatch(path -> path.contains("sign")));
        assertFalse(document.containsKey("servers"));
        assertFalse(text.contains("http://"));
        assertFalse(text.contains("https://"));
    }

    @Test
    void contractDocumentsSecurityIdempotencyProblemsAndAcceptanceOnlySemantics()
            throws IOException {
        Map<String, Object> document;
        try (InputStream input = new ClassPathResource(CONTRACT).getInputStream()) {
            document = map(new Yaml().load(input));
        }
        Map<String, Object> paths = map(document.get("paths"));
        assertOperation(paths, "/v1/token-operations/mints", "post", "token:mint",
                Set.of("202", "400", "401", "403", "409", "415", "422", "500", "503"));
        assertOperation(paths, "/v1/token-operations/burns", "post", "token:burn",
                Set.of("202", "400", "401", "403", "409", "415", "422", "500", "503"));
        assertOperation(paths, "/v1/token-operations/{operationId}", "get", "token:read",
                Set.of("200", "400", "401", "403", "404", "500", "503"));
        assertOperation(paths, "/v1/transfers", "post", "transfer:create",
                Set.of("202", "400", "401", "403", "409", "415", "422", "500", "503"));
        assertOperation(paths, "/v1/transfers/{transferId}", "get", "transfer:read",
                Set.of("200", "400", "401", "403", "404", "500", "503"));

        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> parameters = map(components.get("parameters"));
        Map<String, Object> key = map(parameters.get("IdempotencyKey"));
        assertEquals("Idempotency-Key", key.get("name"));
        assertEquals("^[!-~]{1,128}$", map(key.get("schema")).get("pattern"));

        Map<String, Object> responses = map(components.get("responses"));
        Map<String, Object> acceptedMediaType = map(map(
                map(responses.get("Accepted")).get("content")).get("application/json"));
        assertNotNull(acceptedMediaType.get("example"));
        Map<String, String> problemTypes = Map.of(
                "BadRequest", "urn:digital-banking:problem:invalid-request",
                "Unauthenticated", "urn:digital-banking:problem:unauthenticated",
                "Unauthorized", "urn:digital-banking:problem:unauthorized",
                "NotFound", "urn:digital-banking:problem:operation-not-found",
                "Conflict", "urn:digital-banking:problem:idempotency-conflict",
                "UnsupportedMediaType", "urn:digital-banking:problem:unsupported-media-type",
                "Unprocessable", "urn:digital-banking:problem:unprocessable-request",
                "InternalError", "urn:digital-banking:problem:internal-error",
                "ServiceUnavailable", "urn:digital-banking:problem:service-unavailable");
        for (Map.Entry<String, String> problem : problemTypes.entrySet()) {
            Map<String, Object> mediaType = map(map(
                    map(responses.get(problem.getKey())).get("content"))
                    .get("application/problem+json"));
            assertEquals(problem.getValue(), map(mediaType.get("example")).get("type"));
        }

        Map<String, Object> schemas = map(components.get("schemas"));
        assertEquals(recordComponents(TokenOperationController.AcceptanceRequest.class),
                map(map(schemas.get("AcceptanceRequest")).get("properties")).keySet());
        assertEquals(recordComponents(TokenOperationResponse.class),
                map(map(schemas.get("TokenOperation")).get("properties")).keySet());
        assertSchemaMatchesRecord(schemas, "AssetUnit", TokenOperationResponse.AssetView.class);
        assertSchemaMatchesRecord(schemas, "Attempt", TokenOperationResponse.AttemptView.class);
        assertSchemaMatchesRecord(schemas, "Transition",
                TokenOperationResponse.TransitionView.class);
        assertSchemaMatchesRecord(schemas, "FinalityHistories",
                TokenOperationResponse.FinalityHistories.class);
        assertSchemaMatchesRecord(schemas, "FinalityRecord",
                TokenOperationResponse.FinalityView.class);
        assertEquals(recordComponents(TransferController.AcceptanceRequest.class),
                map(map(schemas.get("TransferAcceptanceRequest")).get("properties")).keySet());
        assertFalse(map(map(schemas.get("TransferAcceptanceRequest"))
                .get("properties")).containsKey("senderWallet"));
        assertFalse(map(map(schemas.get("TransferAcceptanceRequest"))
                .get("properties")).containsKey("recipientWalletReference"));
        assertEquals(recordComponents(TransferResponse.class),
                map(map(schemas.get("Transfer")).get("properties")).keySet());
        assertSchemaMatchesRecord(schemas, "TransferEffect", TransferResponse.EffectView.class);
        assertSchemaMatchesRecord(schemas, "TransferFinalities",
                TransferResponse.FinalityHistories.class);
        assertSchemaMatchesRecord(schemas, "TransferFinality",
                TransferResponse.FinalityView.class);
        assertEquals(Set.of(
                        "urn:digital-banking:problem:invalid-request",
                        "urn:digital-banking:problem:unauthenticated",
                        "urn:digital-banking:problem:unauthorized",
                        "urn:digital-banking:problem:operation-not-found",
                        "urn:digital-banking:problem:transfer-not-found",
                        "urn:digital-banking:problem:idempotency-conflict",
                        "urn:digital-banking:problem:unsupported-media-type",
                        "urn:digital-banking:problem:unprocessable-request",
                        "urn:digital-banking:problem:internal-error",
                        "urn:digital-banking:problem:service-unavailable"),
                Set.copyOf(list(map(map(map(schemas.get("Problem")).get("properties"))
                        .get("type")), "enum")));
    }

    @Test
    void acceptedExampleMatchesTheExecutableParticipantResponse() throws Exception {
        Map<String, Object> document;
        try (InputStream input = new ClassPathResource(CONTRACT).getInputStream()) {
            document = map(new Yaml().load(input));
        }
        Map<String, Object> responses = map(map(document.get("components")).get("responses"));
        Object example = map(map(map(responses.get("Accepted")).get("content"))
                .get("application/json")).get("example");

        assertEquals(normalize(executableAcceptedResponse()), normalize(example));
    }

    @Test
    void transferAcceptedExampleMatchesTheExecutableSafeResponse() throws Exception {
        Map<String, Object> document;
        try (InputStream input = new ClassPathResource(CONTRACT).getInputStream()) {
            document = map(new Yaml().load(input));
        }
        Map<String, Object> responses = map(map(document.get("components")).get("responses"));
        Object example = map(map(map(responses.get("TransferAccepted")).get("content"))
                .get("application/json")).get("example");

        assertEquals(normalize(executableTransferResponse()), normalize(example));
    }

    @Test
    void quantityIsStringAndFourFinalitiesRemainDistinct() throws IOException {
        Map<String, Object> document;
        try (InputStream input = new ClassPathResource(CONTRACT).getInputStream()) {
            document = map(new Yaml().load(input));
        }
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> quantity = map(schemas.get("CanonicalQuantity"));
        Map<String, Object> finalities = map(map(schemas.get("FinalityHistories"))
                .get("properties"));

        assertEquals("string", quantity.get("type"));
        assertEquals("^(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?$", quantity.get("pattern"));
        assertNotNull(finalities.get("blockchain"));
        assertNotNull(finalities.get("legal"));
        assertNotNull(finalities.get("customerVisible"));
        assertNotNull(finalities.get("accounting"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> value, String key) {
        return (List<String>) value.get(key);
    }

    private static Set<String> recordComponents(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
    }

    private static void assertSchemaMatchesRecord(
            Map<String, Object> schemas, String schemaName, Class<?> recordType) {
        Map<String, Object> schema = map(schemas.get(schemaName));
        Set<String> components = recordComponents(recordType);
        assertEquals(components, map(schema.get("properties")).keySet());
        assertEquals(components, Set.copyOf(list(schema, "required")));
    }

    private static TokenOperationResponse executableAcceptedResponse() {
        Instant acceptedAt = Instant.parse("2026-07-16T23:00:00.123456Z");
        AssetUnit unit = new AssetUnit(
                "REFERENCE_ASSET", "REFERENCE_UNIT", 3, 2,
                new BigInteger("100000000"));
        TokenOperation operation = TokenOperation.requested(
                OperationId.from("8c5f0a89-9317-4f30-a7ca-f18a395293ce"),
                new OperationAcceptanceContext(
                        "tenant-a", "participant-a", "TOKEN_OPERATION", "a".repeat(64),
                        1, 1, "b".repeat(64), "corr-001"),
                OperationKind.MINT,
                TokenQuantity.parse("12.34", unit),
                acceptedAt,
                new EvidenceRef(
                        "participant:acceptance:48ff09c2-0a2e-40c4-a122-190a66fc7444"));
        return TokenOperationResponse.from(operation);
    }

    private static TransferResponse executableTransferResponse() {
        Instant acceptedAt = Instant.parse("2026-07-17T18:00:00.123456Z");
        AssetUnit unit = new AssetUnit(
                "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
        List<TransferEffect.Id> effects = java.util.stream.IntStream.rangeClosed(101, 105)
                .mapToObj(value -> new TransferEffect.Id(java.util.UUID.fromString(
                        "00000000-0000-0000-0000-000000000" + value)))
                .toList();
        Transfer transfer = Transfer.accepted(
                new TransferId(java.util.UUID.fromString(
                        "00000000-0000-0000-0000-000000000100")),
                new TransferParticipant("tenant-a", "participant-a"),
                new TransferAcceptanceContext(
                        new BankAccountReference("synthetic-bank:source-001"),
                        new BankAccountReference("synthetic-bank:destination-001"),
                        new WalletReference("synthetic-wallet:server-sender"),
                        new WalletReference("synthetic-wallet:server-recipient"),
                        SettlementNetwork.ETHEREUM, "USD", "route-v1", "wallet-v1",
                        1, "a".repeat(64), 1, "b".repeat(64), "c".repeat(64)),
                TokenQuantity.parse("12.34", unit), effects,
                new TransferTransition.Id(java.util.UUID.fromString(
                        "00000000-0000-0000-0000-000000000201")), acceptedAt,
                new EvidenceRef(
                        "participant:transfer:acceptance:00000000-0000-0000-0000-000000000100"));
        return TransferResponse.from(transfer);
    }

    private static Object normalize(Object value) throws ReflectiveOperationException {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> {
                try {
                    return normalize(item);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
        }
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                normalized.put(entry.getKey().toString(), normalize(entry.getValue()));
            }
            return normalized;
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                normalized.put(component.getName(), normalize(component.getAccessor().invoke(value)));
            }
            return normalized;
        }
        throw new IllegalArgumentException("unsupported example value type: " + value.getClass());
    }

    private static void assertOperation(
            Map<String, Object> paths,
            String path,
            String method,
            String authority,
            Set<String> statuses) {
        Map<String, Object> operation = map(map(paths.get(path)).get(method));
        assertEquals(authority, operation.get("x-required-authority"));
        assertEquals(statuses, map(operation.get("responses")).keySet());
        assertNotNull(operation.get("security"));
        if ("post".equals(method)) {
            assertTrue(((List<?>) operation.get("parameters")).stream()
                    .map(OpenApiContractTest::map)
                    .anyMatch(parameter -> "#/components/parameters/IdempotencyKey"
                            .equals(parameter.get("$ref"))));
        }
    }
}
