package com.flexiple.sourcing.web;

import com.flexiple.sourcing.llm.LlmException;
import com.flexiple.sourcing.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One error shape for the whole API: {@code {code, message, retryable}}. The frontend maps the code
 * to friendly copy and decides whether to offer a retry; nothing here leaks provider detail or keys.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(LlmException.class)
    ResponseEntity<ApiError> handleLlmFailure(LlmException exception) {
        log.warn("AI step failed: {} - {}", exception.code(), exception.getMessage());
        return ResponseEntity.status(statusForLlmCode(exception.code()))
                .body(new ApiError(exception.code(), exception.getMessage(), exception.isRetryable()));
    }

    @ExceptionHandler(SessionStore.SessionNotFoundException.class)
    ResponseEntity<ApiError> handleMissingSession(SessionStore.SessionNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SESSION_NOT_FOUND", exception.getMessage(), false));
    }

    @ExceptionHandler(SessionStore.SessionFrozenException.class)
    ResponseEntity<ApiError> handleFrozenSession(SessionStore.SessionFrozenException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("SESSION_FROZEN", exception.getMessage(), false));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleInvalidRequest(MethodArgumentNotValidException exception) {
        String firstProblem = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse("That request was not valid.");
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", firstProblem, false));
    }

    /** Asking to undo the original search is a legitimate no-op, not a server fault. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> handleNothingToUndo(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("NOTHING_TO_UNDO", exception.getMessage(), false));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", exception.getMessage(), false));
    }

    /** Last line of defence: an unexpected bug must still leave the app running and the session intact. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpectedFailure(Exception exception) {
        log.error("Unexpected server error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("INTERNAL_ERROR",
                "Something went wrong on our side. Your previous search state has been preserved.", true));
    }

    private HttpStatus statusForLlmCode(String code) {
        return switch (code) {
            case "LLM_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "LLM_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "INVALID_LLM_RESPONSE" -> HttpStatus.BAD_GATEWAY;
            case "LLM_NOT_CONFIGURED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    record ApiError(String code, String message, boolean retryable) {
    }
}
