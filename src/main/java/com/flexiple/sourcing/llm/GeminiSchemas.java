package com.flexiple.sourcing.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response schemas handed to Gemini's structured-output mode, so the model is constrained at
 * generation time and the Java side only has to validate meaning, not shape. Kept to the flat
 * OpenAPI subset Gemini accepts: no {@code additionalProperties}, no {@code oneOf}.
 */
public final class GeminiSchemas {

    private GeminiSchemas() {
    }

    public static Map<String, Object> searchDefinitionSchema() {
        return objectSchema(
                Map.of("filters", objectiveFiltersSchema(), "rubric", rubricSchema()),
                List.of("filters", "rubric"));
    }

    public static Map<String, Object> candidateScoresSchema() {
        Map<String, Object> evidenceItem = objectSchema(
                Map.of(
                        "field", stringSchema("One of: years_experience, location, skills, current_company, "
                                + "current_company_type, current_title, past_company, education"),
                        "value", stringSchema("The exact value copied from the profile, verbatim")),
                List.of("field", "value"));

        Map<String, Object> candidate = objectSchema(
                Map.of(
                        "profile_id", stringSchema("The candidate id exactly as given, e.g. p07"),
                        "score", integerSchema("Fit against the rubric, 0-100"),
                        "evidence", arraySchema(evidenceItem, "2-4 facts copied verbatim from this profile"),
                        "summary", stringSchema("One sentence, max 30 words, citing only facts from this profile")),
                List.of("profile_id", "score", "evidence", "summary"));

        return objectSchema(Map.of("candidates", arraySchema(candidate, "One entry per supplied candidate")),
                List.of("candidates"));
    }

    public static Map<String, Object> refinementSchema() {
        Map<String, Object> change = objectSchema(
                Map.of(
                        "type", enumSchema("What was changed", List.of("filter", "rubric")),
                        "field", stringSchema("The filter field or rubric criterion name that changed"),
                        "before", stringSchema("Previous value, human readable"),
                        "after", stringSchema("New value, human readable"),
                        "reason", stringSchema("Why, in the recruiter's own terms")),
                List.of("type", "field", "before", "after", "reason"));

        return objectSchema(
                Map.of(
                        "filters", objectiveFiltersSchema(),
                        "rubric", rubricSchema(),
                        "changes", arraySchema(change, "Every difference from the previous search definition"),
                        "reply", stringSchema("One sentence acknowledging the feedback, addressed to the recruiter")),
                List.of("filters", "rubric", "changes", "reply"));
    }

    private static Map<String, Object> objectiveFiltersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("skills", arraySchema(stringSchema("A concrete, checkable skill name"), "Required skills"));
        properties.put("min_years_experience",
                nullableIntegerSchema("Minimum total years of experience, or null when the recruiter gave no lower bound"));
        properties.put("max_years_experience",
                nullableIntegerSchema("Maximum total years of experience, or null when the recruiter gave no upper bound"));
        properties.put("locations", arraySchema(stringSchema("A city or region name"), "Acceptable locations"));
        properties.put("company_types", companyTypeArraySchema("Acceptable current company backgrounds"));
        properties.put("past_company_types", companyTypeArraySchema("Company backgrounds required somewhere in their history"));
        properties.put("exclude_company_types", companyTypeArraySchema("Current company backgrounds to rule out"));
        properties.put("skills_match_all", booleanSchema("True only if every listed skill is mandatory"));
        properties.put("remote_ok", booleanSchema("True if remote candidates are acceptable for a located role"));
        // The year bounds are required-but-nullable on purpose: left merely optional, the model
        // quietly drops them and a stated "4-7 years" silently stops being part of the search.
        return objectSchema(properties, List.of("skills", "locations", "company_types", "past_company_types",
                "min_years_experience", "max_years_experience", "skills_match_all", "remote_ok"));
    }

    private static Map<String, Object> rubricSchema() {
        Map<String, Object> criterion = objectSchema(
                Map.of(
                        "name", stringSchema("Short label, 2-5 words"),
                        "description", stringSchema("What an excellent candidate looks like on this dimension"),
                        "weight", numberSchema("Relative importance between 0 and 1; weights should sum to about 1")),
                List.of("name", "description", "weight"));
        return objectSchema(
                Map.of(
                        "summary", stringSchema("One or two sentences describing the ideal candidate"),
                        "criteria", arraySchema(criterion, "Between 3 and 6 criteria")),
                List.of("summary", "criteria"));
    }

    private static Map<String, Object> companyTypeArraySchema(String description) {
        return arraySchema(enumSchema("Company background",
                List.of("startup", "scaleup", "enterprise", "agency")), description);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "ARRAY");
        schema.put("description", description);
        schema.put("items", items);
        return schema;
    }

    private static Map<String, Object> stringSchema(String description) {
        return scalarSchema("STRING", description);
    }

    private static Map<String, Object> integerSchema(String description) {
        return scalarSchema("INTEGER", description);
    }

    private static Map<String, Object> nullableIntegerSchema(String description) {
        Map<String, Object> schema = scalarSchema("INTEGER", description);
        schema.put("nullable", true);
        return schema;
    }

    private static Map<String, Object> numberSchema(String description) {
        return scalarSchema("NUMBER", description);
    }

    private static Map<String, Object> booleanSchema(String description) {
        return scalarSchema("BOOLEAN", description);
    }

    private static Map<String, Object> enumSchema(String description, List<String> allowedValues) {
        Map<String, Object> schema = scalarSchema("STRING", description);
        schema.put("enum", allowedValues);
        return schema;
    }

    private static Map<String, Object> scalarSchema(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        schema.put("description", description);
        return schema;
    }
}
