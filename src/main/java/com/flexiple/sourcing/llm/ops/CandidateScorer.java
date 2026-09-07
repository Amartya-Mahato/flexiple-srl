package com.flexiple.sourcing.llm.ops;

import com.flexiple.sourcing.domain.Profile;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.ScoredCandidate;
import com.flexiple.sourcing.llm.GeminiClient;
import com.flexiple.sourcing.llm.GeminiSchemas;
import com.flexiple.sourcing.llm.PromptLoader;
import com.flexiple.sourcing.llm.StructuredResponseReader;
import com.flexiple.sourcing.validation.CandidateScoreGrounder;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM operation 2: score the locally filtered candidates against the fit rubric.
 * The model only ever sees candidates that already passed the objective filters, and its output is
 * put through {@link CandidateScoreGrounder} before anything is shown.
 */
@Service
public class CandidateScorer {

    private static final Logger log = LoggerFactory.getLogger(CandidateScorer.class);
    private static final String PROMPT_FILE = "score-profiles.txt";

    private final GeminiClient geminiClient;
    private final PromptLoader promptLoader;
    private final StructuredResponseReader responseReader;
    private final CandidateScoreGrounder scoreGrounder;

    CandidateScorer(GeminiClient geminiClient, PromptLoader promptLoader, StructuredResponseReader responseReader,
            CandidateScoreGrounder scoreGrounder) {
        this.geminiClient = geminiClient;
        this.promptLoader = promptLoader;
        this.responseReader = responseReader;
        this.scoreGrounder = scoreGrounder;
    }

    public List<ScoredCandidate> scoreProfilesAgainstRubric(String originalQuery, Rubric rubric,
            List<Profile> filteredProfiles) {
        if (filteredProfiles.isEmpty()) {
            return List.of();
        }

        String userPrompt = promptLoader.renderPrompt(PROMPT_FILE, Map.of(
                "original_query", originalQuery,
                "rubric", responseReader.toPromptJson(rubric),
                "candidates", responseReader.toPromptJson(filteredProfiles)));

        String modelJson = geminiClient.generateStructuredJson("score-profiles",
                promptLoader.renderPrompt("system-instruction.txt", Map.of()), userPrompt,
                GeminiSchemas.candidateScoresSchema());

        CandidateScoresResponse response =
                responseReader.readOrReject(modelJson, CandidateScoresResponse.class, "score-profiles");
        List<ScoredCandidate> ranked = scoreGrounder.groundAndRankScores(response.candidates(), filteredProfiles);
        log.info("Scored and ranked {} of {} filtered profiles", ranked.size(), filteredProfiles.size());
        return ranked;
    }

    record CandidateScoresResponse(List<CandidateScoreGrounder.RawCandidateScore> candidates) {

        CandidateScoresResponse {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }
}
