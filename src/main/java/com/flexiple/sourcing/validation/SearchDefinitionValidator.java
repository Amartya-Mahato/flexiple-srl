package com.flexiple.sourcing.validation;

import com.flexiple.sourcing.domain.CompanyType;
import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.RubricCriterion;
import com.flexiple.sourcing.llm.LlmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Turns whatever the model produced into a search definition that is safe to apply, or refuses it.
 *
 * <p>This is the guard that stops one vague piece of feedback from destroying a search: values are
 * range-checked, unknown company types are dropped, rubric weights are re-normalised, and anything
 * structurally hopeless raises {@code INVALID_LLM_RESPONSE} so the caller can keep the previous state.
 */
@Component
public class SearchDefinitionValidator {

    private static final int MAX_PLAUSIBLE_YEARS_EXPERIENCE = 60;
    private static final int MAX_VALUES_PER_FILTER_DIMENSION = 15;
    private static final int MAX_RUBRIC_CRITERIA = 8;
    private static final int MAX_TEXT_LENGTH = 400;

    public ObjectiveFilters validateAndNormalizeFilters(ObjectiveFilters rawFilters) {
        if (rawFilters == null) {
            throw LlmException.invalidResponse("no objective filters were returned");
        }

        Integer minimumYears = clampYearsExperience(rawFilters.minYearsExperience());
        Integer maximumYears = clampYearsExperience(rawFilters.maxYearsExperience());
        if (minimumYears != null && maximumYears != null && minimumYears > maximumYears) {
            throw LlmException.invalidResponse("the experience range was inverted (" + minimumYears + "-" + maximumYears + ")");
        }

        return new ObjectiveFilters(
                cleanTextValues(rawFilters.skills()),
                minimumYears,
                maximumYears,
                cleanTextValues(rawFilters.locations()),
                keepOnlyKnownCompanyTypes(rawFilters.companyTypes()),
                keepOnlyKnownCompanyTypes(rawFilters.pastCompanyTypes()),
                keepOnlyKnownCompanyTypes(rawFilters.excludeCompanyTypes()),
                rawFilters.skillsMatchAll(),
                rawFilters.remoteOk());
    }

    public Rubric validateAndNormalizeRubric(Rubric rawRubric) {
        if (rawRubric == null || rawRubric.criteria().isEmpty()) {
            throw LlmException.invalidResponse("the fit rubric was empty");
        }

        List<RubricCriterion> usableCriteria = rawRubric.criteria().stream()
                .filter(Objects::nonNull)
                .filter(criterion -> criterion.name() != null && !criterion.name().isBlank())
                .limit(MAX_RUBRIC_CRITERIA)
                .map(criterion -> new RubricCriterion(
                        truncate(criterion.name()),
                        truncate(criterion.description() == null ? "" : criterion.description()),
                        Math.max(criterion.weight(), 0)))
                .toList();

        if (usableCriteria.isEmpty()) {
            throw LlmException.invalidResponse("the fit rubric had no usable criteria");
        }
        return new Rubric(truncate(rawRubric.summary() == null ? "" : rawRubric.summary()),
                normalizeWeightsToSumToOne(usableCriteria));
    }

    /** Weights are advisory, so rather than rejecting odd sums we rescale them to a clean 1.0. */
    private List<RubricCriterion> normalizeWeightsToSumToOne(List<RubricCriterion> criteria) {
        double totalWeight = criteria.stream().mapToDouble(RubricCriterion::weight).sum();
        if (totalWeight <= 0) {
            double equalShare = roundToTwoDecimals(1.0 / criteria.size());
            return criteria.stream()
                    .map(criterion -> new RubricCriterion(criterion.name(), criterion.description(), equalShare))
                    .toList();
        }
        return criteria.stream()
                .map(criterion -> new RubricCriterion(criterion.name(), criterion.description(),
                        roundToTwoDecimals(criterion.weight() / totalWeight)))
                .toList();
    }

    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Integer clampYearsExperience(Integer years) {
        if (years == null) {
            return null;
        }
        if (years < 0) {
            return 0;
        }
        return Math.min(years, MAX_PLAUSIBLE_YEARS_EXPERIENCE);
    }

    private List<String> keepOnlyKnownCompanyTypes(List<String> rawCompanyTypes) {
        return rawCompanyTypes.stream()
                .map(CompanyType::parseLenient)
                .flatMap(java.util.Optional::stream)
                .distinct()
                .map(CompanyType::wireValue)
                .toList();
    }

    private List<String> cleanTextValues(List<String> rawValues) {
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String rawValue : rawValues) {
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }
            cleaned.add(truncate(rawValue.trim()));
            if (cleaned.size() == MAX_VALUES_PER_FILTER_DIMENSION) {
                break;
            }
        }
        return List.copyOf(cleaned);
    }

    private String truncate(String value) {
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH);
    }
}
