package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Instant;

@FunctionalInterface
public interface ClockPort {
    Instant now();
}
