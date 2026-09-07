package com.flexiple.sourcing.profiles;

import com.flexiple.sourcing.domain.CompanyType;
import com.flexiple.sourcing.domain.Evidence;
import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Profile;
import com.flexiple.sourcing.domain.ScoredCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Ranks candidates without asking a model anything.
 *
 * <p>This is what makes the manual path a real path rather than a detour: the recruiter can build
 * filters by hand, hit search, and get an ordered list instantly, with no API key, no latency and no
 * cost. Every point is traceable to a field on the profile, and the explanation is assembled from the
 * same facts, so it is grounded by construction.
 *
 * <p>The score is deliberately simple to state: <em>the share of the criteria you actually set that
 * this person satisfies</em>. Each active dimension counts once, skills count partially when only
 * some of them are present, and a location that passed only because remote was allowed counts less
 * than living in the city. That means candidates who meet everything you asked for genuinely all
 * score 100 — the honest answer when your filters cannot tell them apart. Separating those is what
 * the fit rubric and the AI pass are for, and the UI labels which one produced the ranking.
 */
@Component
public class LocalRelevanceScorer {

    private static final int SCORE_WHEN_NO_CRITERIA_WERE_SET = 50;
    private static final double CREDIT_FOR_A_LOOSE_LOCATION_MATCH = 0.6;
    private static final int MAX_EVIDENCE_ITEMS_SHOWN = 4;

    private final SkillNormalizer skillNormalizer;

    public LocalRelevanceScorer(SkillNormalizer skillNormalizer) {
        this.skillNormalizer = skillNormalizer;
    }

    public List<ScoredCandidate> rankByHowWellTheyMatchTheFilters(List<Profile> filteredProfiles,
            ObjectiveFilters filters) {
        List<ScoredCandidate> scored = filteredProfiles.stream()
                .map(profile -> scoreOneProfile(profile, filters))
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
                        // Ties are real when the filters cannot separate people; break them by
                        // experience, then by id, so the order is stable across identical searches.
                        .thenComparing(Comparator.comparingInt(
                                (ScoredCandidate candidate) -> candidate.profile().yearsExperience()).reversed())
                        .thenComparing(candidate -> candidate.profile().id()))
                .toList();

        List<ScoredCandidate> ranked = new ArrayList<>(scored.size());
        for (int index = 0; index < scored.size(); index++) {
            ranked.add(scored.get(index).withRank(index + 1));
        }
        return List.copyOf(ranked);
    }

    private ScoredCandidate scoreOneProfile(Profile profile, ObjectiveFilters filters) {
        List<Double> creditPerActiveCriterion = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();

        List<String> matchedSkills = skillNormalizer.matchedSkillNames(profile, filters.skills());
        if (!filters.skills().isEmpty()) {
            creditPerActiveCriterion.add(
                    (double) countRequiredSkillsCovered(profile, filters) / filters.skills().size());
            matchedSkills.forEach(skill -> evidence.add(new Evidence("skills", skill)));
        }

        if (filters.minYearsExperience() != null || filters.maxYearsExperience() != null) {
            creditPerActiveCriterion.add(sitsInsideTheRequestedExperienceRange(profile, filters) ? 1.0 : 0.0);
            evidence.add(new Evidence("years_experience", String.valueOf(profile.yearsExperience())));
        }

        if (!filters.locations().isEmpty()) {
            boolean livesThere = matchesARequestedLocationExactly(profile, filters);
            creditPerActiveCriterion.add(livesThere ? 1.0 : CREDIT_FOR_A_LOOSE_LOCATION_MATCH);
            evidence.add(new Evidence("location", profile.location()));
        }

        if (!filters.companyTypes().isEmpty()) {
            boolean rightBackground = isOneOf(filters.companyTypes(), profile.currentCompanyType());
            creditPerActiveCriterion.add(rightBackground ? 1.0 : 0.0);
            if (rightBackground) {
                evidence.add(new Evidence("current_company_type", profile.currentCompanyType()));
            }
        }

        if (!filters.pastCompanyTypes().isEmpty()) {
            creditPerActiveCriterion.add(profile.pastCompanyTypes().stream()
                    .anyMatch(type -> isOneOf(filters.pastCompanyTypes(), type)) ? 1.0 : 0.0);
        }

        return new ScoredCandidate(0, profile, averageAsPercentage(creditPerActiveCriterion),
                evidence.stream().limit(MAX_EVIDENCE_ITEMS_SHOWN).toList(),
                describeWhyThisProfilePassed(profile, matchedSkills));
    }

    private int averageAsPercentage(List<Double> creditPerActiveCriterion) {
        if (creditPerActiveCriterion.isEmpty()) {
            return SCORE_WHEN_NO_CRITERIA_WERE_SET;
        }
        double average = creditPerActiveCriterion.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return (int) Math.round(average * 100);
    }

    private long countRequiredSkillsCovered(Profile profile, ObjectiveFilters filters) {
        return filters.skills().stream().filter(skill -> skillNormalizer.profileHasSkill(profile, skill)).count();
    }

    private boolean sitsInsideTheRequestedExperienceRange(Profile profile, ObjectiveFilters filters) {
        Integer minimum = filters.minYearsExperience();
        Integer maximum = filters.maxYearsExperience();
        return (minimum == null || profile.yearsExperience() >= minimum)
                && (maximum == null || profile.yearsExperience() <= maximum);
    }

    private boolean matchesARequestedLocationExactly(Profile profile, ObjectiveFilters filters) {
        return filters.locations().stream().anyMatch(requested -> requested.equalsIgnoreCase(profile.location()));
    }

    private boolean isOneOf(List<String> requestedTypes, String profileCompanyType) {
        return CompanyType.parseLenient(profileCompanyType)
                .map(type -> requestedTypes.stream()
                        .map(CompanyType::parseLenient)
                        .flatMap(java.util.Optional::stream)
                        .anyMatch(requested -> requested == type))
                .orElse(false);
    }

    private String describeWhyThisProfilePassed(Profile profile, List<String> matchedSkills) {
        String skillPhrase = matchedSkills.isEmpty()
                ? ""
                : ", matching on " + String.join(", ", matchedSkills.stream().limit(3).toList());
        return "%d years of experience, currently %s at %s (%s) in %s%s."
                .formatted(profile.yearsExperience(), profile.currentTitle(), profile.currentCompany(),
                        profile.currentCompanyType(), profile.location(), skillPhrase);
    }
}
