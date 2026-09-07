package com.flexiple.sourcing.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * How the matching profiles were ranked.
 *
 * <p>{@code AI} judges them against the fit rubric. {@code LOCAL} ranks them in Java from the
 * objective filters alone - instant, free, and available with no API key at all, which is what makes
 * the manual search path usable on its own.
 */
public enum ScoringMode {
    AI, LOCAL;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ScoringMode> parseLenient(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        String cleaned = rawValue.trim().toUpperCase(Locale.ROOT);
        return java.util.Arrays.stream(values()).filter(mode -> mode.name().equals(cleaned)).findFirst();
    }
}
