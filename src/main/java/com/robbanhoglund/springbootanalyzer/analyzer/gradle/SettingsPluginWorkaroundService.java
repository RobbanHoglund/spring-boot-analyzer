package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionFailure;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleSettingsPluginWorkaround;
import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class SettingsPluginWorkaroundService {

    private static final String DISABLE_COMMENT = "// Disabled by Spring Boot Analyzer build-model workaround:";

    public WorkaroundResult createSanitizedCopyAndApply(
            Path repositoryRoot,
            GradlePluginResolutionFailure failure,
            AnalyzerProperties.GradleProperties properties
    ) {
        if (failure == null || failure.pluginId() == null) {
            return new WorkaroundResult(repositoryRoot, List.of(), false);
        }
        Path sanitizedRoot = repositoryRoot.getParent()
                .resolve("gradle-analysis-sanitized")
                .resolve("repository");
        try {
            if (Files.exists(sanitizedRoot.getParent())) {
                deleteRecursively(sanitizedRoot.getParent());
            }
            copyTree(repositoryRoot, sanitizedRoot);
            List<GradleSettingsPluginWorkaround> applied = applyWorkaroundToSettingsFiles(sanitizedRoot, failure, properties);
            return new WorkaroundResult(sanitizedRoot, applied, !applied.isEmpty());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create sanitized Gradle analysis copy.", exception);
        }
    }

    List<GradleSettingsPluginWorkaround> applyWorkaroundToSettingsFiles(
            Path repositoryRoot,
            GradlePluginResolutionFailure failure,
            AnalyzerProperties.GradleProperties properties
    ) throws IOException {
        List<GradleSettingsPluginWorkaround> applied = new ArrayList<>();
        for (String settingsFileName : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settingsFile = repositoryRoot.resolve(settingsFileName);
            if (Files.notExists(settingsFile)) {
                continue;
            }
            List<String> lines = Files.readAllLines(settingsFile);
            List<String> rewritten = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (shouldDisable(line, failure.pluginId(), properties)) {
                    rewritten.add(DISABLE_COMMENT);
                    rewritten.add("// " + line.trim());
                    applied.add(new GradleSettingsPluginWorkaround(
                            failure.pluginId(),
                            failure.version(),
                            settingsFile.getFileName().toString(),
                            index + 1,
                            "Disabled in sanitized analysis copy",
                            "Plugin resolution failed; toolchain resolver not required for dependency extraction"
                    ));
                } else {
                    rewritten.add(line);
                }
            }
            Files.write(settingsFile, rewritten);
        }
        return List.copyOf(applied);
    }

    private boolean shouldDisable(
            String line,
            String pluginId,
            AnalyzerProperties.GradleProperties properties
    ) {
        if (line == null || line.isBlank()) {
            return false;
        }
        if (line.trim().startsWith("//")) {
            return false;
        }
        if (!properties.settingsPluginWorkarounds().knownNonessentialPlugins().contains(pluginId)) {
            return false;
        }
        return line.contains(pluginId)
                && line.contains("version")
                && line.contains("id");
    }

    private void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record WorkaroundResult(
            Path sanitizedRepositoryRoot,
            List<GradleSettingsPluginWorkaround> appliedWorkarounds,
            boolean applied
    ) {
    }
}
