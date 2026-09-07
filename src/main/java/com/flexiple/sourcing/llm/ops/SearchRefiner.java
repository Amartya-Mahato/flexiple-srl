package com.flexiple.sourcing.llm.ops;

import com.flexiple.sourcing.domain.CandidateVerdict;
import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.ScoredCandidate;
import com.flexiple.sourcing.domain.SearchChange;
import com.flexiple.sourcing.domain.SearchSession;
import com.flexiple.sourcing.llm.GeminiClient;
import com.flexiple.sourcing.llm.GeminiSchemas;
import com.flexiple.sourcing.llm.PromptLoader;
import com.flexiple.sourcing.llm.StructuredResponseReader;
import com.flexiple.sourcing.validation.SearchDefinitionValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM operation 3: turn recruiter feedback into an updated search definition plus an explanation of
 * what changed. The result is validated before the caller is allowed to write it into the session,
 * so a bad refinement leaves the previous search completely intact.
 */
@Service
public class SearchRefiner {

    private static final Logger log = LoggerFactory.getLogger(SearchRefiner.class);
    private static final String PROMPT_FILE = "refine-search.txt";
    private static final int CANDIDATES_DESCRIBED_TO_THE_MODEL = 5;

    private final GeminiClient geminiClient;
    private final PromptLoader promptLoader;
    private final StructuredResponseReader responseReader;
    private final SearchDefinitionValidator validator;

    SearchRefiner(GeminiClient geminiClient, PromptLoader promptLoader, StructuredResponseReader responseReader,
            SearchDefinitionValidator validator) {
        this.geminiClient = geminiClient;
        this.promptLoader = promptLoader;
        this.responseReader = responseReader;
        this.validator = validator;
    }

    public RefinedSearchDefinition refineSearchFromRecruiterFeedback(SearchSession session, String feedback,
            List<CandidateVerdict> verdicts) {
        String userPrompt = promptLoader.renderPrompt(PROMPT_FILE, Map.of(
                "original_query", session.originalQuery(),
                "current_filters", responseReader.toPromptJson(session.currentFilters()),
                "current_rubric", responseReader.toPromptJson(session.currentRubric()),
                "shown_candidates", responseReader.toPromptJson(describeCandidatesOnScreen(session)),
                "verdicts", describeVerdicts(session, verdicts),
                "feedback", feedback == null || feedback.isBlank() ? "(no written feedback)" : feedback));

        String modelJson = geminiClient.generateStructuredJson("refine-search",
                promptLoader.renderPrompt("system-instruction.txt", Map.of()), userPrompt,
                GeminiSchemas.refinementSchema());

        RefinementResponse response = responseReader.readOrReject(modelJson, RefinementResponse.class, "refine-search");
        ObjectiveFilters filters = validator.validateAndNormalizeFilters(response.filters());
        Rubric rubric = validator.validateAndNormalizeRubric(response.rubric());
        log.info("Refinement produced {} explained change(s)", response.changes().size());
        return new RefinedSearchDefinition(filters, rubric, response.changes(), response.reply());
    }

    /** A compact view of what the recruiter was actually looking at, numbered the way the cards are. */
    private List<Map<String, Object>> describeCandidatesOnScreen(SearchSession session) {
        return session.currentResults().stream()
                .limit(CANDIDATES_DESCRIBED_TO_THE_MODEL)
                .map(this::describeCandidate)
                .toList();
    }

    private Map<String, Object> describeCandidate(ScoredCandidate candidate) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("shown_as", "#" + candidate.rank());
        description.put("profile_id", candidate.profile().id());
        description.put("name", candidate.profile().name());
        description.put("current_title", candidate.profile().currentTitle());
        description.put("years_experience", candidate.profile().yearsExperience());
        description.put("location", candidate.profile().location());
        description.put("current_company", candidate.profile().currentCompany());
        description.put("current_company_type", candidate.profile().currentCompanyType());
        description.put("skills", candidate.profile().skills());
        description.put("past_companies", candidate.profile().pastCompanies());
        description.put("score", candidate.score());
        return description;
    }

    private String describeVerdicts(SearchSession session, List<CandidateVerdict> verdicts) {
        if (verdicts == null || verdicts.isEmpty()) {
            return "(the recruiter did not mark any candidate individually)";
        }
        Map<String, Integer> rankByProfileId = new LinkedHashMap<>();
        session.currentResults().forEach(candidate -> rankByProfileId.put(candidate.profile().id(), candidate.rank()));
        return verdicts.stream()
                .map(verdict -> "#" + rankByProfileId.getOrDefault(verdict.profileId(), 0) + " ("
                        + verdict.profileId() + "): " + (verdict.isYes() ? "GOOD MATCH" : "NOT A MATCH"))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    public record RefinedSearchDefinition(ObjectiveFilters filters, Rubric rubric, List<SearchChange> changes,
            String reply) {

        public RefinedSearchDefinition {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    record RefinementResponse(ObjectiveFilters filters, Rubric rubric, List<SearchChange> changes, String reply) {

        RefinementResponse {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }
}
