package io.github.johnwhitton.digitalbanking.domain.operation;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperationIdentifierTest {

    @Test
    void acceptsOnlyCanonicalUuidText() {
        String value = "9ecbbdb1-cf29-4f35-b762-1212a5727c38";

        assertEquals(value, OperationId.from(value).toString());
        assertEquals(value, AttemptId.from(value).toString());
        assertThrows(IllegalArgumentException.class, () -> OperationId.from(value.toUpperCase()));
        assertThrows(IllegalArgumentException.class, () -> AttemptId.from("not-a-uuid"));
    }

    @Test
    void preservesGeneratedIdentifierUniqueness() {
        Set<OperationId> operations = new HashSet<>();
        Set<AttemptId> attempts = new HashSet<>();

        for (int index = 0; index < 100; index++) {
            operations.add(new OperationId(UUID.randomUUID()));
            attempts.add(new AttemptId(UUID.randomUUID()));
        }

        assertEquals(100, operations.size());
        assertEquals(100, attempts.size());
    }
}
