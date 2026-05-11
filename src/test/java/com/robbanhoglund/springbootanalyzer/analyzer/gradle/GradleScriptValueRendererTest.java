package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GradleScriptValueRendererTest {

    private final GradleScriptValueRenderer renderer = new GradleScriptValueRenderer();

    @Test
    void rendersWindowsPathAsSafeFileUriLiteral() {
        String literal =
                renderer.groovyFileUriLiteral(
                        Path.of(
                                "C:/Users/robba/AppData/Local/Temp/spring-boot-analyzer/abc/gradle-plugin-cache/m2"));

        assertThat(literal).startsWith("\"file:/");
        assertThat(literal)
                .contains(
                        "C:/Users/robba/AppData/Local/Temp/spring-boot-analyzer/abc/gradle-plugin-cache/m2");
        assertThat(literal).doesNotContain("C:\\Users");
        assertThat(literal).doesNotContain("\\AppData");
    }

    @Test
    void rendersPathWithSpacesAndDollarSafely() {
        String literal =
                renderer.groovyStringLiteral("file:///C:/Temp/spring-$boot/plugin cache/m2/");

        assertThat(literal).contains("spring-\\$boot");
        assertThat(literal).contains("plugin cache");
        assertThat(literal).startsWith("\"");
        assertThat(literal).endsWith("\"");
    }

    @Test
    void rendersUnixPathAsFileUriLiteral() {
        String literal =
                renderer.groovyStringLiteral("file:///tmp/spring-boot-analyzer/plugin-cache/m2/");

        assertThat(literal).contains("file:///tmp/spring-boot-analyzer/plugin-cache/m2");
        assertThat(literal).doesNotContain("\\");
    }
}
