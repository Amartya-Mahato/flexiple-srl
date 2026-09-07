package com.flexiple.sourcing.domain;

import java.util.List;

/** The subjective half of a search: what an excellent candidate looks like, in the recruiter's words. */
public record Rubric(String summary, List<RubricCriterion> criteria) {

    public Rubric {
        criteria = criteria == null ? List.of() : criteria.stream().filter(java.util.Objects::nonNull).toList();
    }
}
