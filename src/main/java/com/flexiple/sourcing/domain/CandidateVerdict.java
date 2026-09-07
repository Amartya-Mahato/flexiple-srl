package com.flexiple.sourcing.domain;

/** The recruiter's per-profile thumbs up or down, collected from the result cards. */
public record CandidateVerdict(String profileId, String verdict) {

    public boolean isYes() {
        return "yes".equalsIgnoreCase(verdict);
    }
}
