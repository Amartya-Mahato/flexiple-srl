package com.flexiple.sourcing.web.dto;

import com.flexiple.sourcing.domain.Evidence;
import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.PastCompany;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.SearchChange;
import com.flexiple.sourcing.profiles.FilterEngine;
import java.util.List;

/**
 * Everything the workspace needs to render itself after any action. One response shape for every
 * endpoint keeps the frontend to a single render path instead of five partial updates.
 */
public record SearchStateResponse(
        String sessionId,
        String originalQuery,
        ObjectiveFilters filters,
        Rubric rubric,
        String filtersOrigin,
        List<CandidateView> results,
        int matchedCount,
        int talentPoolSize,
        boolean frozen,
        boolean scored,
        String scoringMode,
        boolean canUndo,
        List<RefinementRoundView> history,
        List<FilterEngine.DimensionMatchCount> filterDiagnostics) {

    /** A result card, flattened so the frontend never digs through nested objects. */
    public record CandidateView(
            int rank,
            String profileId,
            String name,
            String currentTitle,
            int yearsExperience,
            String location,
            String currentCompany,
            String currentCompanyType,
            List<String> skills,
            List<String> matchedSkills,
            List<PastCompany> pastCompanies,
            String education,
            String profileSummary,
            int score,
            List<Evidence> evidence,
            String explanation) {
    }

    /** One turn of the loop, as the recruiter should read it. */
    public record RefinementRoundView(
            int roundNumber,
            String source,
            String feedback,
            String reply,
            List<SearchChange> changes,
            String at) {
    }
}
