package com.flexiple.sourcing.profiles;

import com.flexiple.sourcing.domain.Profile;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Lightweight skill matching so "RDS", "AWS RDS" and "Amazon RDS" are not three unrelated strings.
 * Deliberately not semantic search: a normalisation pass, a short alias table, and a containment
 * rule that is too short-sighted to confuse "Go" with "MongoDB".
 */
@Component
public class SkillNormalizer {

    /** Below this length, containment matching is off - it is how "Go" avoids matching "MongoDB". */
    private static final int MINIMUM_LENGTH_FOR_CONTAINMENT_MATCH = 3;

    private static final Map<String, String> SKILL_ALIASES = Map.ofEntries(
            Map.entry("rds", "awsrds"),
            Map.entry("amazonrds", "awsrds"),
            Map.entry("postgres", "postgresql"),
            Map.entry("pgsql", "postgresql"),
            Map.entry("psql", "postgresql"),
            Map.entry("node", "nodejs"),
            Map.entry("k8s", "kubernetes"),
            Map.entry("js", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("golang", "go"),
            Map.entry("ml", "machinelearning"),
            Map.entry("dl", "deeplearning"),
            Map.entry("gcp", "googlecloud"),
            Map.entry("amazonwebservices", "aws"),
            Map.entry("reactjs", "react"),
            Map.entry("llm", "llms"),
            Map.entry("largelanguagemodels", "llms"),
            Map.entry("vectordatabases", "vectordbs"),
            Map.entry("vectordb", "vectordbs"));

    public String toComparableForm(String rawSkill) {
        if (rawSkill == null) {
            return "";
        }
        String stripped = rawSkill.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SKILL_ALIASES.getOrDefault(stripped, stripped);
    }

    public boolean skillNamesReferToTheSameThing(String requiredSkill, String profileSkill) {
        String required = toComparableForm(requiredSkill);
        String owned = toComparableForm(profileSkill);
        if (required.isEmpty() || owned.isEmpty()) {
            return false;
        }
        if (required.equals(owned)) {
            return true;
        }
        int shorterLength = Math.min(required.length(), owned.length());
        return shorterLength >= MINIMUM_LENGTH_FOR_CONTAINMENT_MATCH
                && (required.contains(owned) || owned.contains(required));
    }

    public boolean profileHasSkill(Profile profile, String requiredSkill) {
        return profile.skills().stream().anyMatch(skill -> skillNamesReferToTheSameThing(requiredSkill, skill));
    }

    /** The profile's own spelling of the skills that satisfied the filter, for highlighting in the UI. */
    public java.util.List<String> matchedSkillNames(Profile profile, java.util.List<String> requiredSkills) {
        return profile.skills().stream()
                .filter(skill -> requiredSkills.stream().anyMatch(required -> skillNamesReferToTheSameThing(required, skill)))
                .toList();
    }
}
