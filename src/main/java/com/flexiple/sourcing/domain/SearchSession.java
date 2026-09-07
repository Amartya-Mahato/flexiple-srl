package com.flexiple.sourcing.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * All state for one sourcing session, held in memory for the life of the process.
 * Deliberately mutable: it is the one thing in the app that evolves as the recruiter works.
 * A refinement is only written here after the model's output has fully passed validation,
 * so a bad LLM response can never corrupt a good search.
 */
public final class SearchSession {

    private final String id;
    private final String originalQuery;
    private final Instant createdAt;
    private final List<RefinementRound> refinementHistory = new ArrayList<>();

    private ObjectiveFilters currentFilters;
    private Rubric currentRubric;
    private FiltersOrigin filtersOrigin = FiltersOrigin.AI_GENERATED;
    private List<ScoredCandidate> currentResults = List.of();
    private int matchedProfileCount;
    private ScoringMode lastScoringMode;
    private boolean frozen;

    public SearchSession(String id, String originalQuery, ObjectiveFilters filters, Rubric rubric) {
        this.id = id;
        this.originalQuery = originalQuery;
        this.currentFilters = filters;
        this.currentRubric = rubric;
        this.createdAt = Instant.now();
    }

    public void replaceSearchDefinition(ObjectiveFilters filters, Rubric rubric, FiltersOrigin origin) {
        this.currentFilters = filters;
        this.currentRubric = rubric;
        this.filtersOrigin = origin;
    }

    public void recordResults(List<ScoredCandidate> results, int matchedCount, ScoringMode scoringMode) {
        this.currentResults = List.copyOf(results);
        this.matchedProfileCount = matchedCount;
        this.lastScoringMode = results.isEmpty() ? null : scoringMode;
    }

    public void appendRefinementRound(RefinementRound round) {
        refinementHistory.add(round);
    }

    /**
     * Undoes the most recent round by restoring the definition it started from.
     * The round is removed rather than inverted, so the history stays a truthful record of the
     * search that actually stands.
     */
    public RefinementRound revertLastRefinementRound() {
        if (refinementHistory.isEmpty()) {
            throw new IllegalStateException("There is nothing to undo - this is the original search.");
        }
        RefinementRound undone = refinementHistory.removeLast();
        this.currentFilters = undone.filtersBefore();
        this.currentRubric = undone.rubricBefore();
        this.filtersOrigin = refinementHistory.isEmpty()
                ? FiltersOrigin.AI_GENERATED
                : lastRoundOrigin(refinementHistory.getLast());
        return undone;
    }

    private FiltersOrigin lastRoundOrigin(RefinementRound round) {
        return "manual_edit".equals(round.source()) ? FiltersOrigin.MANUALLY_EDITED : FiltersOrigin.AI_GENERATED;
    }

    public void freeze() {
        this.frozen = true;
    }

    public int nextRoundNumber() {
        return refinementHistory.size() + 1;
    }

    public String id() {
        return id;
    }

    public String originalQuery() {
        return originalQuery;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ObjectiveFilters currentFilters() {
        return currentFilters;
    }

    public Rubric currentRubric() {
        return currentRubric;
    }

    public FiltersOrigin filtersOrigin() {
        return filtersOrigin;
    }

    public List<ScoredCandidate> currentResults() {
        return currentResults;
    }

    public int matchedProfileCount() {
        return matchedProfileCount;
    }

    public ScoringMode lastScoringMode() {
        return lastScoringMode;
    }

    public boolean canUndo() {
        return !frozen && !refinementHistory.isEmpty();
    }

    public boolean isFrozen() {
        return frozen;
    }

    public List<RefinementRound> refinementHistory() {
        return List.copyOf(refinementHistory);
    }
}
