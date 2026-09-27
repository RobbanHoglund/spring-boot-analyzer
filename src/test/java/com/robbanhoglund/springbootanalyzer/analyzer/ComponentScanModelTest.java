package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComponentScanModelTest {

    @TempDir Path repoRoot;

    private void write(String relativePath, String content) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private ComponentScanModel model(String... mainClasses) {
        return ComponentScanModel.of(JavaSources.from(repoRoot), List.of(mainClasses));
    }

    @Test
    void scansTheApplicationPackageTree() throws IOException {
        write(
                "src/main/java/com/example/app/Application.java",
                """
                package com.example.app;

                @org.springframework.boot.autoconfigure.SpringBootApplication
                class Application {
                }
                """);

        ComponentScanModel model = model("com.example.app.Application");

        assertThat(model.isScanned("com.example.app")).isTrue();
        assertThat(model.isScanned("com.example.app.orders")).isTrue();
        assertThat(model.isScanned("com.example.application")).isFalse();
        assertThat(model.isScanned("com.example")).isFalse();
    }

    @Test
    void honoursScanBasePackagesAndScanBasePackageClasses() throws IOException {
        write(
                "src/main/java/com/example/app/Application.java",
                """
                package com.example.app;

                import com.acme.shared.SharedMarker;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication(
                        scanBasePackages = {"com.example.app", "com.example.common"},
                        scanBasePackageClasses = SharedMarker.class)
                class Application {
                }
                """);
        write(
                "src/main/java/com/acme/shared/SharedMarker.java",
                "package com.acme.shared;\n\npublic interface SharedMarker {\n}\n");

        ComponentScanModel model = model("com.example.app.Application");

        assertThat(model.isScanned("com.example.common.audit")).isTrue();
        assertThat(model.isScanned("com.acme.shared")).isTrue();
        assertThat(model.isScanned("com.acme.other")).isFalse();
    }

    @Test
    void followsComponentScanAndImportOfRegisteredConfigurationsOnly() throws IOException {
        write(
                "src/main/java/com/example/app/Application.java",
                """
                package com.example.app;

                import com.external.config.ExternalConfig;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.context.annotation.Import;

                @SpringBootApplication
                @Import(ExternalConfig.class)
                class Application {
                }
                """);
        write(
                "src/main/java/com/example/app/config/PluginConfig.java",
                """
                package com.example.app.config;

                import org.springframework.context.annotation.ComponentScan;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @ComponentScan("com.plugins")
                class PluginConfig {
                }
                """);
        write(
                "src/main/java/com/external/config/ExternalConfig.java",
                """
                package com.external.config;

                import org.springframework.context.annotation.ComponentScan;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @ComponentScan(basePackages = "com.external.beans")
                public class ExternalConfig {
                }
                """);
        write(
                "src/main/java/com/orphan/OrphanConfig.java",
                """
                package com.orphan;

                import org.springframework.context.annotation.ComponentScan;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @ComponentScan("com.never")
                class OrphanConfig {
                }
                """);

        ComponentScanModel model = model("com.example.app.Application");

        assertThat(model.isScanned("com.plugins.search")).isTrue();
        assertThat(model.isRegistered("com.external.config.ExternalConfig")).isTrue();
        assertThat(model.isScanned("com.external.beans")).isTrue();
        assertThat(model.isScanned("com.orphan")).isFalse();
        assertThat(model.isScanned("com.never")).isFalse();
    }

    @Test
    void registersAutoConfigurationEntries() throws IOException {
        write(
                "src/main/java/com/example/app/Application.java",
                """
                package com.example.app;

                @org.springframework.boot.autoconfigure.SpringBootApplication
                class Application {
                }
                """);
        write(
                "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                """
                # auto-configurations shipped by this module
                com.library.autoconfigure.ClientAutoConfiguration
                """);
        write(
                "src/main/resources/META-INF/spring.factories",
                """
                org.springframework.boot.autoconfigure.EnableAutoConfiguration=\\
                  com.library.autoconfigure.LegacyAutoConfiguration,\\
                  com.library.autoconfigure.MetricsAutoConfiguration
                """);

        ComponentScanModel model = model("com.example.app.Application");

        assertThat(model.isRegistered("com.library.autoconfigure.ClientAutoConfiguration"))
                .isTrue();
        assertThat(model.isRegistered("com.library.autoconfigure.LegacyAutoConfiguration"))
                .isTrue();
        assertThat(model.isRegistered("com.library.autoconfigure.MetricsAutoConfiguration"))
                .isTrue();
        assertThat(ComponentScanModel.autoConfigurationSimpleNames(JavaSources.from(repoRoot)))
                .containsExactlyInAnyOrder(
                        "ClientAutoConfiguration",
                        "LegacyAutoConfiguration",
                        "MetricsAutoConfiguration");
    }
}
