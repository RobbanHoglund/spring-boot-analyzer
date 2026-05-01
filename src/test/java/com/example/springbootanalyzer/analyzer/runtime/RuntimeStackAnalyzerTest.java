package com.example.springbootanalyzer.analyzer.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.BuildTool;
import com.example.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.example.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.example.springbootanalyzer.analyzer.model.configuration.ConfigurationSummary;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertyDocumentation;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertyKind;
import com.example.springbootanalyzer.analyzer.model.DetectedClass;
import com.example.springbootanalyzer.analyzer.model.SpringComponentType;
import com.example.springbootanalyzer.analyzer.model.runtime.WebStack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeStackAnalyzerTest {

    private final RuntimeStackAnalyzer analyzer = new RuntimeStackAnalyzer();

    @TempDir
    Path tempDir;

    @Test
    void detectsMixedWebStackVirtualThreadsAndSchedulingHints() throws IOException {
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("RuntimeConfig.java"), """
                package com.example.demo;

                import java.util.concurrent.Executors;
                import org.springframework.scheduling.annotation.EnableScheduling;
                import org.springframework.scheduling.annotation.Scheduled;

                @EnableScheduling
                class RuntimeConfig {

                    void start() {
                        Thread.ofVirtual().start(() -> {});
                        Executors.newVirtualThreadPerTaskExecutor();
                    }

                    @Scheduled(fixedDelay = 1000)
                    void tick() {
                    }
                }
                """);

        ConfigurationAnalysis configurationAnalysis = new ConfigurationAnalysis(
                List.of(),
                List.of(
                        property("spring.threads.virtual.enabled", "true"),
                        property("spring.main.web-application-type", "reactive")
                ),
                List.of(),
                List.of(),
                new ConfigurationSummary(2, 0, 0, 0, 0, 0, List.of("default"))
        );

        BuildInfo buildInfo = new BuildInfo(
                BuildTool.GRADLE,
                true,
                "25",
                List.of(
                        "org.springframework.boot:spring-boot-starter-web",
                        "org.springframework.boot:spring-boot-starter-webflux"
                ),
                "3.5.13",
                "Gradle plugins",
                "HIGH"
        );

        var result = analyzer.analyze(
                tempDir,
                buildInfo,
                GradleModelAnalysis.empty(GradleAnalysisStatus.NOT_REQUESTED, "SYSTEM_GRADLE", List.of()),
                configurationAnalysis,
                List.of(),
                List.of("com.example.demo.DemoApplication")
        );

        assertThat(result.runtimeStackAnalysis().webStack()).isEqualTo(WebStack.REACTIVE_WEBFLUX);
        assertThat(result.runtimeStackAnalysis().virtualThreads().enabledByProperty()).isTrue();
        assertThat(result.runtimeStackAnalysis().virtualThreads().javaVersionCompatible()).isTrue();
        assertThat(result.runtimeStackAnalysis().virtualThreads().explicitVirtualThreadApiUsage()).isTrue();
        assertThat(result.findings()).extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("spring.main.keep-alive=true was not found"));
    }

    @Test
    void keepsSpringMvcReasonWhenControllersExistAndGradleModelIsPartial() throws IOException {
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("DemoController.java"), """
                package com.example.demo;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class DemoController {

                    @GetMapping("/ping")
                    String ping() {
                        return "pong";
                    }
                }
                """);

        BuildInfo buildInfo = new BuildInfo(
                BuildTool.GRADLE,
                true,
                "25",
                List.of("org.springframework.boot:spring-boot-starter-web"),
                "3.5.13",
                "Gradle plugins",
                "HIGH"
        );

        var result = analyzer.analyze(
                tempDir,
                buildInfo,
                GradleModelAnalysis.empty(GradleAnalysisStatus.PARTIAL, "TOOLING_API", List.of()),
                new ConfigurationAnalysis(List.of(), List.of(), List.of(), List.of(), new ConfigurationSummary(0, 0, 0, 0, 0, 0, List.of("default"))),
                List.of(new DetectedClass(
                        "com.example.demo.DemoController",
                        "DemoController",
                        "com.example.demo",
                        "src/main/java/com/example/demo/DemoController.java",
                        SpringComponentType.REST_CONTROLLER,
                        List.of("@RestController")
                )),
                List.of("com.example.demo.DemoApplication")
        );

        assertThat(result.runtimeStackAnalysis().webStack()).isEqualTo(WebStack.SERVLET_MVC);
        assertThat(result.runtimeStackAnalysis().webStackReason())
                .isEqualTo("Spring MVC annotations and servlet web dependency declarations were detected.");
        assertThat(result.runtimeStackAnalysis().webStackReason())
                .doesNotContain("No strong runtime stack signal");
    }

    @Test
    void ignoresScheduledMarkersInsideCommentsAndStrings() throws IOException {
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("Notes.java"), """
                package com.example.demo;

                class Notes {
                    String marker = "@Scheduled";

                    void document() {
                        // @EnableScheduling is mentioned here as documentation only.
                        String text = "Do not trigger @Scheduled detection from strings.";
                    }
                }
                """);

        BuildInfo buildInfo = new BuildInfo(
                BuildTool.GRADLE,
                true,
                "25",
                List.of("org.springframework.boot:spring-boot-starter"),
                "3.5.13",
                "Gradle plugins",
                "HIGH"
        );

        var result = analyzer.analyze(
                tempDir,
                buildInfo,
                GradleModelAnalysis.empty(GradleAnalysisStatus.NOT_REQUESTED, "SYSTEM_GRADLE", List.of()),
                new ConfigurationAnalysis(List.of(), List.of(), List.of(), List.of(), new ConfigurationSummary(0, 0, 0, 0, 0, 0, List.of("default"))),
                List.of(),
                List.of("com.example.demo.DemoApplication")
        );

        assertThat(result.runtimeStackAnalysis().virtualThreads().scheduledWorkDetected()).isFalse();
        assertThat(result.runtimeStackAnalysis().virtualThreads().evidence())
                .noneMatch(item -> item.contains("@Scheduled") || item.contains("@EnableScheduling"));
    }

    private ApplicationProperty property(String name, String value) {
        return new ApplicationProperty(
                name,
                value,
                false,
                false,
                "src/main/resources/application.properties",
                1,
                "default",
                PropertyKind.SPRING_BOOT,
                PropertyDocumentation.unknown(),
                List.of()
        );
    }
}
