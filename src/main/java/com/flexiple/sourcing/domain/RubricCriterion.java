package com.flexiple.sourcing.domain;

/** One line of "what good looks like" for this role. Weights across a rubric sum to 1.0. */
public record RubricCriterion(String name, String description, double weight) {
}
