package com.flexiple.sourcing.web;

import com.flexiple.sourcing.SourcingService;
import com.flexiple.sourcing.web.dto.Requests;
import com.flexiple.sourcing.web.dto.SearchStateResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sourcing loop over HTTP, with two ways in and two ways to rank.
 *
 * <p>Parsing and scoring are separate calls on purpose: the recruiter sees the filters and rubric the
 * moment they exist, while the slower ranking step runs behind its own progress state instead of one
 * long unexplained wait.
 */
@RestController
@RequestMapping("/api/search")
class SearchController {

    private final SourcingService sourcingService;
    private final SearchStateAssembler stateAssembler;

    SearchController(SourcingService sourcingService, SearchStateAssembler stateAssembler) {
        this.sourcingService = sourcingService;
        this.stateAssembler = stateAssembler;
    }

    /** Free text in, objective filters and a fit rubric out, plus how many profiles they select. */
    @PostMapping("/parse")
    SearchStateResponse parseRequirementIntoSearchDefinition(@Valid @RequestBody Requests.StartSearchRequest request) {
        return stateAssembler.describeSession(sourcingService.startSearchFromFreeText(request.query().trim()));
    }

    /**
     * The manual way in: filters the recruiter assembled by hand. No model call, results come back
     * ranked locally and instantly, and the AI can be brought in afterwards if they want it.
     */
    @PostMapping("/manual-start")
    SearchStateResponse startSearchFromManualFilters(@Valid @RequestBody Requests.ManualStartRequest request) {
        return stateAssembler.describeSession(
                sourcingService.startSearchFromManualFilters(request.filters(), request.rubric()));
    }

    /** How many profiles these filters would select. No session, no model, no side effects. */
    @PostMapping("/preview")
    SourcingService.FilterPreview previewHowManyProfilesTheseFiltersSelect(
            @RequestBody Requests.ManualStartRequest request) {
        return sourcingService.previewFilters(request.filters());
    }

    /** Ranks whoever passed the objective filters, against the rubric or by local relevance. */
    @PostMapping("/run")
    SearchStateResponse scoreAndRankMatchingProfiles(@Valid @RequestBody Requests.RunSearchRequest request) {
        return stateAssembler.describeSession(sourcingService.runScoringForCurrentSearchDefinition(
                request.sessionId(), request.resolvedScoringMode()));
    }

    /** Chat feedback and per-profile verdicts in, an explained new search definition and results out. */
    @PostMapping("/refine")
    SearchStateResponse refineSearchFromRecruiterFeedback(@Valid @RequestBody Requests.RefineSearchRequest request) {
        return stateAssembler.describeSession(sourcingService.refineSearchWithFeedback(
                request.sessionId(), request.feedback(), request.verdicts()));
    }

    /** The recruiter's own edits to filters or rubric, re-run the same way but never credited to the AI. */
    @PostMapping("/manual-update")
    SearchStateResponse applyManualSearchDefinitionEdits(@Valid @RequestBody Requests.ManualUpdateRequest request) {
        return stateAssembler.describeSession(sourcingService.applyManualSearchDefinitionEdits(
                request.sessionId(), request.filters(), request.rubric(), request.resolvedScoringMode()));
    }

    /** Steps back one round, restoring the search definition that round started from. */
    @PostMapping("/undo")
    SearchStateResponse undoLastRefinement(@Valid @RequestBody Requests.RunSearchRequest request) {
        return stateAssembler.describeSession(sourcingService.undoLastRefinement(
                request.sessionId(), request.resolvedScoringMode()));
    }

    /** Makes the search final: filters, rubric and shortlist stop changing. */
    @PostMapping("/freeze")
    SearchStateResponse freezeSearch(@Valid @RequestBody Requests.SessionRequest request) {
        return stateAssembler.describeSession(sourcingService.freezeSearch(request.sessionId()));
    }

    /** Re-reads the current state, used after a failure so the UI can resynchronise without a re-run. */
    @PostMapping("/state")
    SearchStateResponse describeCurrentState(@Valid @RequestBody Requests.SessionRequest request) {
        return stateAssembler.describeSession(sourcingService.getSession(request.sessionId()));
    }
}
