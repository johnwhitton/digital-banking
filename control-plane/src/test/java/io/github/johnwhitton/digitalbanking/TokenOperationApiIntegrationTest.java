package io.github.johnwhitton.digitalbanking;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.controlplane.api.ParticipantPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TokenOperationApiIntegrationTest.ReferenceAssetConfiguration.class)
class TokenOperationApiIntegrationTest extends PostgresApiIntegrationSupport {

    private static final Pattern OPERATION_ID = Pattern.compile(
            "\\\"operationId\\\":\\\"([0-9a-f-]{36})\\\"");
    private static final String VALID_REQUEST = """
            {
              "contractVersion": 1,
              "assetId": "REFERENCE_ASSET",
              "unitId": "REFERENCE_UNIT",
              "unitVersion": 3,
              "quantity": "12.34",
              "businessCorrelation": "corr-001"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clearBusinessData() {
        jdbc.sql("DELETE FROM operation_outbox").update();
        jdbc.sql("DELETE FROM operation_finality_evidence").update();
        jdbc.sql("DELETE FROM operation_finality").update();
        jdbc.sql("DELETE FROM operation_attempt").update();
        jdbc.sql("DELETE FROM operation_transition_evidence").update();
        jdbc.sql("DELETE FROM operation_transition").update();
        jdbc.sql("DELETE FROM operation_idempotency").update();
        jdbc.sql("DELETE FROM token_operation").update();
    }

    @Test
    void unauthenticatedBusinessRequestReturnsSafeProblem() throws Exception {
        mvc.perform(post("/v1/token-operations/mints")
                        .header("Idempotency-Key", "request-001")
                        .contentType("application/json")
                        .content(VALID_REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("urn:digital-banking:problem:unauthenticated"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void authenticatedButUnauthorizedBusinessRequestReturnsSafeProblem() throws Exception {
        mvc.perform(post("/v1/token-operations/mints")
                        .with(participant("tenant-a", "participant-a", "token:read"))
                        .header("Idempotency-Key", "request-002")
                        .contentType("application/json")
                        .content(VALID_REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("urn:digital-banking:problem:unauthorized"));
    }

    @Test
    void mintBurnAndReadAuthoritiesRemainSeparate() throws Exception {
        accept("burns", "token:mint", "wrong-burn-authority", VALID_REQUEST)
                .andExpect(status().isForbidden());
        accept("mints", "token:burn", "wrong-mint-authority", VALID_REQUEST)
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/token-operations/{operationId}",
                        "00000000-0000-0000-0000-000000000001")
                        .with(participant(
                                "tenant-a", "participant-a", "token:mint")))
                .andExpect(status().isForbidden());
    }

    @Test
    void authorizedMintAndBurnReturnCommittedAcceptanceAndLocation() throws Exception {
        MvcResult mint = accept("mints", "token:mint", "request-mint", VALID_REQUEST)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", matchesPattern(
                        "/v1/token-operations/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.kind").value("MINT"))
                .andExpect(jsonPath("$.state").value("REQUESTED"))
                .andExpect(jsonPath("$.quantity").value("12.34"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        MvcResult burn = accept("burns", "token:burn", "request-burn", VALID_REQUEST)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("BURN"))
                .andReturn();

        assertFalse(operationId(mint).equals(operationId(burn)));
        assertEquals(2L, count("token_operation"));
        assertEquals(2L, count("operation_outbox"));
    }

    @Test
    void validatesIdempotencyQuantityUnitVersionAndCorrelation() throws Exception {
        mvc.perform(post("/v1/token-operations/mints")
                        .with(participant("tenant-a", "participant-a", "token:mint"))
                        .contentType("application/json")
                        .content(VALID_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"));
        mvc.perform(post("/v1/token-operations/mints")
                        .with(participant("tenant-a", "participant-a", "token:mint"))
                        .header("Idempotency-Key", "x".repeat(129))
                        .contentType("application/json")
                        .content(VALID_REQUEST))
                .andExpect(status().isBadRequest());
        accept("mints", "token:mint", "", VALID_REQUEST)
                .andExpect(status().isBadRequest());
        accept("mints", "token:mint", "contains space", VALID_REQUEST)
                .andExpect(status().isBadRequest());
        accept("mints", "token:mint", "invalid-quantity",
                VALID_REQUEST.replace("12.34", "12.340"))
                .andExpect(status().isBadRequest());
        accept("mints", "token:mint", "invalid-unit",
                VALID_REQUEST.replace("REFERENCE_UNIT", "UNKNOWN_UNIT"))
                .andExpect(status().isUnprocessableEntity());
        accept("mints", "token:mint", "invalid-unit-version",
                VALID_REQUEST.replace("\"unitVersion\": 3", "\"unitVersion\": 4"))
                .andExpect(status().isUnprocessableEntity());
        accept("mints", "token:mint", "invalid-contract",
                VALID_REQUEST.replace("\"contractVersion\": 1", "\"contractVersion\": 2"))
                .andExpect(status().isUnprocessableEntity());
        accept("mints", "token:mint", "invalid-correlation",
                VALID_REQUEST.replace("corr-001", "x".repeat(129)))
                .andExpect(status().isBadRequest());
        accept("mints", "token:mint", "chain-field",
                VALID_REQUEST.replace(
                        "\"businessCorrelation\": \"corr-001\"",
                        "\"businessCorrelation\": \"corr-001\", \"chainId\": 1"))
                .andExpect(status().isBadRequest());
        accept("mints", "token:mint", "participant-field",
                VALID_REQUEST.replace(
                        "\"businessCorrelation\": \"corr-001\"",
                        "\"businessCorrelation\": \"corr-001\", \"participantId\": \"participant-b\""))
                .andExpect(status().isBadRequest());
        assertEquals(0L, count("token_operation"));
    }

    @Test
    void malformedJsonAndUnsupportedMediaReturnStableProblems() throws Exception {
        mvc.perform(post("/v1/token-operations/mints")
                        .with(participant("tenant-a", "participant-a", "token:mint"))
                        .header("Idempotency-Key", "malformed-json")
                        .contentType("application/json")
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("urn:digital-banking:problem:invalid-request"));
        mvc.perform(post("/v1/token-operations/mints")
                        .with(participant("tenant-a", "participant-a", "token:mint"))
                        .header("Idempotency-Key", "unsupported-media")
                        .contentType("text/plain")
                        .content(VALID_REQUEST))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("urn:digital-banking:problem:unsupported-media-type"));
    }

    @Test
    void openApiResourceIsAnonymousAndMatchesTheAuthoritativeClasspathDocument()
            throws Exception {
        String expected = new ClassPathResource(
                "static/openapi/token-operations-v1.yaml")
                .getContentAsString(StandardCharsets.UTF_8);

        mvc.perform(get("/openapi/token-operations-v1.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(expected));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void rawIdempotencyKeyIsAbsentFromResponsesAndLogs(CapturedOutput output)
            throws Exception {
        String sensitiveKey = "sensitive-log-key-should-never-escape";
        MvcResult accepted = accept("mints", "token:mint", sensitiveKey, VALID_REQUEST)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.evidenceReferences[0]")
                        .value(matchesPattern("participant:acceptance:[0-9a-f-]{36}")))
                .andReturn();
        MvcResult conflict = accept(
                "mints", "token:mint", sensitiveKey,
                VALID_REQUEST.replace("12.34", "12.35"))
                .andExpect(status().isConflict())
                .andReturn();

        assertFalse(conflict.getResponse().getContentAsString().contains(sensitiveKey));
        assertFalse(output.getAll().contains(sensitiveKey));
        String commandDigest = jdbc.sql("SELECT command_digest FROM token_operation")
                .query(String.class).single();
        assertFalse(accepted.getResponse().getContentAsString().contains(commandDigest));
    }

    @Test
    void samePayloadReplaysSameOperationAndDifferentPayloadConflicts() throws Exception {
        String sensitiveKey = "sensitive-replay-key";
        MvcResult first = accept("mints", "token:mint", sensitiveKey, VALID_REQUEST)
                .andExpect(status().isAccepted()).andReturn();
        MvcResult replay = accept("mints", "token:mint", sensitiveKey, VALID_REQUEST)
                .andExpect(status().isAccepted()).andReturn();
        MvcResult conflict = accept(
                "mints", "token:mint", sensitiveKey,
                VALID_REQUEST.replace("12.34", "12.35"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("urn:digital-banking:problem:idempotency-conflict"))
                .andReturn();

        assertEquals(operationId(first), operationId(replay));
        assertEquals(first.getResponse().getHeader("Location"),
                replay.getResponse().getHeader("Location"));
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1L, count("token_operation"));
        assertEquals(1L, count("operation_outbox"));
        assertFalse(conflict.getResponse().getContentAsString().contains(sensitiveKey));
    }

    @Test
    void getReturnsDurableRepresentationWithFourSeparateFinalities() throws Exception {
        String operationId = operationId(accept(
                "burns", "token:burn", "durable-get", VALID_REQUEST)
                .andExpect(status().isAccepted()).andReturn());

        mvc.perform(get("/v1/token-operations/{operationId}", operationId)
                        .with(participant("tenant-a", "participant-a", "token:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId))
                .andExpect(jsonPath("$.asset.assetId").value("REFERENCE_ASSET"))
                .andExpect(jsonPath("$.finalities.blockchain[0].status")
                        .value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.finalities.legal[0].status")
                        .value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.finalities.customerVisible[0].status")
                        .value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.finalities.accounting[0].status")
                        .value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.finalities.blockchain[0].authority").doesNotExist())
                .andExpect(jsonPath("$.finalities.blockchain[0].policyVersion").doesNotExist())
                .andExpect(jsonPath("$.attempts").isArray())
                .andExpect(jsonPath("$.evidenceReferences[0]").isString());
    }

    @Test
    void unknownAndOtherParticipantOperationsAreIndistinguishable() throws Exception {
        String operationId = operationId(accept(
                "mints", "token:mint", "participant-private", VALID_REQUEST)
                .andExpect(status().isAccepted()).andReturn());
        MvcResult otherParticipant = mvc.perform(
                        get("/v1/token-operations/{operationId}", operationId)
                                .queryParam("participantId", "participant-a")
                                .header("X-Participant-Id", "participant-a")
                                .with(participant(
                                        "tenant-a", "participant-b", "token:read")))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult unknown = mvc.perform(
                        get("/v1/token-operations/{operationId}",
                                "00000000-0000-0000-0000-000000000099")
                                .with(participant(
                                        "tenant-a", "participant-a", "token:read")))
                .andExpect(status().isNotFound()).andReturn();

        assertEquals(otherParticipant.getResponse().getContentAsString(),
                unknown.getResponse().getContentAsString());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void unavailableDatabaseReturnsSafe503WithoutFalseAcceptance() throws Exception {
        ((HikariDataSource) dataSource).close();

        MvcResult result = accept(
                "mints", "token:mint", "database-down", VALID_REQUEST)
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("urn:digital-banking:problem:service-unavailable"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("Hikari"));
        assertFalse(body.contains("SQLException"));
        assertFalse(body.contains("database-down"));
    }

    private org.springframework.test.web.servlet.ResultActions accept(
            String resource, String authority, String key, String request) throws Exception {
        return mvc.perform(post("/v1/token-operations/" + resource)
                .with(participant("tenant-a", "participant-a", authority))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(request));
    }

    private static RequestPostProcessor participant(
            String tenantId, String participantId, String... authorities) {
        ParticipantPrincipal principal = new ParticipantPrincipal(tenantId, participantId);
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(authorities).stream()
                                .map(SimpleGrantedAuthority::new)
                                .toList());
        return authentication(token);
    }

    private static String operationId(MvcResult result) throws Exception {
        Matcher matcher = OPERATION_ID.matcher(result.getResponse().getContentAsString());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private long count(String table) {
        if (!List.of("token_operation", "operation_outbox").contains(table)) {
            throw new IllegalArgumentException("unexpected table");
        }
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReferenceAssetConfiguration {

        @Bean
        @Primary
        AssetUnitCatalog referenceAssetCatalog() {
            AssetUnit unit = new AssetUnit(
                    "REFERENCE_ASSET", "REFERENCE_UNIT", 3, 2,
                    new BigInteger("100000000"));
            return (assetId, unitId, version) ->
                    unit.assetId().equals(assetId)
                            && unit.unitId().equals(unitId)
                            && unit.version() == version
                            ? java.util.Optional.of(unit) : java.util.Optional.empty();
        }

        @Bean
        @Primary
        ClockPort nanosecondClock() {
            return () -> java.time.Instant.parse("2026-07-16T23:00:00.123456789Z");
        }
    }
}
