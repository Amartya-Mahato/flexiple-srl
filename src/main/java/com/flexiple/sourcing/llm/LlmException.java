package com.flexiple.sourcing.llm;

/**
 * Every way the AI step can fail, expressed as a stable code the frontend maps to friendly copy.
 * Provider bodies are never propagated - they can contain keys, quota details and noise.
 */
public class LlmException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public LlmException(String code, String message, boolean retryable) {
        this(code, message, retryable, null);
    }

    public LlmException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public static LlmException notConfigured() {
        return new LlmException("LLM_NOT_CONFIGURED",
                "No AI provider key is configured. Set the LLM_API_KEY environment variable and restart the app.", false);
    }

    public static LlmException timedOut(Throwable cause) {
        return new LlmException("LLM_TIMEOUT",
                "The AI service took too long to respond. Your previous search is untouched.", true, cause);
    }

    public static LlmException rateLimited() {
        return new LlmException("LLM_RATE_LIMITED",
                "The AI service is rate limiting us right now. Wait a few seconds and try again.", true);
    }

    public static LlmException unavailable(String detail, Throwable cause) {
        return new LlmException("LLM_UNAVAILABLE",
                "The AI service is temporarily unavailable (" + detail + "). Your previous search state has been preserved.",
                true, cause);
    }

    public static LlmException invalidResponse(String detail) {
        return new LlmException("INVALID_LLM_RESPONSE",
                "The AI returned an unusable answer (" + detail + "). Your previous valid search has been preserved.", true);
    }
}
