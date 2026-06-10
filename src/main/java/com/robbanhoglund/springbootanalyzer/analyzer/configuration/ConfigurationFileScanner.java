package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.PropertySourceType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationFileScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationFileScanner.class);

    private static final List<String> ROOTS =
            List.of("src/main/resources", "src/test/resources", "config", ".");
    private static final Pattern PROFILE_PATTERN =
            Pattern.compile("application-([^.]+)\\.(?:properties|ya?ml)", Pattern.CASE_INSENSITIVE);

    public List<ConfigurationCandidate> scan(Path repositoryRoot) {
        List<ConfigurationCandidate> candidates = new ArrayList<>();
        for (String root : ROOTS) {
            Path start = repositoryRoot.resolve(root).normalize();
            if (Files.notExists(start) || !Files.isDirectory(start)) {
                continue;
            }
            try (var stream = Files.walk(start, root.equals(".") ? 1 : Integer.MAX_VALUE)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> isConfigurationFile(path.getFileName().toString()))
                        .sorted(Comparator.naturalOrder())
                        .forEach(path -> candidates.add(toCandidate(repositoryRoot, path)));
            } catch (IOException exception) {
                LOGGER.warn(
                        "Failed to scan configuration files under {}; skipping this root",
                        start,
                        exception);
            }
        }
        return candidates.stream().distinct().toList();
    }

    private ConfigurationCandidate toCandidate(Path repositoryRoot, Path path) {
        String filename = path.getFileName().toString();
        return new ConfigurationCandidate(
                normalizePath(repositoryRoot, path),
                path,
                detectProfile(filename),
                detectSourceType(filename));
    }

    private boolean isConfigurationFile(String filename) {
        String normalized = filename.toLowerCase(Locale.ROOT);
        return normalized.matches("(application(?:-[^.]+)?|bootstrap)\\.(properties|ya?ml)");
    }

    private String detectProfile(String filename) {
        String normalized = filename.toLowerCase(Locale.ROOT);
        Matcher matcher = PROFILE_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        if (normalized.startsWith("application.")) {
            return "default";
        }
        return "bootstrap";
    }

    private PropertySourceType detectSourceType(String filename) {
        String normalized = filename.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".properties")) {
            return PropertySourceType.PROPERTIES;
        }
        if (normalized.endsWith(".yml") || normalized.endsWith(".yaml")) {
            return PropertySourceType.YAML;
        }
        return PropertySourceType.UNKNOWN;
    }

    private String normalizePath(Path repositoryRoot, Path sourceFile) {
        return repositoryRoot.relativize(sourceFile).toString().replace('\\', '/');
    }

    public record ConfigurationCandidate(
            String relativePath,
            Path absolutePath,
            String profile,
            PropertySourceType sourceType) {}
}
