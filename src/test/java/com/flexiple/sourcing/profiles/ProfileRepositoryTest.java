package com.flexiple.sourcing.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.flexiple.sourcing.domain.Profile;
import org.junit.jupiter.api.Test;

class ProfileRepositoryTest {

    private final ProfileRepository repository = TalentPoolFixture.loadRealTalentPool();

    @Test
    void loadsTheWholeTalentPoolFromDisk() {
        assertThat(repository.allProfiles()).hasSize(48);
        assertThat(repository.distinctLocations()).contains("Bangalore");
        assertThat(repository.distinctCompanyNames()).isNotEmpty();
    }

    @Test
    void mapsEverySnakeCaseFieldOntoTheProfileRecord() {
        Profile first = repository.findProfileById("p01").orElseThrow();

        assertThat(first.name()).isEqualTo("Ananya Rao");
        assertThat(first.currentTitle()).isNotBlank();
        assertThat(first.yearsExperience()).isPositive();
        assertThat(first.currentCompanyType()).isEqualTo("startup");
        assertThat(first.skills()).contains("AWS RDS");
        assertThat(first.pastCompanies()).isNotEmpty();
        assertThat(first.pastCompanies().getFirst().companyType()).isNotBlank();
    }

    @Test
    void reportsUnknownProfileIdsAsMissing() {
        assertThat(repository.containsProfileId("p01")).isTrue();
        assertThat(repository.containsProfileId("not-a-real-id")).isFalse();
    }
}
