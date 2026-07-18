package io.github.johnwhitton.digitalbanking;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.johnwhitton.digitalbanking.controlplane.api.LocalMockBankController;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMockBankOpenApiContractTest {

    @Test
    void localContractMatchesEndpointsAuthoritiesAndMinimizedShapes() throws Exception {
        Map<String, Object> document;
        String text;
        try (InputStream input = new ClassPathResource(
                "openapi/local-mock-banks-v1.yaml").getInputStream()) {
            text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            document = map(new Yaml().load(text));
        }

        assertEquals("3.1.0", document.get("openapi"));
        Map<String, Object> paths = map(document.get("paths"));
        assertAuthority(paths,
                "/local/v1/mock-banks/{bankId}/accounts/{accountId}/withdrawals",
                "post", "local-bank:debit");
        assertAuthority(paths,
                "/local/v1/mock-banks/{bankId}/accounts/{accountId}/deposits",
                "post", "local-bank:credit");
        assertTrue(responses(paths,
                "/local/v1/mock-banks/{bankId}/accounts/{accountId}/withdrawals",
                "post").containsKey("202"));
        assertTrue(responses(paths,
                "/local/v1/mock-banks/{bankId}/accounts/{accountId}/deposits",
                "post").containsKey("202"));
        assertAuthority(paths,
                "/local/v1/mock-banks/{bankId}/accounts/{accountId}",
                "get", "local-bank:read");
        assertAuthority(paths,
                "/local/v1/mock-banks/operations/{operationId}",
                "get", "local-bank:read");
        assertAuthority(paths, "/local/v1/mock-banks/openapi.yaml",
                "get", "local-bank:read");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        assertEquals(recordFields(LocalMockBankController.MoneyRequest.class),
                map(map(schemas.get("MoneyRequest")).get("properties")).keySet());
        String amountPattern = map(map(map(schemas.get("MoneyRequest"))
                .get("properties")).get("amount")).get("pattern").toString();
        RecordComponent amountComponent = Arrays.stream(
                        LocalMockBankController.MoneyRequest.class.getRecordComponents())
                .filter(component -> component.getName().equals("amount"))
                .findFirst().orElseThrow();
        assertEquals(amountComponent.getAccessor().getAnnotation(
                jakarta.validation.constraints.Pattern.class).regexp(), amountPattern);
        assertFalse(Pattern.compile(amountPattern).matcher("1.00").matches());
        assertFalse(Pattern.compile(amountPattern).matcher("0.10").matches());
        assertEquals(recordFields(LocalMockBankController.AccountResponse.class),
                map(map(schemas.get("Account")).get("properties")).keySet());
        assertEquals(recordFields(LocalMockBankController.OperationResponse.class),
                map(map(schemas.get("Operation")).get("properties")).keySet());
        assertFalse(text.contains("ledger"));
        assertFalse(text.contains("policyVersion"));
        assertFalse(text.contains("participantId"));
        assertFalse(text.contains("resultingBalance"));
        assertTrue(text.contains("synthetic local fixtures only"));
        assertTrue(text.contains("127.0.0.1"));
    }

    private static void assertAuthority(
            Map<String, Object> paths, String path, String method, String authority) {
        Map<String, Object> operation = map(map(paths.get(path)).get(method));
        assertEquals(authority, operation.get("x-required-authority"));
        assertTrue(operation.containsKey("security"));
    }

    private static Map<String, Object> responses(
            Map<String, Object> paths, String path, String method) {
        return map(map(map(paths.get(path)).get(method)).get("responses"));
    }

    private static Set<String> recordFields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toUnmodifiableSet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
