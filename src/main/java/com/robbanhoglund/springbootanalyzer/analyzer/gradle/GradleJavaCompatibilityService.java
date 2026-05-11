package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.springframework.stereotype.Service;

@Service
public class GradleJavaCompatibilityService {

    public CompatibilityResult evaluateCompatibility(int javaFeatureVersion, String gradleVersion) {
        String minimum = minimumGradleVersionForJava(javaFeatureVersion);
        if (minimum == null) {
            return new CompatibilityResult(true, javaFeatureVersion, gradleVersion, null);
        }
        boolean compatible = compareVersions(gradleVersion, minimum) >= 0;
        return new CompatibilityResult(compatible, javaFeatureVersion, gradleVersion, minimum);
    }

    public int resolveJavaFeatureVersion(Path javaHome) {
        if (javaHome == null) {
            return Runtime.version().feature();
        }
        Path releaseFile = javaHome.resolve("release");
        if (Files.notExists(releaseFile)) {
            return Runtime.version().feature();
        }
        Properties properties = new Properties();
        try (var inputStream = Files.newInputStream(releaseFile)) {
            properties.load(inputStream);
            String version = properties.getProperty("JAVA_VERSION");
            if (version == null || version.isBlank()) {
                return Runtime.version().feature();
            }
            String cleaned = version.replace("\"", "");
            if (cleaned.startsWith("1.")) {
                return Integer.parseInt(cleaned.substring(2, 3));
            }
            String major = cleaned.split("[^0-9]")[0];
            return Integer.parseInt(major);
        } catch (IOException | NumberFormatException exception) {
            return Runtime.version().feature();
        }
    }

    private String minimumGradleVersionForJava(int javaFeatureVersion) {
        if (javaFeatureVersion >= 25) {
            return "9.1.0";
        }
        if (javaFeatureVersion >= 24) {
            return "8.14.0";
        }
        if (javaFeatureVersion >= 23) {
            return "8.10.0";
        }
        if (javaFeatureVersion >= 22) {
            return "8.8.0";
        }
        if (javaFeatureVersion >= 21) {
            return "8.5.0";
        }
        if (javaFeatureVersion >= 17) {
            return "7.3.0";
        }
        return null;
    }

    int compareVersions(String left, String right) {
        List<Integer> leftParts = parseVersion(left);
        List<Integer> rightParts = parseVersion(right);
        int max = Math.max(leftParts.size(), rightParts.size());
        for (int index = 0; index < max; index++) {
            int leftValue = index < leftParts.size() ? leftParts.get(index) : 0;
            int rightValue = index < rightParts.size() ? rightParts.get(index) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private List<Integer> parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return List.of();
        }
        String normalized = version.replaceAll("[^0-9.].*$", "");
        return java.util.Arrays.stream(normalized.split("\\."))
                .filter(part -> !part.isBlank())
                .map(
                        part -> {
                            try {
                                return Integer.parseInt(part);
                            } catch (NumberFormatException exception) {
                                return 0;
                            }
                        })
                .toList();
    }

    public record CompatibilityResult(
            boolean compatible,
            int javaFeatureVersion,
            String gradleVersion,
            String minimumGradleVersion) {}
}
