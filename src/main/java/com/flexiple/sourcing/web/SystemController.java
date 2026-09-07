package com.flexiple.sourcing.web;

import com.flexiple.sourcing.SourcingService;
import com.flexiple.sourcing.llm.FaultInjector;
import com.flexiple.sourcing.llm.GeminiClient;
import com.flexiple.sourcing.profiles.ProfileRepository;
import com.flexiple.sourcing.web.dto.Requests;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Environment readiness, the vocabulary that powers manual search, and the fault-injection switch
 * used to demonstrate recovery. The API key itself is never exposed here - only whether one is present.
 */
@RestController
@RequestMapping("/api")
class SystemController {

    private final GeminiClient geminiClient;
    private final FaultInjector faultInjector;
    private final SourcingService sourcingService;
    private final ProfileRepository profileRepository;

    SystemController(GeminiClient geminiClient, FaultInjector faultInjector, SourcingService sourcingService,
            ProfileRepository profileRepository) {
        this.geminiClient = geminiClient;
        this.faultInjector = faultInjector;
        this.sourcingService = sourcingService;
        this.profileRepository = profileRepository;
    }

    @GetMapping("/status")
    Map<String, Object> describeEnvironmentReadiness() {
        return Map.of(
                "llm_configured", geminiClient.isConfigured(),
                "model", geminiClient.modelName(),
                "talent_pool_size", sourcingService.talentPoolSize(),
                "armed_fault", faultInjector.peekArmedFault().map(Enum::name).orElse("none"));
    }

    /**
     * Everything the talent map actually contains, so the manual filter controls can autocomplete
     * against real values instead of asking the recruiter to guess at spellings.
     */
    @GetMapping("/vocabulary")
    Vocabulary describeTalentPoolVocabulary() {
        return new Vocabulary(
                profileRepository.distinctSkills(),
                profileRepository.distinctLocations(),
                profileRepository.distinctTitles(),
                profileRepository.distinctCompanyNames(),
                List.of("startup", "scaleup", "enterprise", "agency"),
                profileRepository.lowestYearsOfExperience(),
                profileRepository.highestYearsOfExperience(),
                profileRepository.allProfiles().size());
    }

    /**
     * Arms a one-shot failure on the next AI call so the recovery experience can be shown on demand.
     * Modes: malformed, timeout, rate_limit, empty.
     */
    @PostMapping("/dev/fail-next")
    Map<String, String> armNextLlmCallFailure(@Valid @RequestBody Requests.ArmFaultRequest request) {
        return Map.of("armed_fault", faultInjector.armNextCallFailure(request.mode()).name());
    }

    @PostMapping("/dev/fail-next/clear")
    Map<String, String> disarmNextLlmCallFailure() {
        faultInjector.disarm();
        return Map.of("armed_fault", "none");
    }

    record Vocabulary(
            List<String> skills,
            List<String> locations,
            List<String> titles,
            List<String> companies,
            List<String> companyTypes,
            int minYearsExperience,
            int maxYearsExperience,
            int talentPoolSize) {
    }
}
