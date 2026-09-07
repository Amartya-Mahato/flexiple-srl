package com.flexiple.sourcing.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads prompts from {@code src/main/resources/prompts} so they stay reviewable as plain text
 * rather than hiding inside Java string concatenation. Placeholders look like {@code {{name}}}.
 */
@Component
public class PromptLoader {

    private final Map<String, String> promptTemplatesByName = new ConcurrentHashMap<>();

    public String renderPrompt(String promptFileName, Map<String, String> variables) {
        String template = promptTemplatesByName.computeIfAbsent(promptFileName, this::readPromptFromClasspath);
        String rendered = template;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            rendered = rendered.replace("{{" + variable.getKey() + "}}", variable.getValue());
        }
        return rendered;
    }

    private String readPromptFromClasspath(String promptFileName) {
        ClassPathResource resource = new ClassPathResource("prompts/" + promptFileName);
        try (var inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Missing prompt file: prompts/" + promptFileName, exception);
        }
    }
}
