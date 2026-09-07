package com.flexiple.sourcing.domain;

import java.util.List;

/**
 * The objective, machine-checkable half of a search. Everything here is applied
 * deterministically in Java against the talent pool - the LLM never picks candidates.
 */
public record ObjectiveFilters(
        List<String> skills,
        Integer minYearsExperience,
        Integer maxYearsExperience,
        List<String> locations,
        List<String> companyTypes,
        List<String> pastCompanyTypes,
        List<String> excludeCompanyTypes,
        boolean skillsMatchAll,
        boolean remoteOk) {

    public ObjectiveFilters {
        skills = copyOrEmpty(skills);
        locations = copyOrEmpty(locations);
        companyTypes = copyOrEmpty(companyTypes);
        pastCompanyTypes = copyOrEmpty(pastCompanyTypes);
        excludeCompanyTypes = copyOrEmpty(excludeCompanyTypes);
    }

    /** Null-tolerant: a model that emits {@code ["AWS RDS", null]} must not blow up the request. */
    private static List<String> copyOrEmpty(List<String> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).toList();
    }

    public boolean hasNoConstraintsAtAll() {
        return skills.isEmpty() && locations.isEmpty() && companyTypes.isEmpty()
                && pastCompanyTypes.isEmpty() && excludeCompanyTypes.isEmpty()
                && minYearsExperience == null && maxYearsExperience == null;
    }
}
