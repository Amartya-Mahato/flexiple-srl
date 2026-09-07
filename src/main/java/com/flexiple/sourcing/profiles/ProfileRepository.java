package com.flexiple.sourcing.profiles;

import com.flexiple.sourcing.config.AppProperties;
import com.flexiple.sourcing.domain.Profile;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The entire talent pool, read from {@code profiles.json} once at startup and kept in memory.
 * At 48 records there is nothing to optimise; the point is that no request ever touches the disk.
 */
@Component
public class ProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(ProfileRepository.class);

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private List<Profile> allProfiles = List.of();
    private Map<String, Profile> profilesById = Map.of();

    ProfileRepository(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadTalentPoolFromDisk() {
        Path profilesPath = Path.of(appProperties.profilesFile()).toAbsolutePath().normalize();
        if (!Files.isReadable(profilesPath)) {
            throw new IllegalStateException("Cannot read the talent pool at " + profilesPath
                    + ". Start the app from the project directory, or set PROFILES_FILE to the path of profiles.json.");
        }
        try {
            allProfiles = List.of(objectMapper.readValue(Files.readString(profilesPath), Profile[].class));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read " + profilesPath, exception);
        }
        profilesById = allProfiles.stream()
                .collect(java.util.stream.Collectors.toMap(Profile::id, profile -> profile, (a, b) -> a, LinkedHashMap::new));
        log.info("Loaded {} profiles from {}", allProfiles.size(), profilesPath);
    }

    public List<Profile> allProfiles() {
        return allProfiles;
    }

    public Optional<Profile> findProfileById(String profileId) {
        return Optional.ofNullable(profilesById.get(profileId));
    }

    public boolean containsProfileId(String profileId) {
        return profilesById.containsKey(profileId);
    }

    /** Distinct skill names as they are spelled in the data, so prompts can steer the model to real vocabulary. */
    public List<String> distinctSkills() {
        return allProfiles.stream().flatMap(profile -> profile.skills().stream()).distinct().sorted().toList();
    }

    public List<String> distinctLocations() {
        return allProfiles.stream().map(Profile::location).filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }

    public List<String> distinctTitles() {
        return allProfiles.stream().map(Profile::currentTitle).filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }

    public int lowestYearsOfExperience() {
        return allProfiles.stream().mapToInt(Profile::yearsExperience).min().orElse(0);
    }

    public int highestYearsOfExperience() {
        return allProfiles.stream().mapToInt(Profile::yearsExperience).max().orElse(0);
    }

    /** Every employer name in the dataset, used to catch a model inventing a company for a candidate. */
    public List<String> distinctCompanyNames() {
        return allProfiles.stream()
                .flatMap(profile -> profile.allCompanyNames().stream())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
