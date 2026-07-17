package io.github.johnwhitton.digitalbanking;

import java.util.stream.Stream;

import io.github.johnwhitton.digitalbanking.application.TokenOperationApplicationService;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.controlplane.api.ApiExceptionHandler;
import io.github.johnwhitton.digitalbanking.controlplane.api.ParticipantPrincipal;
import io.github.johnwhitton.digitalbanking.controlplane.api.TokenOperationController;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TokenOperationApiFailureBoundaryTest {

    private final TokenOperationApplicationService operations =
            mock(TokenOperationApplicationService.class);
    private final MockMvc mvc = standaloneSetup(new TokenOperationController(operations))
            .setControllerAdvice(new ApiExceptionHandler())
            .setCustomArgumentResolvers(new ParticipantResolver())
            .build();

    @Test
    void callerOwnedInvalidOperationIdRemainsAStableBadRequest() throws Exception {
        mvc.perform(get("/v1/token-operations/{operationId}", "not-a-canonical-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("urn:digital-banking:problem:invalid-request"));
    }

    @ParameterizedTest
    @MethodSource("internalInvariantFailures")
    @ExtendWith(OutputCaptureExtension.class)
    void internalInvariantFailureReturnsSafeServerErrorAndRetainsDiagnostics(
            RuntimeException failure,
            CapturedOutput output) throws Exception {
        given(operations.find(any(OperationId.class), any(ParticipantScope.class)))
                .willThrow(failure);

        MvcResult result = mvc.perform(get(
                        "/v1/token-operations/{operationId}",
                        "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("urn:digital-banking:problem:internal-error"))
                .andExpect(jsonPath("$.status").value(500))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(failure.getMessage()));
        assertFalse(body.contains(failure.getClass().getSimpleName()));
        assertTrue(output.getAll().contains(failure.getMessage()));
    }

    private static Stream<RuntimeException> internalInvariantFailures() {
        String diagnosticMarker = "internal-sensitive-diagnostic-marker";
        return Stream.of(
                new IllegalArgumentException(diagnosticMarker),
                new IllegalStateException(diagnosticMarker));
    }

    private static final class ParticipantResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer container,
                NativeWebRequest request,
                WebDataBinderFactory binderFactory) {
            return new ParticipantPrincipal("tenant-a", "participant-a");
        }
    }
}
