package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

import java.util.List;

public record GradlePluginResolutionFailure(
        String pluginId,
        String version,
        String artifact,
        String settingsFile,
        Integer line,
        List<String> searchedRepositories,
        String message
) {
    public GradlePluginResolutionFailure {
        searchedRepositories = searchedRepositories == null ? List.of() : List.copyOf(searchedRepositories);
    }
}
