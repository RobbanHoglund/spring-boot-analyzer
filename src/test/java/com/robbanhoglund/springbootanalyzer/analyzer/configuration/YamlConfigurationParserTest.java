package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlConfigurationParserTest {

    @TempDir Path tempDir;

    @Test
    void reportsTheLineOfEveryPropertyAcrossDocuments() throws IOException {
        Path file = tempDir.resolve("application.yml");
        Files.writeString(
                file,
                """
                # comment
                server:
                  port: 8080
                jhipster:
                  security:
                    authentication:
                      jwt:
                        base64-secret: c2VjcmV0
                  cors:
                    allowed-origins:
                      - http://localhost:8100
                      - http://localhost:9000
                banner: |
                  multi
                  line
                ---
                spring:
                  config:
                    activate:
                      on-profile: dev
                  datasource:
                    password:
                """);

        var properties = new YamlConfigurationParser().parse(file, "application.yml", null);

        assertThat(properties)
                .extracting(
                        ParsedConfigurationProperty::name,
                        ParsedConfigurationProperty::line,
                        ParsedConfigurationProperty::profile)
                .containsExactly(
                        tuple("server.port", 3, null),
                        tuple("jhipster.security.authentication.jwt.base64-secret", 8, null),
                        tuple("jhipster.cors.allowed-origins[0]", 11, null),
                        tuple("jhipster.cors.allowed-origins[1]", 12, null),
                        tuple("banner", 13, null),
                        tuple("spring.config.activate.on-profile", 20, "dev"),
                        tuple("spring.datasource.password", 22, "dev"));
    }

    @Test
    void keepsTheLineOfTheValueThatWinsForADuplicateKey() throws IOException {
        Path file = tempDir.resolve("application.yml");
        Files.writeString(
                file,
                """
                ---
                ---
                app:
                  name: first
                  name: second
                """);

        var properties = new YamlConfigurationParser().parse(file, "application.yml", null);

        assertThat(properties)
                .extracting(ParsedConfigurationProperty::value, ParsedConfigurationProperty::line)
                .containsExactly(tuple("second", 5));
    }
}
