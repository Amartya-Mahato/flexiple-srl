package com.flexiple.sourcing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM configuration. The API key comes from the {@code LLM_API_KEY} environment variable and never
 * leaves the server: it is not logged, not returned by any endpoint, and not embedded in the page.
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(String apiKey, String model, String baseUrl, int timeoutSeconds) {

    public boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
