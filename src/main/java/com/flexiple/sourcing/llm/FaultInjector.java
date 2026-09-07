package com.flexiple.sourcing.llm;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A demo aid, off unless explicitly armed: it makes the <em>next</em> LLM call fail in a chosen way
 * so the recovery experience can be shown on demand. Real calls stay real - nothing here fabricates
 * a successful answer, it only breaks one response.
 */
@Component
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    private final AtomicReference<Fault> armedFault = new AtomicReference<>();

    public enum Fault {
        MALFORMED, TIMEOUT, RATE_LIMIT, EMPTY;

        static Optional<Fault> parseLenient(String rawMode) {
            if (rawMode == null) {
                return Optional.empty();
            }
            String cleaned = rawMode.trim().toUpperCase(Locale.ROOT).replace("-", "_");
            return java.util.Arrays.stream(values()).filter(fault -> fault.name().equals(cleaned)).findFirst();
        }
    }

    public Fault armNextCallFailure(String rawMode) {
        Fault fault = Fault.parseLenient(rawMode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown fault mode: " + rawMode));
        armedFault.set(fault);
        log.warn("Fault injection armed: the next LLM call will fail with {}", fault);
        return fault;
    }

    public void disarm() {
        armedFault.set(null);
    }

    /** Returns the armed fault exactly once, then clears it. */
    Optional<Fault> consumeArmedFault() {
        return Optional.ofNullable(armedFault.getAndSet(null));
    }

    public Optional<Fault> peekArmedFault() {
        return Optional.ofNullable(armedFault.get());
    }
}
