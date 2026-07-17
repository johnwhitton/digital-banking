package io.github.johnwhitton.digitalbanking;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.johnwhitton.digitalbanking.controlplane.api.TokenOperationController;
import io.github.johnwhitton.digitalbanking.controlplane.api.TokenOperationResponse;
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
        assertTrue(map(paths.get("/openapi/token-operations-v1.yaml")).containsKey("get"));
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
                Set.of("202", "400", "401", "403", "409", "415", "422", "503"));
        assertOperation(paths, "/v1/token-operations/burns", "post", "token:burn",
                Set.of("202", "400", "401", "403", "409", "415", "422", "503"));
        assertOperation(paths, "/v1/token-operations/{operationId}", "get", "token:read",
                Set.of("200", "400", "401", "403", "404", "503"));

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
        assertEquals(Set.of(
                        "urn:digital-banking:problem:invalid-request",
                        "urn:digital-banking:problem:unauthenticated",
                        "urn:digital-banking:problem:unauthorized",
                        "urn:digital-banking:problem:operation-not-found",
                        "urn:digital-banking:problem:idempotency-conflict",
                        "urn:digital-banking:problem:unsupported-media-type",
                        "urn:digital-banking:problem:unprocessable-request",
                        "urn:digital-banking:problem:service-unavailable"),
                Set.copyOf(list(map(map(map(schemas.get("Problem")).get("properties"))
                        .get("type")), "enum")));
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
        assertEquals("^[1-9][0-9]*(\\.[0-9]*[1-9])?$", quantity.get("pattern"));
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
