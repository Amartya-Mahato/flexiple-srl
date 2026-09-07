package com.flexiple.sourcing.web.dto;

import com.flexiple.sourcing.domain.CandidateVerdict;
import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.ScoringMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Incoming request bodies. Every free-text field is length capped at the edge so a pasted novel
 * never reaches the model, and every session id is required rather than defaulted.
 */
public final class Requests {

    private Requests() {
    }

    /** AI ranking unless the caller explicitly asks for the instant local one. */
    private static ScoringMode scoringModeOrDefault(String rawMode) {
        return ScoringMode.parseLenient(rawMode).orElse(ScoringMode.AI);
    }

    public record StartSearchRequest(
            @NotBlank(message = "Describe who you are looking for.")
            @Size(max = 600, message = "Keep the requirement under 600 characters.")
            String query) {
    }

    public record ManualStartRequest(ObjectiveFilters filters, Rubric rubric) {
    }

    public record RunSearchRequest(@NotBlank String sessionId, String scoringMode) {

        public ScoringMode resolvedScoringMode() {
            return scoringModeOrDefault(scoringMode);
        }
    }

    public record SessionRequest(@NotBlank String sessionId) {
    }

    public record RefineSearchRequest(
            @NotBlank String sessionId,
            @Size(max = 1000, message = "Keep feedback under 1000 characters.") String feedback,
            List<CandidateVerdict> verdicts) {

        public RefineSearchRequest {
            verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        }
    }

    public record ManualUpdateRequest(@NotBlank String sessionId, ObjectiveFilters filters, Rubric rubric,
            String scoringMode) {

        public ScoringMode resolvedScoringMode() {
            return scoringModeOrDefault(scoringMode);
        }
    }

    public record ArmFaultRequest(@NotBlank String mode) {
    }
}
