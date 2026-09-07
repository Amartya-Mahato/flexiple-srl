package com.flexiple.sourcing.domain;

import java.util.List;

/**
 * One candidate in the talent pool, mapped straight from {@code profiles.json}.
 * Field names are camelCase here and snake_case on the wire; the global Jackson
 * naming strategy (see application.yml) bridges the two.
 */
public record Profile(
        String id,
        String name,
        String currentTitle,
        int yearsExperience,
        String location,
        String currentCompany,
        String currentCompanyType,
        List<String> skills,
        List<PastCompany> pastCompanies,
        String education,
        String summary) {

    public Profile {
        skills = skills == null ? List.of() : List.copyOf(skills);
        pastCompanies = pastCompanies == null ? List.of() : List.copyOf(pastCompanies);
    }

    /** Every company this person has worked at, current first. Used to catch invented employers. */
    public List<String> allCompanyNames() {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.ofNullable(currentCompany),
                        pastCompanies.stream().map(PastCompany::company))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public List<String> pastCompanyTypes() {
        return pastCompanies.stream().map(PastCompany::companyType).filter(java.util.Objects::nonNull).toList();
    }
}
