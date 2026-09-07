package com.flexiple.sourcing.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The only company backgrounds that exist in the dataset. Anything else from the LLM is dropped. */
public enum CompanyType {
    STARTUP, SCALEUP, ENTERPRISE, AGENCY;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<CompanyType> parseLenient(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        String cleaned = rawValue.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "");
        return Arrays.stream(values()).filter(type -> type.wireValue().equals(cleaned)).findFirst();
    }
}
