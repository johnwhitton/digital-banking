package io.github.johnwhitton.digitalbanking.controlplane.api;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local-only, operator-authorized, read-only evidence for deterministic demo assertions. */
@RestController
@Profile("local-demo-environment & local-demo & (local-ethereum | local-solana) & !local-signer")
@RequestMapping("/local/v1/demo")
public final class LocalDemoStatusController {

    private final LocalDemoStatusService status;

    public LocalDemoStatusController(LocalDemoStatusService status) {
        this.status = java.util.Objects.requireNonNull(status, "status");
    }

    @GetMapping("/status")
    public LocalDemoStatusService.Status status() {
        return status.snapshot();
    }

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public Resource openApi() {
        return new ClassPathResource("openapi/local-demo-status-v1.yaml");
    }
}
