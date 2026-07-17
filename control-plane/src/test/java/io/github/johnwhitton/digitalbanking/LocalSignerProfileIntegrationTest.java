package io.github.johnwhitton.digitalbanking;

import java.util.Set;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningKeyRegistry;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.signer.local.LocalEphemeralSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local-signer")
class LocalSignerProfileIntegrationTest extends PostgresApiIntegrationSupport {

    @Autowired
    private LocalEphemeralSigner localSigner;

    @Autowired
    private SignerPort signerPort;

    @Autowired
    private SigningKeyRegistry keyRegistry;

    @Autowired
    private SigningAuthorityService signingAuthorityService;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void explicitProfileComposesLocalAuthorityWithoutPublicSigningEndpoint() {
        assertSame(localSigner, signerPort);
        assertSame(localSigner, keyRegistry);
        assertTrue(signingAuthorityService != null);
        assertEquals(2, localSigner.keys().size());
        assertTrue(localSigner.keys().stream().allMatch(key ->
                "LOCAL_EPHEMERAL".equals(key.classification())));
        assertTrue(localSigner.keys().stream().allMatch(key ->
                key.roles().equals(Set.of(SigningRequest.KeyRole.MINT_AUTHORITY))));
        assertTrue(mappings.getHandlerMethods().keySet().stream()
                .noneMatch(mapping -> mapping.toString().toLowerCase().contains("sign")));
    }
}
