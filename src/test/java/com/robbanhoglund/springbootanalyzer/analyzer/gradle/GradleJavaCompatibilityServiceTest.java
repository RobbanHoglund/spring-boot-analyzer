package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GradleJavaCompatibilityServiceTest {

    private final GradleJavaCompatibilityService service = new GradleJavaCompatibilityService();

    @Test
    void java25AndGradle8143AreIncompatible() {
        assertThat(service.evaluateCompatibility(25, "8.14.3").compatible()).isFalse();
    }

    @Test
    void java25AndGradle910AreCompatible() {
        assertThat(service.evaluateCompatibility(25, "9.1.0").compatible()).isTrue();
    }

    @Test
    void java25AndGradle950AreCompatible() {
        assertThat(service.evaluateCompatibility(25, "9.5.0").compatible()).isTrue();
    }

    @Test
    void java24AndGradle8143AreCompatible() {
        assertThat(service.evaluateCompatibility(24, "8.14.3").compatible()).isTrue();
    }

    @Test
    void java21AndGradle85AreCompatible() {
        assertThat(service.evaluateCompatibility(21, "8.5").compatible()).isTrue();
    }
}
