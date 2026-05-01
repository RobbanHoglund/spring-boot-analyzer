package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionFailureType;
import org.junit.jupiter.api.Test;

class GradleFailureClassifierTest {

    private final GradleFailureClassifier classifier =
            new GradleFailureClassifier(new GradlePluginResolutionFailureParser());

    @Test
    void classifiesInitScriptCompilationFailure() {
        String message = """
                Could not compile initialization script 'C:/temp/spring-boot-analyzer.init.gradle'.
                startup failed:
                Unexpected character: '\\\\'
                """;

        var classified = classifier.classify(
                message,
                "9.5.0",
                25,
                new GradleJavaCompatibilityService()
        );

        assertThat(classified.failureType()).isEqualTo(GradleExecutionFailureType.INIT_SCRIPT_COMPILATION_FAILED);
    }
}
