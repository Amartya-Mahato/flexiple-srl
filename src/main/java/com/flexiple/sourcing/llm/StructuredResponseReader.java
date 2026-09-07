package com.flexiple.sourcing.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Deserialises model output into typed Java objects, converting any parsing failure into a clean
 * {@code INVALID_LLM_RESPONSE}. Raw model JSON is never rendered and never reaches the browser.
 */
@Component
public class StructuredResponseReader {

    private static final Logger log = LoggerFactory.getLogger(StructuredResponseReader.class);

    private final ObjectMapper objectMapper;

    StructuredResponseReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T readOrReject(String modelJson, Class<T> targetType, String operationName) {
        try {
            T parsed = objectMapper.readValue(modelJson, targetType);
            if (parsed == null) {
                throw LlmException.invalidResponse("the model returned an empty object");
            }
            return parsed;
        } catch (LlmException alreadyTranslated) {
            throw alreadyTranslated;
        } catch (RuntimeException exception) {
            log.warn("LLM {} returned unparseable output ({} chars): {}", operationName,
                    modelJson == null ? 0 : modelJson.length(), exception.getMessage());
            throw LlmException.invalidResponse("the model returned text that does not match the expected shape");
        }
    }

    /** Serialises app objects for embedding in a prompt. */
    public String toPromptJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
