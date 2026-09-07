package com.flexiple.sourcing.llm.ops;

import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.llm.GeminiClient;
import com.flexiple.sourcing.llm.GeminiSchemas;
import com.flexiple.sourcing.llm.PromptLoader;
import com.flexiple.sourcing.llm.StructuredResponseReader;
import com.flexiple.sourcing.profiles.ProfileRepository;
import com.flexiple.sourcing.validation.SearchDefinitionValidator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM operation 1: free-text requirement to objective filters plus a subjective fit rubric.
 * The prompt is shown the vocabulary that actually exists in the dataset so the filters it writes
 * are checkable rather than aspirational.
 */
@Service
public class SearchDefinitionParser {

    private static final Logger log = LoggerFactory.getLogger(SearchDefinitionParser.class);
    private static final String PROMPT_FILE = "parse-search.txt";

    private final GeminiClient geminiClient;
    private final PromptLoader promptLoader;
    private final StructuredResponseReader responseReader;
    private final SearchDefinitionValidator validator;
    private final ProfileRepository profileRepository;

    SearchDefinitionParser(GeminiClient geminiClient, PromptLoader promptLoader,
            StructuredResponseReader responseReader, SearchDefinitionValidator validator,
            ProfileRepository profileRepository) {
        this.geminiClient = geminiClient;
        this.promptLoader = promptLoader;
        this.responseReader = responseReader;
        this.validator = validator;
        this.profileRepository = profileRepository;
    }

    public ParsedSearchDefinition parseRecruiterRequirement(String recruiterRequirement) {
        String userPrompt = promptLoader.renderPrompt(PROMPT_FILE, Map.of(
                "query", recruiterRequirement,
                "known_locations", String.join(", ", profileRepository.distinctLocations()),
                "known_skills", String.join(", ", profileRepository.distinctSkills())));

        String modelJson = geminiClient.generateStructuredJson("parse-search",
                promptLoader.renderPrompt("system-instruction.txt", Map.of()), userPrompt,
                GeminiSchemas.searchDefinitionSchema());

        ParsedSearchDefinition parsed = responseReader.readOrReject(modelJson, ParsedSearchDefinition.class, "parse-search");
        ObjectiveFilters filters = validator.validateAndNormalizeFilters(parsed.filters());
        Rubric rubric = validator.validateAndNormalizeRubric(parsed.rubric());
        log.info("Parsed requirement into {} skill(s), {} location(s), {} rubric criteria",
                filters.skills().size(), filters.locations().size(), rubric.criteria().size());
        return new ParsedSearchDefinition(filters, rubric);
    }

    /** The raw shape of the parse response, before validation. */
    public record ParsedSearchDefinition(ObjectiveFilters filters, Rubric rubric) {
    }
}
