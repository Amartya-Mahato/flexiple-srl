package com.flexiple.sourcing;

import com.flexiple.sourcing.domain.CandidateVerdict;
import com.flexiple.sourcing.domain.FiltersOrigin;
import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Profile;
import com.flexiple.sourcing.domain.RefinementRound;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.ScoredCandidate;
import com.flexiple.sourcing.domain.SearchChange;
import com.flexiple.sourcing.domain.ScoringMode;
import com.flexiple.sourcing.domain.SearchSession;
import com.flexiple.sourcing.llm.ops.CandidateScorer;
import com.flexiple.sourcing.llm.ops.SearchDefinitionParser;
import com.flexiple.sourcing.llm.ops.SearchRefiner;
import com.flexiple.sourcing.profiles.FilterEngine;
import com.flexiple.sourcing.profiles.LocalRelevanceScorer;
import com.flexiple.sourcing.profiles.ProfileRepository;
import com.flexiple.sourcing.session.SessionStore;
import com.flexiple.sourcing.validation.SearchDefinitionValidator;
import com.flexiple.sourcing.validation.StarterRubricFactory;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The sourcing loop itself: parse, filter, score, refine, freeze.
 *
 * <p>The order matters and is always the same - the model defines what matching means, Java decides
 * who matches. Session state is only written after every validation step has passed, so a failed
 * refinement is a no-op rather than a corrupted search.
 */
@Service
public class SourcingService {

    private static final Logger log = LoggerFactory.getLogger(SourcingService.class);

    private final ProfileRepository profileRepository;
    private final FilterEngine filterEngine;
    private final SearchDefinitionParser searchDefinitionParser;
    private final CandidateScorer candidateScorer;
    private final SearchRefiner searchRefiner;
    private final LocalRelevanceScorer localRelevanceScorer;
    private final SearchDefinitionValidator validator;
    private final StarterRubricFactory starterRubricFactory;
    private final SessionStore sessionStore;

    SourcingService(ProfileRepository profileRepository, FilterEngine filterEngine,
            SearchDefinitionParser searchDefinitionParser, CandidateScorer candidateScorer,
            SearchRefiner searchRefiner, LocalRelevanceScorer localRelevanceScorer,
            SearchDefinitionValidator validator, StarterRubricFactory starterRubricFactory,
            SessionStore sessionStore) {
        this.profileRepository = profileRepository;
        this.filterEngine = filterEngine;
        this.searchDefinitionParser = searchDefinitionParser;
        this.candidateScorer = candidateScorer;
        this.searchRefiner = searchRefiner;
        this.localRelevanceScorer = localRelevanceScorer;
        this.validator = validator;
        this.starterRubricFactory = starterRubricFactory;
        this.sessionStore = sessionStore;
    }

    /** Step 1: understand the requirement and open a session. No scoring yet, so the UI can paint early. */
    public SearchSession startSearchFromFreeText(String recruiterRequirement) {
        log.info("Starting search for requirement of {} characters", recruiterRequirement.length());
        SearchDefinitionParser.ParsedSearchDefinition parsed =
                searchDefinitionParser.parseRecruiterRequirement(recruiterRequirement);
        SearchSession session = sessionStore.createSession(recruiterRequirement, parsed.filters(), parsed.rubric());
        session.recordResults(List.of(), countProfilesMatchingCurrentFilters(session), null);
        return session;
    }

    /**
     * The manual way in: filters the recruiter built by hand, no model involved. A starter rubric is
     * derived locally so the search is complete enough to hand to the AI later if they want to.
     */
    public SearchSession startSearchFromManualFilters(ObjectiveFilters handBuiltFilters, Rubric suppliedRubric) {
        ObjectiveFilters filters = validator.validateAndNormalizeFilters(handBuiltFilters);
        Rubric rubric = suppliedRubric == null
                ? starterRubricFactory.deriveStarterRubricFrom(filters)
                : validator.validateAndNormalizeRubric(suppliedRubric);

        SearchSession session = sessionStore.createSession(describeManualSearch(filters), filters, rubric);
        session.replaceSearchDefinition(filters, rubric, FiltersOrigin.MANUALLY_EDITED);
        log.info("Started a manual search with {} skill(s) and {} location(s)",
                filters.skills().size(), filters.locations().size());
        applyFiltersAndScoreInto(session, ScoringMode.LOCAL);
        return session;
    }

    /**
     * Step 2: rank whoever survived the objective filters.
     *
     * @param scoringMode AI judges them against the fit rubric; LOCAL ranks them in Java from the
     *                    filters alone, instantly and without an API key.
     */
    public SearchSession runScoringForCurrentSearchDefinition(String sessionId, ScoringMode scoringMode) {
        SearchSession session = sessionStore.requireEditableSession(sessionId);
        applyFiltersAndScoreInto(session, scoringMode);
        return session;
    }

    /** Steps back to the search definition the last round started from, then re-runs it. */
    public SearchSession undoLastRefinement(String sessionId, ScoringMode scoringMode) {
        SearchSession session = sessionStore.requireEditableSession(sessionId);
        RefinementRound undone = session.revertLastRefinementRound();
        log.info("Undid refinement round {}", undone.roundNumber());
        applyFiltersAndScoreInto(session, scoringMode);
        return session;
    }

    /** Step 3: fold recruiter feedback into the search definition, then re-run the whole thing. */
    public SearchSession refineSearchWithFeedback(String sessionId, String feedback, List<CandidateVerdict> verdicts) {
        SearchSession session = sessionStore.requireEditableSession(sessionId);

        SearchRefiner.RefinedSearchDefinition refined =
                searchRefiner.refineSearchFromRecruiterFeedback(session, feedback, verdicts);

        // Validated again here, at the only point that writes session state, so nothing unchecked
        // can ever replace a search the recruiter is happy with.
        ObjectiveFilters filters = validator.validateAndNormalizeFilters(refined.filters());
        Rubric rubric = validator.validateAndNormalizeRubric(refined.rubric());

        recordRoundAndReplaceDefinition(session, "recruiter_feedback", feedback, verdicts,
                filters, rubric, refined.changes(), refined.reply(), FiltersOrigin.AI_GENERATED);
        rankOrRollBackTheRoundJustRecorded(session, ScoringMode.AI);
        return session;
    }

    /** The recruiter editing the filters or rubric by hand. Same re-run, but never presented as the model's doing. */
    public SearchSession applyManualSearchDefinitionEdits(String sessionId, ObjectiveFilters editedFilters,
            Rubric editedRubric, ScoringMode scoringMode) {
        SearchSession session = sessionStore.requireEditableSession(sessionId);
        ObjectiveFilters filters = validator.validateAndNormalizeFilters(editedFilters);
        Rubric rubric = validator.validateAndNormalizeRubric(editedRubric);

        List<SearchChange> changes = describeDifferences(session.currentFilters(), filters,
                session.currentRubric(), rubric);
        recordRoundAndReplaceDefinition(session, "manual_edit", "You edited the search definition directly.",
                List.of(), filters, rubric, changes, "Applied your edits to the search definition.",
                FiltersOrigin.MANUALLY_EDITED);
        rankOrRollBackTheRoundJustRecorded(session, scoringMode);
        return session;
    }

    /** Step 4: freeze. Nothing about the search can change after this. */
    public SearchSession freezeSearch(String sessionId) {
        SearchSession session = sessionStore.requireEditableSession(sessionId);
        session.freeze();
        log.info("Search frozen after {} refinement round(s) with {} matching profiles",
                session.refinementHistory().size(), session.matchedProfileCount());
        return session;
    }

    public SearchSession getSession(String sessionId) {
        return sessionStore.requireSession(sessionId);
    }

    /**
     * How many profiles a set of filters would select, without creating a session or calling anything.
     * Powers the live count under the manual search builder, so the recruiter sees the cost of every
     * criterion as they add it rather than after they hit search.
     */
    public FilterPreview previewFilters(ObjectiveFilters candidateFilters) {
        ObjectiveFilters filters = validator.validateAndNormalizeFilters(candidateFilters);
        int matchCount = filterEngine.applyObjectiveFiltersToTalentPool(filters, profileRepository.allProfiles()).size();
        return new FilterPreview(matchCount, talentPoolSize(),
                filterEngine.countMatchesPerDimension(filters, profileRepository.allProfiles()));
    }

    public record FilterPreview(int matchedCount, int talentPoolSize,
            List<FilterEngine.DimensionMatchCount> dimensionMatchCounts) {
    }

    public List<FilterEngine.DimensionMatchCount> explainWhyNothingMatched(SearchSession session) {
        return filterEngine.countMatchesPerDimension(session.currentFilters(), profileRepository.allProfiles());
    }

    public int talentPoolSize() {
        return profileRepository.allProfiles().size();
    }

    /**
     * Ranking is the second half of a refinement, and it can fail on its own - a timeout or a rate
     * limit between updating the definition and scoring it would otherwise leave the session showing
     * new filters beside stale results. Rolling the round back keeps the promise the UI makes: a
     * failed refinement changes nothing at all.
     */
    private void rankOrRollBackTheRoundJustRecorded(SearchSession session, ScoringMode scoringMode) {
        try {
            applyFiltersAndScoreInto(session, scoringMode);
        } catch (RuntimeException rankingFailure) {
            session.revertLastRefinementRound();
            log.warn("Rolled back the refinement because ranking failed: {}", rankingFailure.getMessage());
            throw rankingFailure;
        }
    }

    private void applyFiltersAndScoreInto(SearchSession session, ScoringMode scoringMode) {
        List<Profile> matchingProfiles = filterEngine.applyObjectiveFiltersToTalentPool(
                session.currentFilters(), profileRepository.allProfiles());
        log.info("{} of {} profiles matched the objective filters", matchingProfiles.size(), talentPoolSize());

        List<ScoredCandidate> ranked = scoringMode == ScoringMode.LOCAL
                ? localRelevanceScorer.rankByHowWellTheyMatchTheFilters(matchingProfiles, session.currentFilters())
                : candidateScorer.scoreProfilesAgainstRubric(
                        session.originalQuery(), session.currentRubric(), matchingProfiles);
        session.recordResults(ranked, matchingProfiles.size(), scoringMode);
    }

    /** A readable stand-in for the free-text query when the recruiter never typed one. */
    private String describeManualSearch(ObjectiveFilters filters) {
        List<String> parts = new java.util.ArrayList<>();
        if (!filters.skills().isEmpty()) {
            parts.add(String.join(" + ", filters.skills()));
        }
        if (filters.minYearsExperience() != null || filters.maxYearsExperience() != null) {
            parts.add(describeYearRange(filters));
        }
        if (!filters.locations().isEmpty()) {
            parts.add("in " + String.join(" or ", filters.locations()));
        }
        if (!filters.companyTypes().isEmpty()) {
            parts.add("currently at a " + String.join(" or ", filters.companyTypes()));
        }
        return parts.isEmpty() ? "Manual search across the whole talent map" : String.join(", ", parts);
    }

    private String describeYearRange(ObjectiveFilters filters) {
        Integer minimum = filters.minYearsExperience();
        Integer maximum = filters.maxYearsExperience();
        if (minimum != null && maximum != null) {
            return minimum + "-" + maximum + " years";
        }
        return minimum != null ? minimum + "+ years" : "up to " + maximum + " years";
    }

    private int countProfilesMatchingCurrentFilters(SearchSession session) {
        return filterEngine.applyObjectiveFiltersToTalentPool(
                session.currentFilters(), profileRepository.allProfiles()).size();
    }

    private void recordRoundAndReplaceDefinition(SearchSession session, String source, String feedback,
            List<CandidateVerdict> verdicts, ObjectiveFilters newFilters, Rubric newRubric,
            List<SearchChange> changes, String reply, FiltersOrigin origin) {
        session.appendRefinementRound(new RefinementRound(session.nextRoundNumber(), source, feedback, verdicts,
                session.currentFilters(), newFilters, session.currentRubric(), newRubric, changes, reply, Instant.now()));
        session.replaceSearchDefinition(newFilters, newRubric, origin);
    }

    /** Change rows for a manual edit, computed in Java so the UI explains hand edits the same way it explains AI ones. */
    private List<SearchChange> describeDifferences(ObjectiveFilters filtersBefore, ObjectiveFilters filtersAfter,
            Rubric rubricBefore, Rubric rubricAfter) {
        List<SearchChange> changes = new java.util.ArrayList<>();
        addChangeIfDifferent(changes, "filter", "skills", String.join(", ", filtersBefore.skills()),
                String.join(", ", filtersAfter.skills()));
        addChangeIfDifferent(changes, "filter", "min_years_experience",
                String.valueOf(filtersBefore.minYearsExperience()), String.valueOf(filtersAfter.minYearsExperience()));
        addChangeIfDifferent(changes, "filter", "max_years_experience",
                String.valueOf(filtersBefore.maxYearsExperience()), String.valueOf(filtersAfter.maxYearsExperience()));
        addChangeIfDifferent(changes, "filter", "locations", String.join(", ", filtersBefore.locations()),
                String.join(", ", filtersAfter.locations()));
        addChangeIfDifferent(changes, "filter", "company_types", String.join(", ", filtersBefore.companyTypes()),
                String.join(", ", filtersAfter.companyTypes()));
        addChangeIfDifferent(changes, "filter", "past_company_types",
                String.join(", ", filtersBefore.pastCompanyTypes()), String.join(", ", filtersAfter.pastCompanyTypes()));
        addChangeIfDifferent(changes, "rubric", "criteria", describeRubric(rubricBefore), describeRubric(rubricAfter));
        return List.copyOf(changes);
    }

    private String describeRubric(Rubric rubric) {
        return rubric.criteria().stream()
                .map(criterion -> criterion.name() + " " + Math.round(criterion.weight() * 100) + "%")
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private void addChangeIfDifferent(List<SearchChange> changes, String type, String field, String before, String after) {
        if (!java.util.Objects.equals(before, after)) {
            changes.add(new SearchChange(type, field, before, after, "You edited this directly."));
        }
    }
}
