package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryReference;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class GradleSafetyPolicy {

    private final GradleJavaCompatibilityService gradleJavaCompatibilityService;

    public GradleSafetyPolicy(GradleJavaCompatibilityService gradleJavaCompatibilityService) {
        this.gradleJavaCompatibilityService = gradleJavaCompatibilityService;
    }

    public Decision decide(
            GitRepositoryReference repositoryReference,
            Path repositoryRoot,
            BuildInfo buildInfo,
            AnalyzerProperties analyzerProperties) {
        GradleExecutionMode configuredMode =
                analyzerProperties.gradle() == null
                        ? GradleExecutionMode.TOOLING_API
                        : analyzerProperties.gradle().executionMode();
        if (repositoryReference.analysisMode() == null
                || repositoryReference.analysisMode().name().equals("STATIC_ONLY")) {
            return new Decision(
                    false,
                    configuredMode,
                    "STATIC_ONLY",
                    null,
                    null,
                    runtimeJavaFeature(analyzerProperties),
                    false);
        }
        if (buildInfo.buildTool() != BuildTool.GRADLE) {
            return new Decision(
                    false,
                    configuredMode,
                    "NOT_A_GRADLE_BUILD",
                    null,
                    null,
                    runtimeJavaFeature(analyzerProperties),
                    false);
        }
        if (analyzerProperties.gradle() == null || !analyzerProperties.gradle().enabled()) {
            return new Decision(
                    false,
                    configuredMode,
                    "DISABLED",
                    null,
                    null,
                    runtimeJavaFeature(analyzerProperties),
                    false);
        }

        GradleExecutionMode executionMode =
                analyzerProperties.gradle().useWrapper()
                        ? GradleExecutionMode.WRAPPER
                        : analyzerProperties.gradle().executionMode();
        if (executionMode == GradleExecutionMode.WRAPPER) {
            Path wrapper = resolveWrapperScript(repositoryRoot);
            String wrapperVersion =
                    GradleExecutionSupport.extractWrapperGradleVersion(repositoryRoot);
            int javaFeatureVersion = runtimeJavaFeature(analyzerProperties);
            if (wrapper != null) {
                return new Decision(
                        true,
                        GradleExecutionMode.WRAPPER,
                        "ENABLED",
                        wrapper,
                        wrapperVersion,
                        javaFeatureVersion,
                        false);
            }
            return new Decision(
                    false,
                    GradleExecutionMode.WRAPPER,
                    "WRAPPER_NOT_FOUND",
                    null,
                    wrapperVersion,
                    javaFeatureVersion,
                    false);
        }

        String gradleVersion = analyzerProperties.gradle().diagnosticGradleVersion();
        return new Decision(
                true,
                executionMode,
                "ENABLED",
                null,
                gradleVersion,
                runtimeJavaFeature(analyzerProperties),
                analyzerProperties.gradle().allowSystemGradleFallback());
    }

    private int runtimeJavaFeature(AnalyzerProperties analyzerProperties) {
        return gradleJavaCompatibilityService.resolveJavaFeatureVersion(
                analyzerProperties.gradle() == null
                        ? null
                        : analyzerProperties.gradle().javaHome());
    }

    private Path resolveWrapperScript(Path repositoryRoot) {
        Path bat = repositoryRoot.resolve("gradlew.bat");
        if (Files.exists(bat)) {
            return bat;
        }
        Path sh = repositoryRoot.resolve("gradlew");
        if (Files.exists(sh)) {
            return sh;
        }
        return null;
    }

    public record Decision(
            boolean execute,
            GradleExecutionMode executionMode,
            String reason,
            Path wrapperScript,
            String gradleVersion,
            int javaFeatureVersion,
            boolean allowSystemGradleFallback) {}
}
