package com.flexiple.sourcing.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Rubric;
import org.junit.jupiter.api.Test;
import com.flexiple.sourcing.TestJson;

/** Bad model output must become a clean error, never a stack trace or a half-applied object. */
class StructuredResponseReaderTest {

    private final StructuredResponseReader reader = new StructuredResponseReader(TestJson.mapperMatchingProduction());

    private record SearchDefinition(ObjectiveFilters filters, Rubric rubric) {
    }

    @Test
    void readsWellFormedSnakeCaseOutput() {
        String modelJson = """
                {"filters": {"skills": ["AWS RDS"], "min_years_experience": 4, "max_years_experience": 7,
                 "locations": ["Bangalore"], "company_types": ["startup"], "skills_match_all": false},
                 "rubric": {"summary": "Owns databases", "criteria": [
                   {"name": "Database depth", "description": "Production ownership", "weight": 1.0}]}}
                """;

        SearchDefinition parsed = reader.readOrReject(modelJson, SearchDefinition.class, "test");

        assertThat(parsed.filters().skills()).containsExactly("AWS RDS");
        assertThat(parsed.filters().minYearsExperience()).isEqualTo(4);
        assertThat(parsed.rubric().criteria()).hasSize(1);
    }

    @Test
    void turnsTruncatedJsonIntoARetryableInvalidResponse() {
        String truncated = "{\"filters\": {\"skills\": [\"AWS RDS\"  \"min_years_experience\": ,,, truncated";

        assertThatThrownBy(() -> reader.readOrReject(truncated, SearchDefinition.class, "test"))
                .isInstanceOf(LlmException.class)
                .satisfies(failure -> {
                    assertThat(((LlmException) failure).code()).isEqualTo("INVALID_LLM_RESPONSE");
                    assertThat(((LlmException) failure).isRetryable()).isTrue();
                });
    }

    @Test
    void rejectsProseWhereJsonWasRequired() {
        assertThatThrownBy(() -> reader.readOrReject("Sure! Here are the filters you asked for.",
                SearchDefinition.class, "test"))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void rejectsNullAndEmptyOutput() {
        assertThatThrownBy(() -> reader.readOrReject("null", SearchDefinition.class, "test"))
                .isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> reader.readOrReject("", SearchDefinition.class, "test"))
                .isInstanceOf(LlmException.class);
    }
}
