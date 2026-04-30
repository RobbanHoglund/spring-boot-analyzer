package com.example.springbootanalyzer.analyzer.gradle;

import com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GradlePluginResolutionFailureParser {

    private static final Pattern SETTINGS_FILE_PATTERN =
            Pattern.compile("Settings file '([^']+settings\\.gradle(?:\\.kts)?)' line:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLUGIN_ID_PATTERN =
            Pattern.compile("Plugin \\[id:\\s*'([^']+)'(?:,\\s*version:\\s*'([^']+)')?]", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLUGIN_ARTIFACT_PATTERN =
            Pattern.compile("plugin artifact '?([^'\\r\\n]+)'?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEARCHED_REPOSITORY_PATTERN =
            Pattern.compile("^\\s{2,}([^\\r\\n].+)$", Pattern.MULTILINE);

    public GradlePluginResolutionFailure parse(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String normalized = message.toLowerCase();
        if (!normalized.contains("plugin [id:")
                || !normalized.contains("was not found in any of the following sources")) {
            return null;
        }

        Matcher pluginMatcher = PLUGIN_ID_PATTERN.matcher(message);
        String pluginId = null;
        String version = null;
        if (pluginMatcher.find()) {
            pluginId = pluginMatcher.group(1);
            version = pluginMatcher.group(2);
        }

        Matcher settingsMatcher = SETTINGS_FILE_PATTERN.matcher(message);
        String settingsFile = null;
        Integer line = null;
        if (settingsMatcher.find()) {
            settingsFile = settingsMatcher.group(1);
            line = Integer.valueOf(settingsMatcher.group(2));
        }

        Matcher artifactMatcher = PLUGIN_ARTIFACT_PATTERN.matcher(message);
        String artifact = artifactMatcher.find() ? artifactMatcher.group(1) : null;

        List<String> searchedRepositories = new ArrayList<>();
        int searchedIndex = normalized.indexOf("searched in the following repositories");
        if (searchedIndex >= 0) {
            String searchedSection = message.substring(searchedIndex);
            Matcher repoMatcher = SEARCHED_REPOSITORY_PATTERN.matcher(searchedSection);
            boolean first = true;
            while (repoMatcher.find()) {
                String repository = repoMatcher.group(1).trim();
                if (first && repository.toLowerCase().startsWith("searched in the following repositories")) {
                    first = false;
                    continue;
                }
                first = false;
                if (repository.startsWith("*")) {
                    break;
                }
                searchedRepositories.add(repository);
            }
        }

        return new GradlePluginResolutionFailure(
                pluginId,
                version,
                artifact,
                settingsFile == null ? null : settingsFile.substring(settingsFile.lastIndexOf('\\') + 1),
                line,
                searchedRepositories,
                message.trim()
        );
    }
}
