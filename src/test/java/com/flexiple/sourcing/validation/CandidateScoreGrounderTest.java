package com.flexiple.sourcing.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flexiple.sourcing.domain.Evidence;
import com.flexiple.sourcing.domain.Profile;
import com.flexiple.sourcing.domain.ScoredCandidate;
import com.flexiple.sourcing.llm.LlmException;
import com.flexiple.sourcing.profiles.ProfileRepository;
import com.flexiple.sourcing.profiles.SkillNormalizer;
import com.flexiple.sourcing.profiles.TalentPoolFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The trust layer: what the model claims about a candidate only survives if the data agrees. */
class CandidateScoreGrounderTest {

    private final ProfileRepository repository = TalentPoolFixture.loadRealTalentPool();
    private final CandidateScoreGrounder grounder = new CandidateScoreGrounder(new SkillNormalizer(), repository);

    private final Profile ananya = repository.findProfileById("p01").orElseThrow();
    private final Profile second = repository.findProfileById("p02").orElseThrow();
    /** p05 works somewhere neither p01 nor p02 ever did, which is what makes it a clean hallucination probe. */
    private final Profile unrelated = repository.findProfileById("p05").orElseThrow();

    private CandidateScoreGrounder.RawCandidateScore score(String profileId, int score, List<Evidence> evidence,
            String summary) {
        return new CandidateScoreGrounder.RawCandidateScore(profileId, score, evidence, summary);
    }

    @Test
    void keepsEvidenceThatMatchesTheProfileAndDropsEvidenceThatDoesNot() {
        List<ScoredCandidate> results = grounder.groundAndRankScores(List.of(
                score("p01", 90, List.of(
                        new Evidence("skills", "AWS RDS"),
                        new Evidence("location", "Bangalore"),
                        new Evidence("years_experience", "17"),
                        new Evidence("current_company", "Definitely Not Their Employer")),
                        "Lists AWS RDS and works in Bangalore.")),
                List.of(ananya));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().evidence()).extracting(Evidence::value)
                .containsExactly("AWS RDS", "Bangalore");
    }

    @Test
    void refusesCandidateIdsThatWereNeverInTheFilteredSet() {
        assertThatThrownBy(() -> grounder.groundAndRankScores(
                List.of(score("p99", 90, List.of(new Evidence("skills", "AWS RDS")), "Invented person.")),
                List.of(ananya)))
                .isInstanceOf(LlmException.class)
                .satisfies(failure -> assertThat(((LlmException) failure).code()).isEqualTo("INVALID_LLM_RESPONSE"));
    }

    @Test
    void discardsScoresOutsideTheAllowedRange() {
        List<ScoredCandidate> results = grounder.groundAndRankScores(List.of(
                score("p01", 150, List.of(new Evidence("skills", "AWS RDS")), "Out of range."),
                score("p02", 70, List.of(new Evidence("location", "Bangalore")), "In range.")),
                List.of(ananya, second));

        assertThat(results).extracting(candidate -> candidate.profile().id()).containsExactly("p02", "p01");
        assertThat(results.getLast().score()).isZero();
    }

    @Test
    void replacesASummaryThatNamesACompanyTheCandidateNeverWorkedAt() {
        String hallucinated = "Built payments infrastructure at " + unrelated.currentCompany() + " for six years.";

        List<ScoredCandidate> results = grounder.groundAndRankScores(
                List.of(score("p01", 88, List.of(new Evidence("skills", "AWS RDS")), hallucinated)),
                List.of(ananya));

        assertThat(results.getFirst().explanation()).doesNotContain(unrelated.currentCompany());
        assertThat(results.getFirst().explanation()).contains(ananya.currentCompany());
    }

    @Test
    void fallsBackToProfileFactsWhenNoEvidenceSurvives() {
        List<ScoredCandidate> results = grounder.groundAndRankScores(
                List.of(score("p01", 80, List.of(new Evidence("skills", "COBOL")), "Excellent all-round engineer.")),
                List.of(ananya));

        assertThat(results.getFirst().evidence()).isEmpty();
        assertThat(results.getFirst().explanation())
                .contains(String.valueOf(ananya.yearsExperience()))
                .contains(ananya.currentTitle())
                .contains(ananya.currentCompany());
    }

    @Test
    void neverLosesAProfileThatPassedTheObjectiveFilters() {
        List<ScoredCandidate> results = grounder.groundAndRankScores(
                List.of(score("p01", 91, List.of(new Evidence("skills", "AWS RDS")), "Strong RDS background.")),
                List.of(ananya, second));

        assertThat(results).extracting(candidate -> candidate.profile().id()).containsExactly("p01", "p02");
        assertThat(results.getLast().explanation()).contains(second.currentCompany());
    }

    @Test
    void ranksDeterministicallyByScoreThenById() {
        List<ScoredCandidate> results = grounder.groundAndRankScores(List.of(
                score("p02", 70, List.of(new Evidence("location", "Bangalore")), "Second."),
                score("p01", 70, List.of(new Evidence("location", "Bangalore")), "First.")),
                List.of(ananya, second));

        assertThat(results).extracting(ScoredCandidate::rank).containsExactly(1, 2);
        assertThat(results).extracting(candidate -> candidate.profile().id()).containsExactly("p01", "p02");
    }

    @Test
    void ignoresDuplicateEntriesForTheSameCandidate() {
        List<ScoredCandidate> results = grounder.groundAndRankScores(List.of(
                score("p01", 90, List.of(new Evidence("skills", "AWS RDS")), "First answer."),
                score("p01", 20, List.of(new Evidence("skills", "AWS RDS")), "Contradictory second answer.")),
                List.of(ananya));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isEqualTo(90);
    }
}
