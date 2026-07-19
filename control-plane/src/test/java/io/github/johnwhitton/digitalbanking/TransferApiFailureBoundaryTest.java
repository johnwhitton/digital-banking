package io.github.johnwhitton.digitalbanking;

import io.github.johnwhitton.digitalbanking.application.TransferApplicationService;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.controlplane.api.ApiExceptionHandler;
import io.github.johnwhitton.digitalbanking.controlplane.api.ParticipantPrincipal;
import io.github.johnwhitton.digitalbanking.controlplane.api.TransferController;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

class TransferApiFailureBoundaryTest {

    private final TransferApplicationService transfers = mock(TransferApplicationService.class);
    private final MockMvc mvc = standaloneSetup(new TransferController(transfers))
            .setControllerAdvice(new ApiExceptionHandler())
            .setCustomArgumentResolvers(new ParticipantResolver())
            .build();

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void invariantFailureIsRedactedButRetainsServerDiagnostics(CapturedOutput output)
            throws Exception {
        String marker = "internal-transfer-sensitive-marker";
        given(transfers.findAcceptance(
                any(TransferId.class), any(ParticipantScope.class)))
                .willThrow(new IllegalStateException(marker));

        MvcResult result = mvc.perform(get("/v1/transfers/{transferId}",
                        "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("urn:digital-banking:problem:internal-error"))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains(marker));
        assertTrue(output.getAll().contains(marker));
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
