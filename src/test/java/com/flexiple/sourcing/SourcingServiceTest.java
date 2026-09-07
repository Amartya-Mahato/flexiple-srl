package com.flexiple.sourcing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flexiple.sourcing.domain.CandidateVerdict;
import com.flexiple.sourcing.domain.Evidence;
import com.flexiple.sourcing.domain.FiltersOrigin;
import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Profile;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.RubricCriterion;
import com.flexiple.sourcing.domain.ScoredCandidate;
import com.flexiple.sourcing.domain.ScoringMode;
import com.flexiple.sourcing.domain.SearchChange;
import com.flexiple.sourcing.domain.SearchSession;
import com.flexiple.sourcing.llm.LlmException;
import com.flexiple.sourcing.llm.ops.CandidateScorer;
import com.flexiple.sourcing.llm.ops.SearchDefinitionParser;
import com.flexiple.sourcing.llm.ops.SearchRefiner;
import com.flexiple.sourcing.profiles.FilterEngine;
import com.flexiple.sourcing.profiles.LocalRelevanceScorer;
import com.flexiple.sourcing.profiles.ProfileRepository;
import com.flexiple.sourcing.profiles.SkillNormalizer;
import com.flexiple.sourcing.profiles.TalentPoolFixture;
import com.flexiple.sourcing.session.SessionStore;
import com.flexiple.sourcing.validation.SearchDefinitionValidator;
import com.flexiple.sourcing.validation.StarterRubricFactory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The loop end to end with the three AI operations stubbed, so the behaviour under test is ours:
 * that filtering is local, that state only moves forward on valid output, and that freezing is final.
 */
class SourcingServiceTest {

    private static final String CANONICAL_QUERY =
            "RDS developers with 4-7 years of experience who have worked at startups, based in Bangalore";

    private final ProfileRepository repository = TalentPoolFixture.loadRealTalentPool();
    private final SearchDefinitionParser parser = mock(SearchDefinitionParser.class);
    private final CandidateScorer scorer = mock(CandidateScorer.class);
    private final SearchRefiner refiner = mock(SearchRefiner.class);

    private SourcingService sourcingService;

    private final ObjectiveFilters initialFilters = new ObjectiveFilters(List.of("RDS"), 4, 7,
            List.of("Bangalore"), List.of("startup"), List.of(), List.of(), false, false);
    private final Rubric initialRubric = new Rubric("Backend engineer who owns databases",
            List.of(new RubricCriterion("Database depth", "Has run production databases", 1.0)));

    @BeforeEach
    void setUp() {
        SkillNormalizer skillNormalizer = new SkillNormalizer();
        sourcingService = new SourcingService(repository, new FilterEngine(skillNormalizer), parser, scorer, refiner,
                new LocalRelevanceScorer(skillNormalizer), new SearchDefinitionValidator(),
                new StarterRubricFactory(), new SessionStore());

        when(parser.parseRecruiterRequirement(anyString()))
                .thenReturn(new SearchDefinitionParser.ParsedSearchDefinition(initialFilters, initialRubric));
        when(scorer.scoreProfilesAgainstRubric(anyString(), any(), anyList()))
                .thenAnswer(invocation -> scoreEveryProfileInOrder(invocation.getArgument(2)));
    }

    private List<ScoredCandidate> scoreEveryProfileInOrder(List<Profile> profiles) {
        return java.util.stream.IntStream.range(0, profiles.size())
                .mapToObj(index -> new ScoredCandidate(index + 1, profiles.get(index), 90 - index,
                        List.of(new Evidence("location", profiles.get(index).location())), "Stubbed explanation."))
                .toList();
    }

    private SearchSession startAndScoreASearch() {
        SearchSession session = sourcingService.startSearchFromFreeText(CANONICAL_QUERY);
        return sourcingService.runScoringForCurrentSearchDefinition(session.id(), ScoringMode.AI);
    }

    @Test
    void filtersLocallyAndScoresOnlyTheProfilesThatSurvived() {
        SearchSession session = startAndScoreASearch();

        assertThat(session.matchedProfileCount()).isEqualTo(6);
        assertThat(session.currentResults()).extracting(candidate -> candidate.profile().id())
                .containsExactly("p01", "p02", "p03", "p04", "p05", "p06");
    }

    @Test
    void appliesAValidRefinementAndRecordsWhatChangedAndWhy() {
        SearchSession session = startAndScoreASearch();
        ObjectiveFilters tightened = new ObjectiveFilters(List.of("RDS"), 6, 7, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), false, false);
        when(refiner.refineSearchFromRecruiterFeedback(any(), anyString(), anyList()))
                .thenReturn(new SearchRefiner.RefinedSearchDefinition(tightened, initialRubric,
                        List.of(new SearchChange("filter", "min_years_experience", "4", "6", "Candidate 1 was too junior.")),
                        "Raised the minimum to 6 years."));

        SearchSession refined = sourcingService.refineSearchWithFeedback(session.id(), "1 is too junior",
                List.of(new CandidateVerdict("p01", "no")));

        assertThat(refined.currentFilters().minYearsExperience()).isEqualTo(6);
        assertThat(refined.matchedProfileCount()).isLessThan(6);
        assertThat(refined.refinementHistory()).hasSize(1);
        assertThat(refined.refinementHistory().getFirst().filtersBefore().minYearsExperience()).isEqualTo(4);
        assertThat(refined.refinementHistory().getFirst().changes()).hasSize(1);
        assertThat(refined.refinementHistory().getFirst().reply()).isEqualTo("Raised the minimum to 6 years.");
    }

    @Test
    void leavesThePreviousSearchCompletelyIntactWhenARefinementIsUnusable() {
        SearchSession session = startAndScoreASearch();
        List<String> resultsBefore = session.currentResults().stream().map(candidate -> candidate.profile().id()).toList();
        when(refiner.refineSearchFromRecruiterFeedback(any(), anyString(), anyList()))
                .thenThrow(LlmException.invalidResponse("simulated malformed JSON"));

        assertThatThrownBy(() -> sourcingService.refineSearchWithFeedback(session.id(), "make it better", List.of()))
                .isInstanceOf(LlmException.class);

        SearchSession afterFailure = sourcingService.getSession(session.id());
        assertThat(afterFailure.currentFilters()).isEqualTo(initialFilters);
        assertThat(afterFailure.currentRubric()).isEqualTo(initialRubric);
        assertThat(afterFailure.refinementHistory()).isEmpty();
        assertThat(afterFailure.currentResults().stream().map(candidate -> candidate.profile().id()))
                .containsExactlyElementsOf(resultsBefore);
    }

    @Test
    void refusesARefinementThatWouldInvertTheExperienceRange() {
        SearchSession session = startAndScoreASearch();
        ObjectiveFilters nonsense = new ObjectiveFilters(List.of("RDS"), 9, 2, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), false, false);
        when(refiner.refineSearchFromRecruiterFeedback(any(), anyString(), anyList()))
                .thenReturn(new SearchRefiner.RefinedSearchDefinition(nonsense, initialRubric, List.of(), "Done."));

        assertThatThrownBy(() -> sourcingService.refineSearchWithFeedback(session.id(), "senior only", List.of()))
                .isInstanceOf(LlmException.class);
        assertThat(sourcingService.getSession(session.id()).currentFilters()).isEqualTo(initialFilters);
    }

    @Test
    void marksHandEditsAsTheRecruitersOwnAndExplainsThemToo() {
        SearchSession session = startAndScoreASearch();
        ObjectiveFilters edited = new ObjectiveFilters(List.of("RDS"), 5, 7, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), false, false);

        SearchSession updated = sourcingService.applyManualSearchDefinitionEdits(session.id(), edited, initialRubric, ScoringMode.AI);

        assertThat(updated.filtersOrigin()).isEqualTo(FiltersOrigin.MANUALLY_EDITED);
        assertThat(updated.refinementHistory().getFirst().source()).isEqualTo("manual_edit");
        assertThat(updated.refinementHistory().getFirst().changes())
                .anySatisfy(change -> assertThat(change.field()).isEqualTo("min_years_experience"));
    }

    @Test
    void handlesAnEmptyResultSetWithoutCallingTheModelOrFailing() {
        SearchSession session = startAndScoreASearch();
        ObjectiveFilters impossible = new ObjectiveFilters(List.of("Swift"), 12, 13, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), true, false);

        SearchSession updated = sourcingService.applyManualSearchDefinitionEdits(session.id(), impossible, initialRubric, ScoringMode.AI);

        assertThat(updated.matchedProfileCount()).isZero();
        assertThat(updated.currentResults()).isEmpty();
        assertThat(sourcingService.explainWhyNothingMatched(updated)).isNotEmpty();
    }

    @Test
    void freezingMakesTheSearchFinal() {
        SearchSession session = startAndScoreASearch();

        SearchSession frozen = sourcingService.freezeSearch(session.id());
        assertThat(frozen.isFrozen()).isTrue();

        assertThatThrownBy(() -> sourcingService.refineSearchWithFeedback(session.id(), "one more tweak", List.of()))
                .isInstanceOf(SessionStore.SessionFrozenException.class);
        assertThatThrownBy(() -> sourcingService.applyManualSearchDefinitionEdits(session.id(), initialFilters, initialRubric, ScoringMode.AI))
                .isInstanceOf(SessionStore.SessionFrozenException.class);
        assertThatThrownBy(() -> sourcingService.freezeSearch(session.id()))
                .isInstanceOf(SessionStore.SessionFrozenException.class);

        assertThat(sourcingService.getSession(session.id()).currentResults()).isNotEmpty();
    }

    @Test
    void startsAManualSearchWithNoModelCallAtAllAndStillRanksTheResults() {
        ObjectiveFilters handBuilt = new ObjectiveFilters(List.of("AWS RDS"), 4, 7, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), false, false);

        SearchSession session = sourcingService.startSearchFromManualFilters(handBuilt, null);

        assertThat(session.matchedProfileCount()).isEqualTo(6);
        assertThat(session.currentResults()).hasSize(6);
        assertThat(session.lastScoringMode()).isEqualTo(ScoringMode.LOCAL);
        assertThat(session.filtersOrigin()).isEqualTo(FiltersOrigin.MANUALLY_EDITED);
        assertThat(session.currentRubric().criteria()).isNotEmpty();
        verifyNoInteractions(parser, scorer, refiner);
    }

    @Test
    void locallyRankedResultsAreOrderedAndExplainedFromProfileFacts() {
        ObjectiveFilters handBuilt = new ObjectiveFilters(List.of("AWS RDS"), 4, 7, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), false, false);

        SearchSession session = sourcingService.startSearchFromManualFilters(handBuilt, null);

        assertThat(session.currentResults()).extracting(ScoredCandidate::rank).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(session.currentResults()).isSortedAccordingTo(
                java.util.Comparator.comparingInt(ScoredCandidate::score).reversed());
        assertThat(session.currentResults()).allSatisfy(candidate -> {
            assertThat(candidate.score()).isBetween(0, 100);
            assertThat(candidate.explanation()).contains(candidate.profile().currentCompany());
        });
    }

    @Test
    void undoRestoresTheDefinitionTheLastRoundStartedFrom() {
        SearchSession session = startAndScoreASearch();
        ObjectiveFilters tightened = new ObjectiveFilters(List.of("RDS"), 6, 7, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), false, false);
        when(refiner.refineSearchFromRecruiterFeedback(any(), anyString(), anyList()))
                .thenReturn(new SearchRefiner.RefinedSearchDefinition(tightened, initialRubric, List.of(), "Tightened."));
        sourcingService.refineSearchWithFeedback(session.id(), "too junior", List.of());
        assertThat(session.currentFilters().minYearsExperience()).isEqualTo(6);

        SearchSession restored = sourcingService.undoLastRefinement(session.id(), ScoringMode.LOCAL);

        assertThat(restored.currentFilters()).isEqualTo(initialFilters);
        assertThat(restored.currentRubric()).isEqualTo(initialRubric);
        assertThat(restored.refinementHistory()).isEmpty();
        assertThat(restored.canUndo()).isFalse();
        assertThat(restored.matchedProfileCount()).isEqualTo(6);
    }

    @Test
    void refusesToUndoWhenThereIsNothingBeforeTheOriginalSearch() {
        SearchSession session = startAndScoreASearch();

        assertThatThrownBy(() -> sourcingService.undoLastRefinement(session.id(), ScoringMode.LOCAL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rollsTheRefinementBackWhenRankingFailsAfterTheDefinitionWasUpdated() {
        SearchSession session = startAndScoreASearch();
        List<String> resultsBefore = session.currentResults().stream().map(candidate -> candidate.profile().id()).toList();
        ObjectiveFilters tightened = new ObjectiveFilters(List.of("RDS"), 6, 7, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), false, false);
        when(refiner.refineSearchFromRecruiterFeedback(any(), anyString(), anyList()))
                .thenReturn(new SearchRefiner.RefinedSearchDefinition(tightened, initialRubric, List.of(), "Tightened."));
        // The definition is accepted, then ranking dies - the half-applied state the UI must never see.
        when(scorer.scoreProfilesAgainstRubric(anyString(), any(), anyList()))
                .thenThrow(LlmException.rateLimited());

        assertThatThrownBy(() -> sourcingService.refineSearchWithFeedback(session.id(), "too junior", List.of()))
                .isInstanceOf(LlmException.class)
                .satisfies(failure -> assertThat(((LlmException) failure).code()).isEqualTo("LLM_RATE_LIMITED"));

        SearchSession afterFailure = sourcingService.getSession(session.id());
        assertThat(afterFailure.currentFilters()).isEqualTo(initialFilters);
        assertThat(afterFailure.currentRubric()).isEqualTo(initialRubric);
        assertThat(afterFailure.refinementHistory()).isEmpty();
        assertThat(afterFailure.canUndo()).isFalse();
        assertThat(afterFailure.currentResults().stream().map(candidate -> candidate.profile().id()))
                .containsExactlyElementsOf(resultsBefore);
    }

    @Test
    void rejectsWorkOnASessionThatDoesNotExist() {
        assertThatThrownBy(() -> sourcingService.getSession("no-such-session"))
                .isInstanceOf(SessionStore.SessionNotFoundException.class);
    }
}
