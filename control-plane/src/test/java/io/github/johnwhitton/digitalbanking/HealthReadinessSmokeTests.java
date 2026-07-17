package io.github.johnwhitton.digitalbanking;

import java.util.Set;

import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
class HealthReadinessSmokeTests extends PostgresApiIntegrationSupport {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WebEndpointsSupplier webEndpoints;

    @Test
    void readinessEndpointReportsUpWithoutAuthentication() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/*+json")))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unexposedActuatorEndpointsAreUnavailable() throws Exception {
        mvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());

        assertEquals(Set.of("health"), webEndpoints.getEndpoints().stream()
                .map(endpoint -> endpoint.getEndpointId().toString())
                .collect(java.util.stream.Collectors.toSet()));
    }
}
