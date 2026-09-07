package com.flexiple.sourcing.web;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.flexiple.sourcing.domain.Evidence;
import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Profile;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.RubricCriterion;
import com.flexiple.sourcing.domain.ScoredCandidate;
import com.flexiple.sourcing.llm.LlmException;
import com.flexiple.sourcing.llm.ops.CandidateScorer;
import com.flexiple.sourcing.llm.ops.SearchDefinitionParser;
import com.flexiple.sourcing.llm.ops.SearchRefiner;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract, with the AI operations stubbed: the app boots, the loop is reachable, and every
 * failure comes back as a coded, retryable-flagged error rather than a stack trace.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchDefinitionParser searchDefinitionParser;

    @MockitoBean
    private CandidateScorer candidateScorer;

    @MockitoBean
    private SearchRefiner searchRefiner;

    private final ObjectiveFilters filters = new ObjectiveFilters(List.of("AWS RDS"), 4, 7, List.of("Bangalore"),
            List.of("startup"), List.of(), List.of(), false, false);
    private final Rubric rubric = new Rubric("Owns production databases",
            List.of(new RubricCriterion("Database depth", "Runs databases in production", 1.0)));

    @BeforeEach
    void stubTheAiOperations() {
        when(searchDefinitionParser.parseRecruiterRequirement(anyString()))
                .thenReturn(new SearchDefinitionParser.ParsedSearchDefinition(filters, rubric));
        when(candidateScorer.scoreProfilesAgainstRubric(anyString(), any(), anyList()))
                .thenAnswer(invocation -> {
                    List<Profile> profiles = invocation.getArgument(2);
                    return List.of(new ScoredCandidate(1, profiles.getFirst(), 91,
                            List.of(new Evidence("skills", "AWS RDS")), "Lists AWS RDS and works in Bangalore."));
                });
    }

    private String startASearchAndReturnItsSessionId() throws Exception {
        String body = mockMvc.perform(post("/api/search/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"RDS engineers in Bangalore\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.min_years_experience").value(4))
                .andExpect(jsonPath("$.rubric.criteria[0].name").value("Database depth"))
                .andExpect(jsonPath("$.matched_count", greaterThan(0)))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll("(?s).*\"session_id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    void runsTheLoopFromParseThroughScoringToFreeze() throws Exception {
        String sessionId = startASearchAndReturnItsSessionId();

        mockMvc.perform(post("/api/search/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"" + sessionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scored").value(true))
                .andExpect(jsonPath("$.results[0].profile_id").value("p01"))
                .andExpect(jsonPath("$.results[0].evidence[0].value").value("AWS RDS"));

        mockMvc.perform(post("/api/search/freeze").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"" + sessionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frozen").value(true));

        mockMvc.perform(post("/api/search/refine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"" + sessionId + "\", \"feedback\": \"one more change\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_FROZEN"));
    }

    @Test
    void reportsAMalformedRefinementAsARetryableErrorAndKeepsThePreviousState() throws Exception {
        String sessionId = startASearchAndReturnItsSessionId();
        when(searchRefiner.refineSearchFromRecruiterFeedback(any(), anyString(), anyList()))
                .thenThrow(LlmException.invalidResponse("simulated malformed JSON"));

        mockMvc.perform(post("/api/search/refine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"" + sessionId + "\", \"feedback\": \"1 is too junior\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("INVALID_LLM_RESPONSE"))
                .andExpect(jsonPath("$.retryable").value(true));

        mockMvc.perform(post("/api/search/state").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"" + sessionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.min_years_experience").value(4))
                .andExpect(jsonPath("$.history").isEmpty());
    }

    @Test
    void mapsRateLimitingToItsOwnStatusAndCode() throws Exception {
        String sessionId = startASearchAndReturnItsSessionId();
        when(searchRefiner.refineSearchFromRecruiterFeedback(any(), anyString(), anyList()))
                .thenThrow(LlmException.rateLimited());

        mockMvc.perform(post("/api/search/refine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"" + sessionId + "\", \"feedback\": \"try again\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LLM_RATE_LIMITED"));
    }

    @Test
    void rejectsAnEmptyOrOversizedRequirementAtTheEdge() throws Exception {
        mockMvc.perform(post("/api/search/parse").contentType(MediaType.APPLICATION_JSON).content("{\"query\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/search/parse").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"" + "x".repeat(700) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesToActOnAnUnknownSession() throws Exception {
        mockMvc.perform(post("/api/search/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"not-a-session\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void servesTheManualPathWithoutTouchingTheModelAtAll() throws Exception {
        String manualFilters = """
                {"filters": {"skills": ["AWS RDS"], "min_years_experience": 4, "max_years_experience": 7,
                 "locations": ["Bangalore"], "company_types": ["startup"], "past_company_types": [],
                 "exclude_company_types": [], "skills_match_all": false, "remote_ok": false}}""";

        mockMvc.perform(post("/api/search/manual-start").contentType(MediaType.APPLICATION_JSON).content(manualFilters))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoring_mode").value("local"))
                .andExpect(jsonPath("$.filters_origin").value("MANUALLY_EDITED"))
                .andExpect(jsonPath("$.matched_count").value(6))
                .andExpect(jsonPath("$.results[0].explanation").isNotEmpty())
                .andExpect(jsonPath("$.rubric.criteria").isNotEmpty());

        org.mockito.Mockito.verifyNoInteractions(searchDefinitionParser, candidateScorer, searchRefiner);
    }

    @Test
    void previewsHowManyProfilesFiltersSelectWithoutCreatingASession() throws Exception {
        mockMvc.perform(post("/api/search/preview").contentType(MediaType.APPLICATION_JSON).content("""
                        {"filters": {"skills": ["AWS RDS"], "min_years_experience": 4, "max_years_experience": 7,
                         "locations": ["Bangalore"], "company_types": ["startup"], "past_company_types": [],
                         "exclude_company_types": [], "skills_match_all": false, "remote_ok": false}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched_count").value(6))
                .andExpect(jsonPath("$.talent_pool_size").value(48))
                .andExpect(jsonPath("$.dimension_match_counts").isNotEmpty())
                .andExpect(jsonPath("$.session_id").doesNotExist());
    }

    @Test
    void undoesTheLastRoundAndRefusesToGoBackPastTheOriginalSearch() throws Exception {
        String sessionId = startASearchAndReturnItsSessionId();
        when(searchRefiner.refineSearchFromRecruiterFeedback(any(), anyString(), anyList()))
                .thenReturn(new SearchRefiner.RefinedSearchDefinition(
                        new ObjectiveFilters(List.of("AWS RDS"), 6, 7, List.of("Bangalore"),
                                List.of("startup"), List.of(), List.of(), false, false),
                        rubric, List.of(), "Tightened the range."));

        mockMvc.perform(post("/api/search/refine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"" + sessionId + "\", \"feedback\": \"too junior\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.min_years_experience").value(6))
                .andExpect(jsonPath("$.can_undo").value(true));

        mockMvc.perform(post("/api/search/undo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"" + sessionId + "\", \"scoring_mode\": \"local\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.min_years_experience").value(4))
                .andExpect(jsonPath("$.history").isEmpty())
                .andExpect(jsonPath("$.can_undo").value(false));

        mockMvc.perform(post("/api/search/undo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"" + sessionId + "\", \"scoring_mode\": \"local\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_UNDO"));
    }

    @Test
    void publishesTheTalentPoolVocabularyForManualAutocomplete() throws Exception {
        mockMvc.perform(get("/api/vocabulary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills").isNotEmpty())
                .andExpect(jsonPath("$.locations").isNotEmpty())
                .andExpect(jsonPath("$.company_types", org.hamcrest.Matchers.hasSize(4)))
                .andExpect(jsonPath("$.talent_pool_size").value(48));
    }

    @Test
    void reportsEnvironmentReadinessWithoutEverExposingTheKey() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.talent_pool_size").value(48))
                .andExpect(jsonPath("$.api_key").doesNotExist())
                .andExpect(jsonPath("$.armed_fault").value("none"));
    }

    @Test
    void servesTheFrontendFromTheSameOrigin() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/app.js")).andExpect(status().isOk());
        mockMvc.perform(get("/styles.css")).andExpect(status().isOk());
    }
}
