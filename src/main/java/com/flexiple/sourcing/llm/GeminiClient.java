package com.flexiple.sourcing.llm;

import com.flexiple.sourcing.config.LlmProperties;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The only place that talks to the LLM provider. Callers hand it a prompt and a JSON schema and get
 * back raw JSON text; everything above this class works with validated Java objects.
 *
 * <p>Reliability rules live here: an explicit read timeout, exactly one retry for transient failures
 * (never for a bad request), and a translation of every provider outcome into an {@link LlmException}
 * with a stable code. Nothing from the provider body is ever rethrown verbatim.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(700);
    private static final double DETERMINISTIC_TEMPERATURE = 0.2;

    private final RestClient restClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final FaultInjector faultInjector;

    GeminiClient(RestClient llmRestClient, LlmProperties llmProperties, ObjectMapper objectMapper,
            FaultInjector faultInjector) {
        this.restClient = llmRestClient;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.faultInjector = faultInjector;
    }

    public boolean isConfigured() {
        return llmProperties.isApiKeyConfigured();
    }

    public String modelName() {
        return llmProperties.model();
    }

    /**
     * Runs one structured-output generation and returns the model's JSON text.
     *
     * @param operationName short label used in logs only, e.g. "parse-search"
     */
    public String generateStructuredJson(String operationName, String systemInstruction, String userPrompt,
            Map<String, Object> responseSchema) {
        if (!llmProperties.isApiKeyConfigured()) {
            throw LlmException.notConfigured();
        }
        java.util.Optional<FaultInjector.Fault> armedFault = faultInjector.consumeArmedFault();
        if (armedFault.isPresent()) {
            return simulateArmedFault(armedFault.get());
        }

        LlmException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                long startedAt = System.currentTimeMillis();
                String json = callGenerateContentOnce(systemInstruction, userPrompt, responseSchema);
                log.info("LLM {} completed in {} ms ({} chars of JSON)", operationName,
                        System.currentTimeMillis() - startedAt, json.length());
                return json;
            } catch (LlmException failure) {
                lastFailure = failure;
                if (!failure.isRetryable() || attempt == MAX_ATTEMPTS) {
                    break;
                }
                log.warn("LLM {} attempt {} failed ({}), retrying once", operationName, attempt, failure.code());
                sleepBeforeRetry();
            }
        }
        throw lastFailure;
    }

    private String callGenerateContentOnce(String systemInstruction, String userPrompt,
            Map<String, Object> responseSchema) {
        String url = llmProperties.baseUrl() + "/models/" + llmProperties.model() + ":generateContent";
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(url)
                    .header("x-goog-api-key", llmProperties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(
                            buildGenerateContentRequest(systemInstruction, userPrompt, responseSchema)))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, errorResponse) -> {
                        // Deliberately empty: the status is translated below, and the provider body
                        // is never surfaced because it can carry quota and account details.
                    })
                    .toEntity(String.class);
        } catch (ResourceAccessException exception) {
            throw translateNetworkFailure(exception);
        }

        if (response.getStatusCode().isError()) {
            throw translateErrorStatus(response.getStatusCode());
        }
        return extractGeneratedTextFrom(response.getBody());
    }

    private Map<String, Object> buildGenerateContentRequest(String systemInstruction, String userPrompt,
            Map<String, Object> responseSchema) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", DETERMINISTIC_TEMPERATURE);
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", responseSchema);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        request.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))));
        request.put("generationConfig", generationConfig);
        return request;
    }

    /** Pulls the answer out of the provider envelope, skipping any reasoning parts the model emits. */
    private String extractGeneratedTextFrom(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw LlmException.invalidResponse("empty response body");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (RuntimeException exception) {
            throw LlmException.invalidResponse("unreadable provider envelope");
        }

        JsonNode blockReason = root.path("promptFeedback").path("blockReason");
        if (!blockReason.isMissingNode() && !blockReason.isNull()) {
            throw LlmException.invalidResponse("the request was blocked by the provider safety filters");
        }

        StringBuilder generatedText = new StringBuilder();
        for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            generatedText.append(part.path("text").asString(""));
        }
        if (generatedText.isEmpty()) {
            throw LlmException.invalidResponse("the model returned no content");
        }
        return generatedText.toString();
    }

    private LlmException translateNetworkFailure(ResourceAccessException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof SocketTimeoutException) {
            return LlmException.timedOut(exception);
        }
        if (cause instanceof IOException) {
            return LlmException.unavailable("network error", exception);
        }
        return LlmException.unavailable("connection failed", exception);
    }

    private LlmException translateErrorStatus(HttpStatusCode status) {
        int statusCode = status.value();
        if (statusCode == 429) {
            return LlmException.rateLimited();
        }
        if (statusCode == 401 || statusCode == 403) {
            return new LlmException("LLM_NOT_CONFIGURED",
                    "The AI provider rejected the API key. Check LLM_API_KEY and restart the app.", false);
        }
        if (statusCode == 404) {
            return new LlmException("LLM_UNAVAILABLE",
                    "The AI model " + llmProperties.model() + " was not found. Set LLM_MODEL to a model your key can use.",
                    false);
        }
        if (statusCode == 400) {
            return new LlmException("LLM_UNAVAILABLE",
                    "The AI provider rejected the request. Your previous search state has been preserved.", false);
        }
        return LlmException.unavailable("provider returned HTTP " + statusCode, null);
    }

    /**
     * Demo aid. MALFORMED deliberately returns broken text rather than throwing, so the real parsing
     * and validation path is what rejects it - exactly as it would with a genuinely bad model answer.
     */
    private String simulateArmedFault(FaultInjector.Fault fault) {
        log.warn("Injecting simulated LLM fault: {}", fault);
        return switch (fault) {
            case TIMEOUT -> throw LlmException.timedOut(new SocketTimeoutException("simulated timeout"));
            case RATE_LIMIT -> throw LlmException.rateLimited();
            case EMPTY -> throw LlmException.invalidResponse("the model returned no content");
            case MALFORMED -> "{\"filters\": {\"skills\": [\"AWS RDS\"  \"min_years_experience\": ,,, truncated";
        };
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_BACKOFF);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
