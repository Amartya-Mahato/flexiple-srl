package com.flexiple.sourcing.web;

import com.flexiple.sourcing.SourcingService;
import com.flexiple.sourcing.domain.ScoredCandidate;
import com.flexiple.sourcing.domain.ScoringMode;
import com.flexiple.sourcing.domain.SearchSession;
import com.flexiple.sourcing.profiles.SkillNormalizer;
import com.flexiple.sourcing.web.dto.SearchStateResponse;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

/** Turns the live session into the single response shape the workspace renders from. */
@Component
class SearchStateAssembler {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault());

    private final SkillNormalizer skillNormalizer;
    private final SourcingService sourcingService;

    SearchStateAssembler(SkillNormalizer skillNormalizer, SourcingService sourcingService) {
        this.skillNormalizer = skillNormalizer;
        this.sourcingService = sourcingService;
    }

    SearchStateResponse describeSession(SearchSession session) {
        ScoringMode scoringMode = session.lastScoringMode();
        return new SearchStateResponse(
                session.id(),
                session.originalQuery(),
                session.currentFilters(),
                session.currentRubric(),
                session.filtersOrigin().name(),
                session.currentResults().stream().map(candidate -> toCandidateView(candidate, session)).toList(),
                session.matchedProfileCount(),
                sourcingService.talentPoolSize(),
                session.isFrozen(),
                !session.currentResults().isEmpty(),
                scoringMode == null ? null : scoringMode.wireValue(),
                session.canUndo(),
                describeRefinementHistory(session),
                session.matchedProfileCount() == 0 ? sourcingService.explainWhyNothingMatched(session) : List.of());
    }

    private SearchStateResponse.CandidateView toCandidateView(ScoredCandidate candidate, SearchSession session) {
        return new SearchStateResponse.CandidateView(
                candidate.rank(),
                candidate.profile().id(),
                candidate.profile().name(),
                candidate.profile().currentTitle(),
                candidate.profile().yearsExperience(),
                candidate.profile().location(),
                candidate.profile().currentCompany(),
                candidate.profile().currentCompanyType(),
                candidate.profile().skills(),
                skillNormalizer.matchedSkillNames(candidate.profile(), session.currentFilters().skills()),
                candidate.profile().pastCompanies(),
                candidate.profile().education(),
                candidate.profile().summary(),
                candidate.score(),
                candidate.evidence(),
                candidate.explanation());
    }

    private List<SearchStateResponse.RefinementRoundView> describeRefinementHistory(SearchSession session) {
        return session.refinementHistory().stream()
                .map(round -> new SearchStateResponse.RefinementRoundView(
                        round.roundNumber(),
                        round.source(),
                        round.feedback(),
                        round.reply(),
                        round.changes(),
                        TIME_FORMAT.format(round.at())))
                .toList();
    }
}
