package com.flexiple.sourcing.domain;

import java.time.Instant;
import java.util.List;

/** One turn of the refinement loop, kept so the recruiter can see how the search evolved. */
public record RefinementRound(
        int roundNumber,
        String source,
        String feedback,
        List<CandidateVerdict> verdicts,
        ObjectiveFilters filtersBefore,
        ObjectiveFilters filtersAfter,
        Rubric rubricBefore,
        Rubric rubricAfter,
        List<SearchChange> changes,
        String reply,
        Instant at) {

    public RefinementRound {
        verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
