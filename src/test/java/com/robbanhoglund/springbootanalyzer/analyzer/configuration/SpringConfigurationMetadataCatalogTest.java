package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringConfigurationMetadataCatalogTest {

    private final SpringConfigurationMetadataCatalog catalog =
            new SpringConfigurationMetadataCatalog();

    @TempDir Path repoRoot;

    @Test
    void addsSpringBoot4PropertiesOnlyForSpringBoot4Projects() {
        assertThat(catalog.load(repoRoot, "4.0.6").find("spring.http.clients.connect-timeout"))
                .isNotNull();
        assertThat(catalog.load(repoRoot, "4.0.6").find("management.endpoints.access.default"))
                .isNotNull();
        assertThat(catalog.load(repoRoot, "3.5.13").find("spring.http.clients.connect-timeout"))
                .isNull();
        assertThat(catalog.load(repoRoot, null).find("spring.http.clients.connect-timeout"))
                .isNull();
    }

    @Test
    void marksKeysRenamedInSpringBoot4AsDeprecated() {
        var boot4 = catalog.load(repoRoot, "4.0.6").find("spring.http.client.connect-timeout");
        var boot35 = catalog.load(repoRoot, "3.5.13").find("spring.http.client.connect-timeout");

        assertThat(boot4.documentation().deprecated()).isTrue();
        assertThat(boot35.documentation().deprecated()).isFalse();
    }

    @Test
    void findsTheMapPropertyThatOwnsAnEntry() {
        var boot35 = catalog.load(repoRoot, "3.5.13");
        var boot4 = catalog.load(repoRoot, "4.0.6");

        assertThat(boot35.findMapOwner("spring.jackson.serialization.indent-output").name())
                .isEqualTo("spring.jackson.serialization");
        assertThat(
                        boot35.findMapOwner("spring.jackson.parser.allow-comments")
                                .documentation()
                                .deprecated())
                .isFalse();
        // Spring Boot 4 deprecates the map without repeating its type; the entry still resolves.
        var parser = boot4.findMapOwner("spring.jackson.parser.allow-comments");
        assertThat(parser.name()).isEqualTo("spring.jackson.parser");
        assertThat(parser.documentation().deprecated()).isTrue();
        assertThat(boot35.findMapOwner("server.port.extra")).isNull();
    }

    @Test
    void mergesProjectMetadataAsCustomProperties() throws IOException {
        Path metadata =
                repoRoot.resolve(
                        "src/main/resources/META-INF/additional-spring-configuration-metadata.json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(
                metadata,
                """
                {"properties": [{"name": "shop.checkout.timeout", "type": "java.time.Duration"}]}
                """);

        var property = catalog.load(repoRoot, "3.5.13").find("shop.checkout.timeout");

        assertThat(property.custom()).isTrue();
        assertThat(property.documentation().type()).isEqualTo("java.time.Duration");
    }
}
