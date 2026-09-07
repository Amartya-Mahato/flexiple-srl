package com.flexiple.sourcing.domain;

/**
 * One explained difference between the search before and after a refinement,
 * e.g. {@code min_years_experience: 3 -> 4} because "candidate 1 was too junior".
 */
public record SearchChange(String type, String field, String before, String after, String reason) {
}
