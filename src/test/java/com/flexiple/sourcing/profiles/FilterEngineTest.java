package com.flexiple.sourcing.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Profile;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilterEngineTest {

    private final ProfileRepository repository = TalentPoolFixture.loadRealTalentPool();
    private final FilterEngine filterEngine = new FilterEngine(new SkillNormalizer());

    private List<String> idsMatching(ObjectiveFilters filters) {
        return filterEngine.applyObjectiveFiltersToTalentPool(filters, repository.allProfiles())
                .stream().map(Profile::id).toList();
    }

    @Test
    void appliesTheCanonicalSearchExactlyAsWritten() {
        ObjectiveFilters filters = new ObjectiveFilters(List.of("RDS"), 4, 7, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), false, false);

        assertThat(idsMatching(filters)).containsExactly("p01", "p02", "p03", "p04", "p05", "p06");
    }

    @Test
    void treatsAnEmptyFilterSetAsNoConstraintAtAll() {
        ObjectiveFilters everyone = new ObjectiveFilters(List.of(), null, null, List.of(),
                List.of(), List.of(), List.of(), false, false);

        assertThat(idsMatching(everyone)).hasSize(repository.allProfiles().size());
    }

    @Test
    void returnsNothingWhenTheCriteriaAreImpossible() {
        ObjectiveFilters impossible = new ObjectiveFilters(List.of("Swift"), 12, 13, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), true, false);

        assertThat(idsMatching(impossible)).isEmpty();
    }

    @Test
    void requiresEverySkillOnlyWhenAskedTo() {
        ObjectiveFilters anySkill = new ObjectiveFilters(List.of("Swift", "AWS RDS"), null, null, List.of(),
                List.of(), List.of(), List.of(), false, false);
        ObjectiveFilters everySkill = new ObjectiveFilters(List.of("Swift", "AWS RDS"), null, null, List.of(),
                List.of(), List.of(), List.of(), true, false);

        assertThat(idsMatching(anySkill)).isNotEmpty();
        assertThat(idsMatching(everySkill).size()).isLessThan(idsMatching(anySkill).size());
    }

    @Test
    void separatesCurrentCompanyBackgroundFromPastCompanyBackground() {
        ObjectiveFilters currentlyEnterprise = new ObjectiveFilters(List.of(), null, null, List.of(),
                List.of("enterprise"), List.of(), List.of(), false, false);
        ObjectiveFilters everWorkedAtEnterprise = new ObjectiveFilters(List.of(), null, null, List.of(),
                List.of(), List.of("enterprise"), List.of(), false, false);

        assertThat(idsMatching(currentlyEnterprise)).isNotEmpty();
        assertThat(idsMatching(everWorkedAtEnterprise)).isNotEmpty();
        assertThat(idsMatching(currentlyEnterprise)).isNotEqualTo(idsMatching(everWorkedAtEnterprise));
    }

    @Test
    void excludesBackgroundsTheRecruiterRuledOut() {
        ObjectiveFilters noAgencies = new ObjectiveFilters(List.of(), null, null, List.of(),
                List.of(), List.of(), List.of("agency"), false, false);

        List<Profile> matches = filterEngine.applyObjectiveFiltersToTalentPool(noAgencies, repository.allProfiles());
        assertThat(matches).isNotEmpty();
        assertThat(matches).noneMatch(profile -> "agency".equals(profile.currentCompanyType()));
    }

    @Test
    void letsRemoteCandidatesThroughAWhenRemoteIsAcceptable() {
        ObjectiveFilters bangaloreOnly = new ObjectiveFilters(List.of(), null, null, List.of("Bangalore"),
                List.of(), List.of(), List.of(), false, false);
        ObjectiveFilters bangaloreOrRemote = new ObjectiveFilters(List.of(), null, null, List.of("Bangalore"),
                List.of(), List.of(), List.of(), false, true);

        assertThat(idsMatching(bangaloreOrRemote).size()).isGreaterThan(idsMatching(bangaloreOnly).size());
    }

    @Test
    void explainsWhichSingleDimensionIsRulingEveryoneOut() {
        ObjectiveFilters impossible = new ObjectiveFilters(List.of("Swift"), 12, 13, List.of("Bangalore"),
                List.of("startup"), List.of(), List.of(), true, false);

        List<FilterEngine.DimensionMatchCount> diagnostics =
                filterEngine.countMatchesPerDimension(impossible, repository.allProfiles());

        assertThat(diagnostics).extracting(FilterEngine.DimensionMatchCount::dimension)
                .containsExactlyInAnyOrder("experience", "locations", "skills", "company_types");
        assertThat(diagnostics).allSatisfy(entry -> assertThat(entry.matchCount()).isLessThan(48));
    }
}
