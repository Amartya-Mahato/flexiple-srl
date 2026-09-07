package com.flexiple.sourcing.domain;

/**
 * A single fact the model cited to justify a score, e.g. {@code field="skills", value="AWS RDS"}.
 * Only evidence that has been verified against the real profile ever reaches the browser.
 */
public record Evidence(String field, String value) {
}
