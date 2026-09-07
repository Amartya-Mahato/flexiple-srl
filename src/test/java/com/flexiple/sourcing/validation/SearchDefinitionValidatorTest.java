package com.flexiple.sourcing.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.RubricCriterion;
import com.flexiple.sourcing.llm.LlmException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchDefinitionValidatorTest {

    private final SearchDefinitionValidator validator = new SearchDefinitionValidator();

    private ObjectiveFilters filtersWithYears(Integer minimum, Integer maximum) {
        return new ObjectiveFilters(List.of(), minimum, maximum, List.of(), List.of(), List.of(), List.of(), false, false);
    }

    @Test
    void rejectsAnInvertedExperienceRange() {
        assertThatThrownBy(() -> validator.validateAndNormalizeFilters(filtersWithYears(9, 4)))
                .isInstanceOf(LlmException.class)
                .satisfies(failure -> assertThat(((LlmException) failure).code()).isEqualTo("INVALID_LLM_RESPONSE"));
    }

    @Test
    void clampsNonsensicalYearValuesInsteadOfFailing() {
        ObjectiveFilters clamped = validator.validateAndNormalizeFilters(filtersWithYears(-4, 900));

        assertThat(clamped.minYearsExperience()).isZero();
        assertThat(clamped.maxYearsExperience()).isEqualTo(60);
    }

    @Test
    void dropsCompanyTypesThatDoNotExistInTheDataset() {
        ObjectiveFilters raw = new ObjectiveFilters(List.of(), null, null, List.of(),
                List.of("startup", "unicorn", "STARTUP", "Scale-Up"), List.of(), List.of(), false, false);

        assertThat(validator.validateAndNormalizeFilters(raw).companyTypes())
                .containsExactly("startup", "scaleup");
    }

    @Test
    void stripsBlanksAndDuplicatesFromTextFilters() {
        ObjectiveFilters raw = new ObjectiveFilters(Arrays.asList("AWS RDS", "  ", "AWS RDS", null, " Redis "),
                null, null, List.of(), List.of(), List.of(), List.of(), false, false);

        assertThat(validator.validateAndNormalizeFilters(raw).skills()).containsExactly("AWS RDS", "Redis");
    }

    @Test
    void refusesAnEmptyRubricBecauseThereWouldBeNothingToScoreAgainst() {
        assertThatThrownBy(() -> validator.validateAndNormalizeRubric(new Rubric("", List.of())))
                .isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> validator.validateAndNormalizeRubric(null))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void rescalesRubricWeightsToSumToOne() {
        Rubric lopsided = new Rubric("Ideal person", List.of(
                new RubricCriterion("Database depth", "Owns production databases", 6),
                new RubricCriterion("Startup experience", "Has shipped in small teams", 2),
                new RubricCriterion("Ownership", "Runs things end to end", 2)));

        Rubric normalized = validator.validateAndNormalizeRubric(lopsided);

        assertThat(normalized.criteria()).hasSize(3);
        assertThat(normalized.criteria().stream().mapToDouble(RubricCriterion::weight).sum()).isCloseTo(1.0, within());
        assertThat(normalized.criteria().getFirst().weight()).isEqualTo(0.6);
    }

    @Test
    void sharesWeightEquallyWhenTheModelReturnsNoneAtAll() {
        Rubric weightless = new Rubric("Ideal person", List.of(
                new RubricCriterion("A", "first", 0),
                new RubricCriterion("B", "second", 0)));

        assertThat(validator.validateAndNormalizeRubric(weightless).criteria())
                .allSatisfy(criterion -> assertThat(criterion.weight()).isEqualTo(0.5));
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.02);
    }
}
