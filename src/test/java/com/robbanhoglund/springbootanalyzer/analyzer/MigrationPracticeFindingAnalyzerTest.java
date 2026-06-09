package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationPracticeFindingAnalyzerTest {

    @TempDir Path repoRoot;

    private MigrationPracticeFindingAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new MigrationPracticeFindingAnalyzer();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void writeSourceFile(String relativePath, String content) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static RuntimeStackAnalysis boot(String version) {
        return new RuntimeStackAnalysis(version, null, null, null, null, null, null);
    }

    private List<Finding> findings(String springBootVersion) {
        return analyzer.analyze(repoRoot, boot(springBootVersion));
    }

    private static Finding byRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> ruleId.equals(f.ruleId())).findFirst().orElse(null);
    }

    // ── No sources ────────────────────────────────────────────────────────────

    @Test
    void returnsEmptyListWhenNoMainDirectory() {
        assertThat(findings("3.2.0")).isEmpty();
    }

    // ── SPRING_SECURITY_WEBSECURITYCONFIGURERADAPTER ──────────────────────────

    @Test
    void flagsWebSecurityConfigurerAdapterSubclass() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
                public class SecurityConfig extends WebSecurityConfigurerAdapter {
                }
                """);

        Finding f = byRule(findings("2.7.18"), "SPRING_SECURITY_WEBSECURITYCONFIGURERADAPTER");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("SecurityConfig");
    }

    @Test
    void doesNotFlagSecurityFilterChainBean() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.context.annotation.Bean;
                import org.springframework.security.web.SecurityFilterChain;
                public class SecurityConfig {
                    @Bean
                    SecurityFilterChain chain() { return null; }
                }
                """);

        assertThat(byRule(findings("3.2.0"), "SPRING_SECURITY_WEBSECURITYCONFIGURERADAPTER"))
                .isNull();
    }

    // ── SPRING_SECURITY_ANTMATCHERS_REMOVED ───────────────────────────────────

    @Test
    void flagsAntMatchersCall() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    void configure(HttpSecurity http) throws Exception {
                        http.authorizeRequests().antMatchers("/public").permitAll();
                    }
                }
                """);

        Finding f = byRule(findings("2.7.18"), "SPRING_SECURITY_ANTMATCHERS_REMOVED");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("antMatchers");
    }

    @Test
    void flagsMvcMatchersCall() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    void configure(HttpSecurity http) throws Exception {
                        http.authorizeRequests().mvcMatchers("/api/**").authenticated();
                    }
                }
                """);

        assertThat(byRule(findings("2.7.18"), "SPRING_SECURITY_ANTMATCHERS_REMOVED")).isNotNull();
    }

    @Test
    void doesNotFlagRequestMatchers() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    void configure(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(a -> a.requestMatchers("/public").permitAll());
                    }
                }
                """);

        assertThat(byRule(findings("3.2.0"), "SPRING_SECURITY_ANTMATCHERS_REMOVED")).isNull();
    }

    // ── SPRING_SECURITY_ENABLE_GLOBAL_METHOD_SECURITY ─────────────────────────

    @Test
    void flagsEnableGlobalMethodSecurity() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/MethodSecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
                @EnableGlobalMethodSecurity(prePostEnabled = true)
                public class MethodSecurityConfig {
                }
                """);

        Finding f = byRule(findings("2.7.18"), "SPRING_SECURITY_ENABLE_GLOBAL_METHOD_SECURITY");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("MethodSecurityConfig");
    }

    @Test
    void doesNotFlagEnableMethodSecurity() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/MethodSecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
                @EnableMethodSecurity
                public class MethodSecurityConfig {
                }
                """);

        assertThat(byRule(findings("3.2.0"), "SPRING_SECURITY_ENABLE_GLOBAL_METHOD_SECURITY"))
                .isNull();
    }

    // ── SPRING_JAKARTA_NAMESPACE_ON_BOOT3 ─────────────────────────────────────

    @Test
    void flagsLegacyJavaxImportOnBoot3() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Order.java",
                """
                package com.example;
                import javax.persistence.Entity;
                @Entity
                public class Order {
                }
                """);

        Finding f = byRule(findings("3.2.0"), "SPRING_JAKARTA_NAMESPACE_ON_BOOT3");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("javax.persistence.Entity");
    }

    @Test
    void doesNotFlagLegacyJavaxImportOnBoot2() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Order.java",
                """
                package com.example;
                import javax.persistence.Entity;
                @Entity
                public class Order {
                }
                """);

        assertThat(byRule(findings("2.7.18"), "SPRING_JAKARTA_NAMESPACE_ON_BOOT3")).isNull();
    }

    @Test
    void doesNotFlagJakartaImportOnBoot3() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Order.java",
                """
                package com.example;
                import jakarta.persistence.Entity;
                @Entity
                public class Order {
                }
                """);

        assertThat(byRule(findings("3.2.0"), "SPRING_JAKARTA_NAMESPACE_ON_BOOT3")).isNull();
    }

    @Test
    void doesNotFlagJdkJavaxImportOnBoot3() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Processor.java",
                """
                package com.example;
                import javax.annotation.processing.Generated;
                public class Processor {
                }
                """);

        assertThat(byRule(findings("3.2.0"), "SPRING_JAKARTA_NAMESPACE_ON_BOOT3")).isNull();
    }
}
