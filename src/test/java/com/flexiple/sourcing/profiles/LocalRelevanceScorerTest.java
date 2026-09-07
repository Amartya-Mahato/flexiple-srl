package com.flexiple.sourcing.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Profile;
import com.flexiple.sourcing.domain.ScoredCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The no-AI ranking: it has to be ordered, bounded, explainable and honest about ties. */
class LocalRelevanceScorerTest {

    private final ProfileRepository repository = TalentPoolFixture.loadRealTalentPool();
    private final SkillNormalizer skillNormalizer = new SkillNormalizer();
    private final FilterEngine filterEngine = new FilterEngine(skillNormalizer);
    private final LocalRelevanceScorer scorer = new LocalRelevanceScorer(skillNormalizer);

    private List<ScoredCandidate> rank(ObjectiveFilters filters) {
        List<Profile> matching = filterEngine.applyObjectiveFiltersToTalentPool(filters, repository.allProfiles());
        return scorer.rankByHowWellTheyMatchTheFilters(matching, filters);
    }

    @Test
    void scoresEveryoneWithinRangeAndRanksThemContiguously() {
        List<ScoredCandidate> ranked = rank(new ObjectiveFilters(List.of("PostgreSQL", "Kafka", "Python"), 4, 9,
                List.of("Bangalore", "Remote - India"), List.of("startup", "scaleup"), List.of(), List.of(), false, true));

        assertThat(ranked).isNotEmpty();
        assertThat(ranked).extracting(ScoredCandidate::rank)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, ranked.size()).boxed().toList());
        assertThat(ranked).allSatisfy(candidate -> assertThat(candidate.score()).isBetween(0, 100));
        assertThat(ranked).isSortedAccordingTo(java.util.Comparator.comparingInt(ScoredCandidate::score).reversed());
    }

    @Test
    void separatesCandidatesByHowManyOfTheRequiredSkillsTheyActuallyHave() {
        List<ScoredCandidate> ranked = rank(new ObjectiveFilters(List.of("PostgreSQL", "Kafka", "Python"), 4, 9,
                List.of("Bangalore", "Remote - India"), List.of("startup", "scaleup"), List.of(), List.of(), false, true));

        assertThat(ranked.getFirst().score()).isGreaterThan(ranked.getLast().score());
        assertThat(ranked.stream().map(ScoredCandidate::score).distinct()).hasSizeGreaterThan(1);
    }

    @Test
    void givesEveryoneFullMarksWhenTheFiltersCannotTellThemApart() {
        List<ScoredCandidate> ranked = rank(new ObjectiveFilters(List.of("AWS RDS"), 4, 7,
                List.of("Bangalore"), List.of("startup"), List.of(), List.of(), false, false));

        assertThat(ranked).hasSize(6);
        assertThat(ranked).allSatisfy(candidate -> assertThat(candidate.score()).isEqualTo(100));
    }

    @Test
    void breaksTiesByExperienceSoTheOrderIsStableRatherThanArbitrary() {
        List<ScoredCandidate> ranked = rank(new ObjectiveFilters(List.of("AWS RDS"), 4, 7,
                List.of("Bangalore"), List.of("startup"), List.of(), List.of(), false, false));

        assertThat(ranked).isSortedAccordingTo(
                java.util.Comparator.comparingInt((ScoredCandidate candidate) -> candidate.profile().yearsExperience())
                        .reversed());
        assertThat(rank(new ObjectiveFilters(List.of("AWS RDS"), 4, 7, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), false, false)))
                .extracting(candidate -> candidate.profile().id())
                .isEqualTo(ranked.stream().map(candidate -> candidate.profile().id()).toList());
    }

    @Test
    void explanationsAndEvidenceComeOnlyFromTheProfileItself() {
        List<ScoredCandidate> ranked = rank(new ObjectiveFilters(List.of("AWS RDS"), 4, 7,
                List.of("Bangalore"), List.of("startup"), List.of(), List.of(), false, false));

        assertThat(ranked).allSatisfy(candidate -> {
            assertThat(candidate.explanation())
                    .contains(candidate.profile().currentCompany())
                    .contains(candidate.profile().location())
                    .contains(String.valueOf(candidate.profile().yearsExperience()));
            assertThat(candidate.evidence()).isNotEmpty();
            candidate.evidence().stream()
                    .filter(evidence -> "skills".equals(evidence.field()))
                    .forEach(evidence -> assertThat(candidate.profile().skills()).contains(evidence.value()));
        });
    }

    @Test
    void handlesASearchWithNoCriteriaAtAllWithoutDividingByZero() {
        List<ScoredCandidate> ranked = rank(new ObjectiveFilters(List.of(), null, null, List.of(),
                List.of(), List.of(), List.of(), false, false));

        assertThat(ranked).hasSize(repository.allProfiles().size());
        assertThat(ranked).allSatisfy(candidate -> assertThat(candidate.score()).isEqualTo(50));
    }
}
