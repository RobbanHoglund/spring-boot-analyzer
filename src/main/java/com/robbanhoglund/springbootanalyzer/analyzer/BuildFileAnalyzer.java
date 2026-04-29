package com.robbanhoglund.springbootanalyzer.analyzer;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class BuildFileAnalyzer {

    private static final List<String> SPRING_BOOT_MARKERS = List.of(
            "org.springframework.boot",
            "spring-boot-starter",
            "spring-boot-maven-plugin"
    );

    private static final Pattern GRADLE_DEPENDENCY_PATTERN =
            Pattern.compile("['\"]([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+(?::[^'\"]+)?)['\"]");

    private static final Pattern MAVEN_DEPENDENCY_PATTERN = Pattern.compile(
            "<dependency>.*?<groupId>([^<]+)</groupId>.*?<artifactId>([^<]+)</artifactId>(?:.*?<version>([^<]+)</version>)?.*?</dependency>",
            Pattern.DOTALL
    );

    private static final List<Pattern> JAVA_VERSION_PATTERNS = List.of(
            Pattern.compile("JavaLanguageVersion\\.of\\((\\d+)\\)"),
            Pattern.compile("VERSION_(\\d+)"),
            Pattern.compile("(?:sourceCompatibility|targetCompatibility)\\s*=\\s*['\"]?(\\d+)"),
            Pattern.compile("<java\\.version>(\\d+)</java\\.version>"),
            Pattern.compile("<maven\\.compiler\\.(?:source|target|release)>(\\d+)</maven\\.compiler\\.(?:source|target|release)>")
    );
    private static final List<VersionPattern> SPRING_BOOT_VERSION_PATTERNS = List.of(
            new VersionPattern(Pattern.compile("id\\s*['\"]org\\.springframework\\.boot['\"]\\s*version\\s*['\"]([^'\"]+)['\"]"), "Gradle plugins", "HIGH"),
            new VersionPattern(Pattern.compile("org\\.springframework\\.boot\\)\\s*version\\s*['\"]([^'\"]+)['\"]"), "Gradle plugins", "HIGH"),
            new VersionPattern(Pattern.compile("springBoot\\s*=\\s*['\"]([^'\"]+)['\"]"), "gradle.properties", "MEDIUM"),
            new VersionPattern(Pattern.compile("spring-boot\\s*=\\s*['\"]?([^\\s'\"]+)"), "version catalog", "MEDIUM"),
            new VersionPattern(Pattern.compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>", Pattern.DOTALL), "Maven parent", "HIGH"),
            new VersionPattern(Pattern.compile("<artifactId>spring-boot-dependencies</artifactId>\\s*<version>([^<]+)</version>", Pattern.DOTALL), "Maven BOM", "HIGH"),
            new VersionPattern(Pattern.compile("org\\.springframework\\.boot:[^:'\"]+[:\"]([^'\"]+)['\"]"), "Dependency declaration", "LOW")
    );

    public BuildInfo analyze(Path repositoryRoot) {
        BuildTool buildTool = detectBuildTool(repositoryRoot);
        List<Path> buildFiles = detectBuildFiles(repositoryRoot);

        boolean springBootDetected = false;
        String javaVersionHint = null;
        String springBootVersion = null;
        String springBootVersionSource = null;
        String springBootVersionConfidence = null;
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();

        for (Path buildFile : buildFiles) {
            String content = readFile(buildFile);
            springBootDetected = springBootDetected || containsSpringBootMarker(content);
            if (javaVersionHint == null) {
                javaVersionHint = detectJavaVersionHint(content);
            }
            if (springBootVersion == null) {
                SpringBootVersion springBootVersionResult = detectSpringBootVersion(content);
                if (springBootVersionResult != null) {
                    springBootVersion = springBootVersionResult.version();
                    springBootVersionSource = springBootVersionResult.source();
                    springBootVersionConfidence = springBootVersionResult.confidence();
                }
            }
            dependencies.addAll(extractDependencies(buildFile, content));
        }

        return new BuildInfo(
                buildTool,
                springBootDetected,
                javaVersionHint,
                List.copyOf(dependencies),
                springBootVersion,
                springBootVersionSource,
                springBootVersionConfidence
        );
    }

    private BuildTool detectBuildTool(Path repositoryRoot) {
        if (Files.exists(repositoryRoot.resolve("build.gradle")) || Files.exists(repositoryRoot.resolve("build.gradle.kts"))) {
            return BuildTool.GRADLE;
        }
        if (Files.exists(repositoryRoot.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        return BuildTool.UNKNOWN;
    }

    private List<Path> detectBuildFiles(Path repositoryRoot) {
        List<Path> buildFiles = new ArrayList<>();
        addIfExists(buildFiles, repositoryRoot.resolve("build.gradle"));
        addIfExists(buildFiles, repositoryRoot.resolve("build.gradle.kts"));
        addIfExists(buildFiles, repositoryRoot.resolve("pom.xml"));
        addIfExists(buildFiles, repositoryRoot.resolve("settings.gradle"));
        addIfExists(buildFiles, repositoryRoot.resolve("settings.gradle.kts"));
        addIfExists(buildFiles, repositoryRoot.resolve("gradle.properties"));
        addIfExists(buildFiles, repositoryRoot.resolve("gradle/libs.versions.toml"));
        return buildFiles;
    }

    private void addIfExists(List<Path> buildFiles, Path path) {
        if (Files.exists(path)) {
            buildFiles.add(path);
        }
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read build file: " + path, exception);
        }
    }

    private boolean containsSpringBootMarker(String content) {
        return SPRING_BOOT_MARKERS.stream().anyMatch(content::contains);
    }

    private String detectJavaVersionHint(String content) {
        for (Pattern pattern : JAVA_VERSION_PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private SpringBootVersion detectSpringBootVersion(String content) {
        for (VersionPattern versionPattern : SPRING_BOOT_VERSION_PATTERNS) {
            Matcher matcher = versionPattern.pattern().matcher(content);
            if (matcher.find()) {
                return new SpringBootVersion(matcher.group(1), versionPattern.source(), versionPattern.confidence());
            }
        }
        return null;
    }

    private List<String> extractDependencies(Path buildFile, String content) {
        if (buildFile.getFileName().toString().equals("pom.xml")) {
            return extractMavenDependencies(content);
        }
        return extractGradleDependencies(content);
    }

    private List<String> extractGradleDependencies(String content) {
        List<String> dependencies = new ArrayList<>();
        Matcher matcher = GRADLE_DEPENDENCY_PATTERN.matcher(content);
        while (matcher.find()) {
            dependencies.add(matcher.group(1));
        }
        return dependencies;
    }

    private List<String> extractMavenDependencies(String content) {
        List<String> dependencies = new ArrayList<>();
        Matcher matcher = MAVEN_DEPENDENCY_PATTERN.matcher(content);
        while (matcher.find()) {
            String version = matcher.group(3);
            if (version == null || version.isBlank()) {
                dependencies.add(matcher.group(1) + ":" + matcher.group(2));
            } else {
                dependencies.add(matcher.group(1) + ":" + matcher.group(2) + ":" + version);
            }
        }
        return dependencies;
    }

    private record VersionPattern(
            Pattern pattern,
            String source,
            String confidence
    ) {
    }

    private record SpringBootVersion(
            String version,
            String source,
            String confidence
    ) {
    }
}
