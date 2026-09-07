package com.flexiple.sourcing.domain;

import java.util.List;

/** A profile the model scored, after validation and grounding, ready to render. */
public record ScoredCandidate(
        int rank,
        Profile profile,
        int score,
        List<Evidence> evidence,
        String explanation) {

    public ScoredCandidate {
        evidence = evidence == null ? List.of() : evidence.stream().filter(java.util.Objects::nonNull).toList();
    }

    public ScoredCandidate withRank(int newRank) {
        return new ScoredCandidate(newRank, profile, score, evidence, explanation);
    }
}
