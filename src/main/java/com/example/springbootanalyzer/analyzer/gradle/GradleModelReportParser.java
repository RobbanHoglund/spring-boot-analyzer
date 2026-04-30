package com.example.springbootanalyzer.analyzer.gradle;

import com.example.springbootanalyzer.analyzer.model.Finding;
import com.example.springbootanalyzer.analyzer.model.FindingSeverity;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleConfigurationModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleDependencyConflict;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleDependencyModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleJavaToolchainModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleProjectModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleResolutionResult;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleRepositoryModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleResolvedDependencyModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleSourceSetModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleTaskModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GradleModelReportParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public GradleModelAnalysis parse(Path reportFile, String executionMode) {
        if (reportFile == null || Files.notExists(reportFile)) {
            return new GradleModelAnalysis(
                    GradleAnalysisStatus.FAILED,
                    null,
                    null,
                    executionMode,
                    null,
                    "REPORT_NOT_CREATED",
                    "Gradle analysis did not produce a report file.",
                    false,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionBridgeResult.empty(),
                    false,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(new Finding(FindingSeverity.WARNING, "Gradle analysis did not produce a report file.", null))
            );
        }
        try {
            JsonNode root = objectMapper.readTree(reportFile.toFile());
            return new GradleModelAnalysis(
                    GradleAnalysisStatus.SUCCESS,
                    text(root, "gradleVersion"),
                    null,
                    executionMode,
                    reportFile.toString(),
                    null,
                    null,
                    false,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionBridgeResult.empty(),
                    false,
                    null,
                    parseProjects(root.path("projects")),
                    parsePlugins(root.path("plugins")),
                    parseRepositories(root.path("repositories")),
                    parseConfigurations(root.path("configurations")),
                    parseDeclaredDependencies(root.path("declaredDependencies")),
                    parseResolvedDependencies(root.path("resolvedDependencies")),
                    parseResolutionResults(root.path("resolutionResults")),
                    parseDependencyConflicts(root.path("dependencyConflicts")),
                    parseSourceSets(root.path("sourceSets")),
                    parseTasks(root.path("tasks")),
                    parseJavaToolchains(root.path("javaToolchains")),
                    List.of()
            );
        } catch (IOException exception) {
            return new GradleModelAnalysis(
                    GradleAnalysisStatus.FAILED,
                    null,
                    null,
                    executionMode,
                    null,
                    "REPORT_NOT_CREATED",
                    "Failed to parse Gradle model report.",
                    false,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionBridgeResult.empty(),
                    false,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(new Finding(FindingSeverity.WARNING, "Failed to parse Gradle model report.", null))
            );
        }
    }

    private List<GradleProjectModel> parseProjects(JsonNode node) {
        List<GradleProjectModel> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradleProjectModel(text(entry, "path"), text(entry, "name"), text(entry, "projectDir"))));
        return List.copyOf(items);
    }

    private List<GradlePluginModel> parsePlugins(JsonNode node) {
        List<GradlePluginModel> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradlePluginModel(text(entry, "projectPath"), text(entry, "pluginId"), text(entry, "implementationClass"))));
        return List.copyOf(items);
    }

    private List<GradleRepositoryModel> parseRepositories(JsonNode node) {
        List<GradleRepositoryModel> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradleRepositoryModel(text(entry, "projectPath"), text(entry, "name"), text(entry, "type"), text(entry, "url"))));
        return List.copyOf(items);
    }

    private List<GradleConfigurationModel> parseConfigurations(JsonNode node) {
        List<GradleConfigurationModel> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradleConfigurationModel(
                text(entry, "projectPath"),
                text(entry, "name"),
                entry.path("resolvable").asBoolean(false),
                entry.path("consumable").asBoolean(false),
                entry.path("dependencyCount").asInt(0),
                entry.path("declaredDependencyCount").asInt(entry.path("dependencyCount").asInt(0)),
                entry.path("allDependencyCount").asInt(entry.path("dependencyCount").asInt(0)),
                strings(entry.path("extendsFrom"))
        )));
        return List.copyOf(items);
    }

    private List<GradleDependencyModel> parseDeclaredDependencies(JsonNode node) {
        List<GradleDependencyModel> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradleDependencyModel(
                text(entry, "projectPath"),
                text(entry, "configuration"),
                text(entry, "notation"),
                text(entry, "group"),
                text(entry, "artifact"),
                text(entry, "version")
        )));
        return List.copyOf(items);
    }

    private List<GradleResolutionResult> parseResolutionResults(JsonNode node) {
        List<GradleResolutionResult> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradleResolutionResult(
                text(entry, "projectPath"),
                text(entry, "configuration"),
                entry.path("attempted").asBoolean(false),
                entry.path("successful").asBoolean(false),
                entry.path("fallbackUsed").asBoolean(false),
                text(entry, "errorType"),
                text(entry, "errorMessage"),
                entry.path("resolvedDependencyCount").asInt(0)
        )));
        return List.copyOf(items);
    }

    private List<GradleResolvedDependencyModel> parseResolvedDependencies(JsonNode node) {
        List<GradleResolvedDependencyModel> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradleResolvedDependencyModel(
                text(entry, "projectPath"),
                text(entry, "configuration"),
                text(entry, "group"),
                text(entry, "artifact"),
                text(entry, "version"),
                entry.path("direct").asBoolean(false),
                text(entry, "selectedReason")
        )));
        return List.copyOf(items);
    }

    private List<GradleDependencyConflict> parseDependencyConflicts(JsonNode node) {
        List<GradleDependencyConflict> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradleDependencyConflict(
                text(entry, "projectPath"),
                text(entry, "configuration"),
                text(entry, "group"),
                text(entry, "artifact"),
                text(entry, "requestedVersions"),
                text(entry, "selectedVersion")
        )));
        return List.copyOf(items);
    }

    private List<GradleSourceSetModel> parseSourceSets(JsonNode node) {
        List<GradleSourceSetModel> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradleSourceSetModel(
                text(entry, "projectPath"),
                text(entry, "name"),
                strings(entry.path("javaDirs")),
                strings(entry.path("resourceDirs"))
        )));
        return List.copyOf(items);
    }

    private List<GradleTaskModel> parseTasks(JsonNode node) {
        List<GradleTaskModel> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradleTaskModel(
                text(entry, "projectPath"),
                text(entry, "name"),
                text(entry, "group"),
                text(entry, "description")
        )));
        return List.copyOf(items);
    }

    private List<GradleJavaToolchainModel> parseJavaToolchains(JsonNode node) {
        List<GradleJavaToolchainModel> items = new ArrayList<>();
        node.forEach(entry -> items.add(new GradleJavaToolchainModel(
                text(entry, "projectPath"),
                text(entry, "languageVersion"),
                text(entry, "vendor"),
                text(entry, "implementation")
        )));
        return List.copyOf(items);
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(entry -> values.add(entry.asText()));
        return List.copyOf(values);
    }

    private String text(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asText();
    }
}
