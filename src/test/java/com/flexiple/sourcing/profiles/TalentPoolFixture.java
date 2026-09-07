package com.flexiple.sourcing.profiles;

import com.flexiple.sourcing.config.AppProperties;
import com.flexiple.sourcing.TestJson;

/** Loads the real profiles.json for tests, with the same snake_case mapping the app uses. */
public final class TalentPoolFixture {

    private TalentPoolFixture() {
    }

    public static ProfileRepository loadRealTalentPool() {
        ProfileRepository repository = new ProfileRepository(
                new AppProperties("./profiles.json"),
                TestJson.mapperMatchingProduction());
        repository.loadTalentPoolFromDisk();
        return repository;
    }
}
