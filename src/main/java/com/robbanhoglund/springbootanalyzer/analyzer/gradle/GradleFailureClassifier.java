package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionFailureType;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionFailure;
import org.springframework.stereotype.Component;

@Component
public class GradleFailureClassifier {

    private final GradlePluginResolutionFailureParser pluginResolutionFailureParser;

    public GradleFailureClassifier(GradlePluginResolutionFailureParser pluginResolutionFailureParser) {
        this.pluginResolutionFailureParser = pluginResolutionFailureParser;
    }

    public ClassifiedGradleFailure classify(
            String message,
            String gradleVersion,
            int javaFeatureVersion,
            GradleJavaCompatibilityService compatibilityService
    ) {
        String normalized = message == null ? "" : message.toLowerCase();
        GradlePluginResolutionFailure pluginResolutionFailure = pluginResolutionFailureParser.parse(message);

        if (normalized.contains("cannot run program") && normalized.contains("gradle")) {
            return new ClassifiedGradleFailure(
                    GradleExecutionFailureType.EXECUTABLE_NOT_FOUND,
                    pluginResolutionFailure
            );
        }
        if (normalized.contains("unsupported class file major version")
                || normalized.contains("bug! exception in phase 'semantic analysis'")) {
            var compatibility = compatibilityService.evaluateCompatibility(javaFeatureVersion, gradleVersion);
            if (!compatibility.compatible()) {
                return new ClassifiedGradleFailure(
                        GradleExecutionFailureType.INCOMPATIBLE_JAVA_AND_GRADLE,
                        pluginResolutionFailure
                );
            }
        }
        if (normalized.contains("timed out")) {
            return new ClassifiedGradleFailure(GradleExecutionFailureType.TIMED_OUT, pluginResolutionFailure);
        }
        if (normalized.contains("could not compile initialization script")
                || normalized.contains("initialization script")
                || normalized.contains("startup failed")
                || normalized.contains("unexpected character")
                || normalized.contains("could not find method sanitizevalue()")
                || normalized.contains("could not find method sbasanitizevalue()")
                || normalized.contains("spring-boot-analyzer.init.gradle")
                || normalized.contains("unsafe windows path")) {
            return new ClassifiedGradleFailure(GradleExecutionFailureType.INIT_SCRIPT_COMPILATION_FAILED, null);
        }
        if (pluginResolutionFailure != null) {
            return new ClassifiedGradleFailure(
                    pluginResolutionFailure.settingsFile() != null
                            ? GradleExecutionFailureType.SETTINGS_PLUGIN_RESOLUTION_FAILED
                            : GradleExecutionFailureType.PLUGIN_RESOLUTION_FAILED,
                    pluginResolutionFailure
            );
        }
        if (normalized.contains("could not resolve all files for configuration")
                || normalized.contains("dependency resolution")
                || normalized.contains("could not resolve")) {
            return new ClassifiedGradleFailure(GradleExecutionFailureType.DEPENDENCY_RESOLUTION_FAILED, null);
        }
        if (normalized.contains("tooling api")
                || normalized.contains("connection")
                || normalized.contains("could not run build action")
                || normalized.contains("unable to start the daemon process")) {
            return new ClassifiedGradleFailure(GradleExecutionFailureType.TOOLING_API_TRANSPORT_FAILED, null);
        }
        return new ClassifiedGradleFailure(GradleExecutionFailureType.BUILD_LOGIC_FAILED, null);
    }

    public record ClassifiedGradleFailure(
            GradleExecutionFailureType failureType,
            GradlePluginResolutionFailure pluginResolutionFailure
    ) {
    }
}
