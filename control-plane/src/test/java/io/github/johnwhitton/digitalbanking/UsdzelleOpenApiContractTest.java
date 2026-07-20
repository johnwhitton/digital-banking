package io.github.johnwhitton.digitalbanking;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.johnwhitton.digitalbanking.controlplane.api.UsdzelleWorkflowController;
import io.github.johnwhitton.digitalbanking.controlplane.api.UsdzelleWorkflowResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsdzelleOpenApiContractTest {

    @Test
    void contractParsesMatchesRecordsAndExposesNoCallerControlledAuthority() throws Exception {
        String text;
        Map<String, Object> document;
        try (InputStream input = new ClassPathResource(
                "static/openapi/usdzelle-workflows-v1.yaml").getInputStream()) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            document = map(new Yaml().load(text));
        }
        assertEquals("3.1.0", document.get("openapi"));
        Map<String, Object> paths = map(document.get("paths"));
        assertTrue(map(paths.get("/v1/usdzelle/acquisitions")).containsKey("post"));
        assertTrue(map(paths.get("/v1/usdzelle/acquisitions/{workflowId}"))
                .containsKey("get"));
        assertTrue(map(paths.get("/v1/usdzelle/redemptions")).containsKey("post"));
        assertTrue(map(paths.get("/v1/usdzelle/redemptions/{workflowId}"))
                .containsKey("get"));
        assertOperationResponses(paths, "/v1/usdzelle/acquisitions", "post",
                Set.of("202", "400", "401", "403", "409", "415", "422", "500", "503"));
        assertOperationResponses(paths, "/v1/usdzelle/redemptions", "post",
                Set.of("202", "400", "401", "403", "409", "415", "422", "500", "503"));
        assertOperationResponses(paths, "/v1/usdzelle/acquisitions/{workflowId}", "get",
                Set.of("200", "400", "401", "403", "404", "500", "503"));
        assertOperationResponses(paths, "/v1/usdzelle/redemptions/{workflowId}", "get",
                Set.of("200", "400", "401", "403", "404", "500", "503"));

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        assertEquals(components(UsdzelleWorkflowController.AcceptanceRequest.class),
                map(map(schemas.get("WorkflowRequest")).get("properties")).keySet());
        assertEquals(components(UsdzelleWorkflowResponse.class),
                map(map(schemas.get("Workflow")).get("properties")).keySet());
        assertEquals(components(UsdzelleWorkflowResponse.Progress.class),
                map(map(schemas.get("Progress")).get("properties")).keySet());
        assertEquals(components(UsdzelleWorkflowResponse.Step.class),
                map(map(schemas.get("Step")).get("properties")).keySet());

        for (String forbidden : Set.of(
                "privateKey", "walletAddress", "adminWallet", "contractAddress",
                "transferAuthorityKeyAlias",
                "chainId", "rpcUrl", "nonce", "gas", "calldata", "journalLines",
                "evidenceReference", "finalityConclusion", "retry")) {
            assertFalse(text.contains(forbidden), forbidden);
        }
        assertFalse(text.contains("mainnet"));
        assertFalse(text.contains("https://"));
    }

    private static void assertOperationResponses(
            Map<String, Object> paths,
            String path,
            String method,
            Set<String> expected) {
        Map<String, Object> operation = map(map(paths.get(path)).get(method));
        assertEquals(expected, map(operation.get("responses")).keySet());
    }

    private static Set<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
