package com.flexiple.sourcing.profiles;

import com.flexiple.sourcing.domain.CompanyType;
import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Profile;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Applies the objective half of a search to the talent pool, in Java, deterministically.
 * The model is never asked which candidates match - only what "matching" means.
 *
 * <p>Semantics: AND across dimensions, OR within a dimension.
 */
@Component
public class FilterEngine {

    private static final String REMOTE_LOCATION_MARKER = "remote";

    private final SkillNormalizer skillNormalizer;

    public FilterEngine(SkillNormalizer skillNormalizer) {
        this.skillNormalizer = skillNormalizer;
    }

    public List<Profile> applyObjectiveFiltersToTalentPool(ObjectiveFilters filters, List<Profile> talentPool) {
        return talentPool.stream()
                .filter(profile -> matchesExperienceRange(profile, filters))
                .filter(profile -> matchesAnyRequestedLocation(profile, filters))
                .filter(profile -> matchesAnyRequestedCurrentCompanyType(profile, filters))
                .filter(profile -> hasWorkedAtAnyRequestedPastCompanyType(profile, filters))
                .filter(profile -> isNotExcludedByCompanyBackground(profile, filters))
                .filter(profile -> matchesRequiredSkills(profile, filters))
                .toList();
    }

    /**
     * How many profiles each dimension would keep on its own. Drives the empty-results screen,
     * which tells the recruiter which single filter is doing the damage instead of just saying "0 results".
     */
    public List<DimensionMatchCount> countMatchesPerDimension(ObjectiveFilters filters, List<Profile> talentPool) {
        return java.util.stream.Stream.of(
                countDimension("experience", describeExperienceRange(filters), filters.minYearsExperience() != null
                        || filters.maxYearsExperience() != null, talentPool, profile -> matchesExperienceRange(profile, filters)),
                countDimension("locations", String.join(", ", filters.locations()), !filters.locations().isEmpty(),
                        talentPool, profile -> matchesAnyRequestedLocation(profile, filters)),
                countDimension("skills", String.join(", ", filters.skills()), !filters.skills().isEmpty(),
                        talentPool, profile -> matchesRequiredSkills(profile, filters)),
                countDimension("company_types", String.join(", ", filters.companyTypes()), !filters.companyTypes().isEmpty(),
                        talentPool, profile -> matchesAnyRequestedCurrentCompanyType(profile, filters)),
                countDimension("past_company_types", String.join(", ", filters.pastCompanyTypes()),
                        !filters.pastCompanyTypes().isEmpty(), talentPool,
                        profile -> hasWorkedAtAnyRequestedPastCompanyType(profile, filters)))
                .filter(Objects::nonNull)
                .toList();
    }

    private DimensionMatchCount countDimension(String dimension, String description, boolean isActive,
            List<Profile> talentPool, Predicate<Profile> predicate) {
        if (!isActive) {
            return null;
        }
        long matches = talentPool.stream().filter(predicate).count();
        return new DimensionMatchCount(dimension, description, (int) matches);
    }

    private String describeExperienceRange(ObjectiveFilters filters) {
        Integer minimum = filters.minYearsExperience();
        Integer maximum = filters.maxYearsExperience();
        if (minimum != null && maximum != null) {
            return minimum + "-" + maximum + " years";
        }
        if (minimum != null) {
            return minimum + "+ years";
        }
        return maximum == null ? "any" : "up to " + maximum + " years";
    }

    private boolean matchesExperienceRange(Profile profile, ObjectiveFilters filters) {
        Integer minimum = filters.minYearsExperience();
        Integer maximum = filters.maxYearsExperience();
        return (minimum == null || profile.yearsExperience() >= minimum)
                && (maximum == null || profile.yearsExperience() <= maximum);
    }

    private boolean matchesAnyRequestedLocation(Profile profile, ObjectiveFilters filters) {
        if (filters.locations().isEmpty()) {
            return true;
        }
        String profileLocation = normalizeLocation(profile.location());
        if (filters.remoteOk() && profileLocation.contains(REMOTE_LOCATION_MARKER)) {
            return true;
        }
        return filters.locations().stream()
                .map(this::normalizeLocation)
                .anyMatch(requested -> profileLocation.contains(requested) || requested.contains(profileLocation));
    }

    private String normalizeLocation(String rawLocation) {
        return rawLocation == null ? "" : rawLocation.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private boolean matchesAnyRequestedCurrentCompanyType(Profile profile, ObjectiveFilters filters) {
        if (filters.companyTypes().isEmpty()) {
            return true;
        }
        return containsCompanyType(filters.companyTypes(), profile.currentCompanyType());
    }

    private boolean hasWorkedAtAnyRequestedPastCompanyType(Profile profile, ObjectiveFilters filters) {
        if (filters.pastCompanyTypes().isEmpty()) {
            return true;
        }
        return profile.pastCompanyTypes().stream()
                .anyMatch(pastType -> containsCompanyType(filters.pastCompanyTypes(), pastType));
    }

    private boolean isNotExcludedByCompanyBackground(Profile profile, ObjectiveFilters filters) {
        if (filters.excludeCompanyTypes().isEmpty()) {
            return true;
        }
        return !containsCompanyType(filters.excludeCompanyTypes(), profile.currentCompanyType());
    }

    private boolean containsCompanyType(List<String> requestedTypes, String profileCompanyType) {
        return CompanyType.parseLenient(profileCompanyType)
                .map(profileType -> requestedTypes.stream()
                        .map(CompanyType::parseLenient)
                        .flatMap(java.util.Optional::stream)
                        .anyMatch(requested -> requested == profileType))
                .orElse(false);
    }

    private boolean matchesRequiredSkills(Profile profile, ObjectiveFilters filters) {
        if (filters.skills().isEmpty()) {
            return true;
        }
        return filters.skillsMatchAll()
                ? filters.skills().stream().allMatch(skill -> skillNormalizer.profileHasSkill(profile, skill))
                : filters.skills().stream().anyMatch(skill -> skillNormalizer.profileHasSkill(profile, skill));
    }

    /** How many profiles one filter dimension keeps on its own, used by the empty-results guidance. */
    public record DimensionMatchCount(String dimension, String description, int matchCount) {
    }
}
