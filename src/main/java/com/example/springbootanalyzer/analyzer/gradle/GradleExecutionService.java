package com.example.springbootanalyzer.analyzer.gradle;

import com.example.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleExecutionFailureType;
import com.example.springbootanalyzer.config.AnalyzerProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GradleExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GradleExecutionService.class);

    private final GradleCommandBuilder gradleCommandBuilder;
    private final GradleExecutableLocator gradleExecutableLocator;
    private final GradleJavaCompatibilityService gradleJavaCompatibilityService;
    private final GradleFailureClassifier gradleFailureClassifier;

    public GradleExecutionService(
            GradleCommandBuilder gradleCommandBuilder,
            GradleExecutableLocator gradleExecutableLocator,
            GradleJavaCompatibilityService gradleJavaCompatibilityService,
            GradleFailureClassifier gradleFailureClassifier
    ) {
        this.gradleCommandBuilder = gradleCommandBuilder;
        this.gradleExecutableLocator = gradleExecutableLocator;
        this.gradleJavaCompatibilityService = gradleJavaCompatibilityService;
        this.gradleFailureClassifier = gradleFailureClassifier;
    }

    public GradleExecutionResult execute(
            Path repositoryRoot,
            GradleExecutionMode executionMode,
            Path wrapperScript,
            String gradleVersion,
            int javaFeatureVersion,
            AnalyzerProperties.GradleProperties properties
    ) {
        return execute(repositoryRoot, executionMode, wrapperScript, gradleVersion, javaFeatureVersion, properties, null);
    }

    public GradleExecutionResult execute(
            Path repositoryRoot,
            GradleExecutionMode executionMode,
            Path wrapperScript,
            String gradleVersion,
            int javaFeatureVersion,
            AnalyzerProperties.GradleProperties properties,
            Path localPluginRepository
    ) {
        String executionLabel = GradleExecutionSupport.executionModeLabel("PROCESS", executionMode);
        Path executable = resolveExecutable(executionMode, wrapperScript, properties);
        if (executable == null) {
            return new GradleExecutionResult(
                    false,
                    false,
                    -1,
                    null,
                    null,
                    null,
                    executionLabel,
                    gradleVersion,
                    String.valueOf(javaFeatureVersion),
                    GradleExecutionFailureType.EXECUTABLE_NOT_FOUND,
                    "External Gradle fallback was skipped because no Gradle executable was configured or found on PATH.",
                    null
            );
        }
        try {
            GradleExecutionSupport.ExecutionFiles files =
                    GradleExecutionSupport.prepareExecutionFiles(repositoryRoot, properties, localPluginRepository);

            List<String> command = gradleCommandBuilder.buildCommand(
                    executable.toString(),
                    files.initScript(),
                    files.reportFile(),
                    properties.maxResolvedDependencies(),
                    properties.allowNetwork(),
                    properties
            );
            LOGGER.info(
                    "Executing Gradle diagnostic task: executionMode={}, repositoryRoot={}, reportFile={}, timeout={}, useWrapper={}",
                    executionMode,
                    repositoryRoot,
                    files.reportFile(),
                    properties.timeout(),
                    executionMode == GradleExecutionMode.WRAPPER
            );

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(repositoryRoot.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.environment().clear();
            processBuilder.environment().putAll(GradleExecutionSupport.safeEnvironment(System.getenv(), files.gradleUserHome(), properties));
            if (properties.javaHome() != null) {
                processBuilder.environment().put("JAVA_HOME", properties.javaHome().toString());
            }

            Process process = processBuilder.start();
            String output = GradleExecutionSupport.readBounded(process.getInputStream(), properties.maxOutputBytes());
            boolean finished = process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("Gradle diagnostic task timed out after {}", properties.timeout());
                return new GradleExecutionResult(
                    false,
                    true,
                    -1,
                    files.reportFile(),
                    files.initScript(),
                    GradleExecutionSupport.redact(output),
                    executionLabel,
                    gradleVersion,
                    String.valueOf(javaFeatureVersion),
                    GradleExecutionFailureType.TIMED_OUT,
                    "Gradle diagnostic task timed out.",
                    null
                );
            }

            LOGGER.info("Gradle diagnostic task exited with code {}", process.exitValue());
            return new GradleExecutionResult(
                process.exitValue() == 0,
                false,
                process.exitValue(),
                files.reportFile(),
                files.initScript(),
                GradleExecutionSupport.redact(output),
                executionLabel,
                gradleVersion,
                String.valueOf(javaFeatureVersion),
                process.exitValue() == 0
                            ? GradleExecutionFailureType.NONE
                            : GradleExecutionSupport.classifyFailure(output, gradleVersion, javaFeatureVersion, gradleJavaCompatibilityService, gradleFailureClassifier).failureType(),
                    process.exitValue() == 0 ? null : conciseErrorMessage(output, gradleVersion, javaFeatureVersion),
                    process.exitValue() == 0
                            ? null
                            : GradleExecutionSupport.classifyFailure(output, gradleVersion, javaFeatureVersion, gradleJavaCompatibilityService, gradleFailureClassifier).pluginResolutionFailure()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Gradle diagnostic task interrupted");
            LOGGER.debug("Gradle diagnostic task interrupted", exception);
            return new GradleExecutionResult(
                false,
                false,
                -1,
                null,
                null,
                GradleExecutionSupport.redact(exception.getMessage()),
                executionLabel,
                gradleVersion,
                String.valueOf(javaFeatureVersion),
                GradleExecutionFailureType.UNKNOWN,
                "Gradle diagnostic task was interrupted.",
                null
            );
        } catch (IOException exception) {
            String message = GradleExecutionSupport.redact(exception.getMessage());
            GradleFailureClassifier.ClassifiedGradleFailure classifiedFailure = GradleExecutionSupport.classifyFailure(
                    message,
                    gradleVersion,
                    javaFeatureVersion,
                    gradleJavaCompatibilityService,
                    gradleFailureClassifier
            );
            GradleExecutionFailureType failureType = classifiedFailure.failureType();
            logFailure("Failed to execute Gradle diagnostic task", failureType, message, gradleVersion, javaFeatureVersion, exception);
            return new GradleExecutionResult(
                    false,
                    false,
                    -1,
                    null,
                    null,
                    message,
                    executionLabel,
                    gradleVersion,
                    String.valueOf(javaFeatureVersion),
                    failureType,
                    conciseErrorMessage(message, gradleVersion, javaFeatureVersion),
                    classifiedFailure.pluginResolutionFailure()
            );
        }
    }

    private Path resolveExecutable(
            GradleExecutionMode executionMode,
            Path wrapperScript,
            AnalyzerProperties.GradleProperties properties
    ) {
        if (executionMode == GradleExecutionMode.WRAPPER && wrapperScript != null) {
            return wrapperScript;
        }
        return gradleExecutableLocator.findSystemGradleExecutable(properties);
    }

    private String conciseErrorMessage(String message, String gradleVersion, int javaFeatureVersion) {
        GradleExecutionFailureType failureType = GradleExecutionSupport.classifyFailure(
                message,
                gradleVersion,
                javaFeatureVersion,
                gradleJavaCompatibilityService,
                gradleFailureClassifier
        ).failureType();
        return switch (failureType) {
            case INCOMPATIBLE_JAVA_AND_GRADLE ->
                    "Diagnostic Gradle %s is not compatible with Java %d. Use Gradle 9.1.0+ or configure analyzer.gradle.java-home."
                            .formatted(gradleVersion, javaFeatureVersion);
            case EXECUTABLE_NOT_FOUND ->
                    "External Gradle fallback was skipped because no Gradle executable was configured or found on PATH.";
            case TIMED_OUT -> "Gradle diagnostic task timed out.";
            case INIT_SCRIPT_COMPILATION_FAILED ->
                    helperScopeMessage(message);
            case SETTINGS_PLUGIN_RESOLUTION_FAILED -> "Settings plugin could not be resolved before the analyzer diagnostic task could run.";
            case BUILD_LOGIC_FAILED -> "Gradle diagnostic task failed during build configuration.";
            default -> message == null || message.isBlank() ? "Gradle diagnostic task failed." : message;
        };
    }

    private void logFailure(
            String prefix,
            GradleExecutionFailureType failureType,
            String message,
            String gradleVersion,
            int javaFeatureVersion,
            Exception exception
    ) {
        if (failureType == GradleExecutionFailureType.INCOMPATIBLE_JAVA_AND_GRADLE) {
            LOGGER.warn(
                    "Gradle model analysis skipped: diagnostic Gradle {} is not compatible with Java {}. Use Gradle 9.1.0+.",
                    gradleVersion,
                    javaFeatureVersion
            );
            LOGGER.debug(prefix, exception);
            return;
        }
        if (failureType == GradleExecutionFailureType.EXECUTABLE_NOT_FOUND) {
            LOGGER.warn(message);
            LOGGER.debug(prefix, exception);
            return;
        }
        if (failureType == GradleExecutionFailureType.SETTINGS_PLUGIN_RESOLUTION_FAILED
                || failureType == GradleExecutionFailureType.PLUGIN_RESOLUTION_FAILED) {
            LOGGER.warn("Gradle diagnostic task failed during plugin resolution: {}", message);
            LOGGER.debug(prefix, exception);
            return;
        }
        if (failureType == GradleExecutionFailureType.INIT_SCRIPT_COMPILATION_FAILED) {
            LOGGER.warn("Gradle diagnostic task failed because the analyzer generated an invalid init script: {}", message);
            LOGGER.debug(prefix, exception);
            return;
        }
        LOGGER.warn(prefix);
        LOGGER.debug(prefix, exception);
    }

    private String helperScopeMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        if (normalized.contains("could not find method sanitizevalue()")
                || normalized.contains("could not find method sbasanitizevalue()")) {
            return "Gradle model analysis failed because the generated init script called helper method sanitizeValue from a Gradle task closure. This is an analyzer init-script scoping bug.";
        }
        return "Gradle model analysis failed because the analyzer generated an invalid Gradle init script. This is likely a path escaping or helper scoping issue.";
    }
}
