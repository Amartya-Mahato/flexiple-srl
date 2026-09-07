package com.flexiple.sourcing.validation;

import com.flexiple.sourcing.domain.Evidence;
import com.flexiple.sourcing.domain.Profile;
import com.flexiple.sourcing.domain.ScoredCandidate;
import com.flexiple.sourcing.llm.LlmException;
import com.flexiple.sourcing.profiles.ProfileRepository;
import com.flexiple.sourcing.profiles.SkillNormalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides what the recruiter is actually allowed to see.
 *
 * <p>The model proposes a score, a handful of cited facts and a one-line summary. None of that is
 * trusted: candidate ids must belong to the set we filtered locally, scores must be in range, each
 * cited fact is checked against the real profile, and a summary that names a company the candidate
 * never worked at is thrown away. Whatever survives is what the card shows - so an explanation on
 * screen is, by construction, backed by the data.
 */
@Component
public class CandidateScoreGrounder {

    private static final Logger log = LoggerFactory.getLogger(CandidateScoreGrounder.class);
    private static final int MINIMUM_SCORE = 0;
    private static final int MAXIMUM_SCORE = 100;
    private static final int MAX_EVIDENCE_ITEMS_SHOWN = 4;

    private final SkillNormalizer skillNormalizer;
    private final ProfileRepository profileRepository;

    CandidateScoreGrounder(SkillNormalizer skillNormalizer, ProfileRepository profileRepository) {
        this.skillNormalizer = skillNormalizer;
        this.profileRepository = profileRepository;
    }

    /**
     * @param rawScores what the model returned
     * @param filteredProfiles the only candidates that may appear, in the order local filtering produced
     */
    public List<ScoredCandidate> groundAndRankScores(List<RawCandidateScore> rawScores, List<Profile> filteredProfiles) {
        Map<String, Profile> allowedProfilesById = filteredProfiles.stream()
                .collect(java.util.stream.Collectors.toMap(Profile::id, profile -> profile));

        List<ScoredCandidate> grounded = new ArrayList<>();
        Set<String> acceptedProfileIds = new HashSet<>();
        int rejectedCount = 0;

        for (RawCandidateScore rawScore : rawScores) {
            Profile profile = allowedProfilesById.get(rawScore.profileId());
            boolean isUsable = profile != null
                    && !acceptedProfileIds.contains(rawScore.profileId())
                    && rawScore.score() >= MINIMUM_SCORE
                    && rawScore.score() <= MAXIMUM_SCORE;
            if (!isUsable) {
                rejectedCount++;
                continue;
            }
            List<Evidence> verifiedEvidence = keepOnlyEvidenceSupportedByProfile(rawScore.evidence(), profile);
            grounded.add(new ScoredCandidate(0, profile, rawScore.score(), verifiedEvidence,
                    chooseTrustworthyExplanation(rawScore.summary(), verifiedEvidence, profile)));
            acceptedProfileIds.add(rawScore.profileId());
        }

        if (grounded.isEmpty() && !filteredProfiles.isEmpty()) {
            throw LlmException.invalidResponse("no scored candidate could be matched back to the filtered set");
        }
        if (rejectedCount > 0) {
            log.warn("Discarded {} scored candidates that failed validation", rejectedCount);
        }

        appendUnscoredProfilesSoNobodyDisappears(grounded, filteredProfiles, acceptedProfileIds);
        return rankDeterministically(grounded);
    }

    /**
     * A profile that survived objective filtering must never vanish because the model forgot it.
     * It is added at the bottom with a fact-only explanation and a neutral score.
     */
    private void appendUnscoredProfilesSoNobodyDisappears(List<ScoredCandidate> grounded,
            List<Profile> filteredProfiles, Set<String> alreadyScoredProfileIds) {
        for (Profile profile : filteredProfiles) {
            if (alreadyScoredProfileIds.contains(profile.id())) {
                continue;
            }
            log.warn("Profile {} was not scored by the model; showing it unranked with a fact-only explanation", profile.id());
            grounded.add(new ScoredCandidate(0, profile, MINIMUM_SCORE, List.of(),
                    buildExplanationFromProfileFields(profile)));
        }
    }

    private List<ScoredCandidate> rankDeterministically(List<ScoredCandidate> candidates) {
        List<ScoredCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.profile().id()))
                .toList();
        List<ScoredCandidate> ranked = new ArrayList<>(sorted.size());
        for (int index = 0; index < sorted.size(); index++) {
            ranked.add(sorted.get(index).withRank(index + 1));
        }
        return List.copyOf(ranked);
    }

    private List<Evidence> keepOnlyEvidenceSupportedByProfile(List<Evidence> claimedEvidence, Profile profile) {
        return claimedEvidence.stream()
                .filter(java.util.Objects::nonNull)
                .filter(evidence -> evidence.value() != null && !evidence.value().isBlank())
                .filter(evidence -> isEvidenceTrueOfProfile(evidence, profile))
                .limit(MAX_EVIDENCE_ITEMS_SHOWN)
                .toList();
    }

    private boolean isEvidenceTrueOfProfile(Evidence evidence, Profile profile) {
        String field = evidence.field() == null ? "" : evidence.field().trim().toLowerCase(Locale.ROOT);
        String claimedValue = evidence.value().trim();
        return switch (field) {
            case "years_experience", "years", "experience" -> containsDigits(claimedValue, profile.yearsExperience());
            case "location" -> equalsIgnoringPunctuation(claimedValue, profile.location());
            case "skills", "skill" -> skillNormalizer.profileHasSkill(profile, claimedValue);
            case "current_company", "company" -> equalsIgnoringPunctuation(claimedValue, profile.currentCompany());
            case "current_company_type", "company_type" -> equalsIgnoringPunctuation(claimedValue, profile.currentCompanyType());
            case "current_title", "title" -> equalsIgnoringPunctuation(claimedValue, profile.currentTitle());
            case "past_company", "past_companies" -> profile.pastCompanies().stream()
                    .anyMatch(pastCompany -> equalsIgnoringPunctuation(claimedValue, pastCompany.company())
                            || equalsIgnoringPunctuation(claimedValue, pastCompany.title())
                            || equalsIgnoringPunctuation(claimedValue, pastCompany.companyType()));
            case "education" -> profile.education() != null
                    && normalize(profile.education()).contains(normalize(claimedValue));
            case "summary" -> profile.summary() != null && normalize(profile.summary()).contains(normalize(claimedValue));
            default -> false;
        };
    }

    /**
     * Prefers the model's sentence, but only when it does not name an employer from elsewhere in the
     * dataset. That single check catches the most damaging hallucination cheaply and precisely.
     */
    private String chooseTrustworthyExplanation(String modelSummary, List<Evidence> verifiedEvidence, Profile profile) {
        if (modelSummary == null || modelSummary.isBlank() || verifiedEvidence.isEmpty()) {
            return buildExplanationFromProfileFields(profile);
        }
        if (mentionsCompanyTheCandidateNeverWorkedAt(modelSummary, profile)) {
            log.warn("Rejected the summary for {}: it named a company that is not on the profile", profile.id());
            return buildExplanationFromProfileFields(profile);
        }
        return modelSummary.trim();
    }

    private boolean mentionsCompanyTheCandidateNeverWorkedAt(String summary, Profile profile) {
        String normalizedSummary = normalize(summary);
        Set<String> ownEmployers = profile.allCompanyNames().stream()
                .map(this::normalize)
                .collect(java.util.stream.Collectors.toSet());
        return profileRepository.distinctCompanyNames().stream()
                .map(this::normalize)
                .filter(company -> company.length() >= 4)
                .filter(company -> !ownEmployers.contains(company))
                .anyMatch(normalizedSummary::contains);
    }

    /** The fallback explanation: assembled purely from profile fields, so it cannot be wrong. */
    private String buildExplanationFromProfileFields(Profile profile) {
        String topSkills = String.join(", ", profile.skills().stream().limit(3).toList());
        return "%d years of experience, currently %s at %s (%s) in %s%s."
                .formatted(profile.yearsExperience(), profile.currentTitle(), profile.currentCompany(),
                        profile.currentCompanyType(), profile.location(),
                        topSkills.isBlank() ? "" : "; skills include " + topSkills);
    }

    private boolean containsDigits(String claimedValue, int actualNumber) {
        String digitsOnly = claimedValue.replaceAll("[^0-9]", "");
        return !digitsOnly.isEmpty() && digitsOnly.equals(String.valueOf(actualNumber));
    }

    private boolean equalsIgnoringPunctuation(String claimedValue, String actualValue) {
        return actualValue != null && normalize(claimedValue).equals(normalize(actualValue));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The unvalidated shape the model returns for one candidate. */
    public record RawCandidateScore(String profileId, int score, List<Evidence> evidence, String summary) {

        public RawCandidateScore {
            evidence = evidence == null ? List.of() : evidence.stream().filter(java.util.Objects::nonNull).toList();
        }
    }
}
