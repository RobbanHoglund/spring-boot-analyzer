package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityPracticeFindingAnalyzerTest {

    @TempDir Path repoRoot;

    private SecurityPracticeFindingAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SecurityPracticeFindingAnalyzer();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void writeSourceFile(String relativePath, String content) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Finding> findings() {
        return analyzer.analyze(repoRoot);
    }

    private static Finding byRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> ruleId.equals(f.ruleId())).findFirst().orElse(null);
    }

    // ── No sources ────────────────────────────────────────────────────────────

    @Test
    void returnsEmptyListWhenNoMainDirectory() {
        assertThat(findings()).isEmpty();
    }

    // ── SPRING_CSRF_DISABLED_CODE — csrf().disable() ──────────────────────────

    @Test
    void flagsCsrfDisableChainSyntax() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.csrf().disable().authorizeRequests().anyRequest().authenticated();
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CSRF_DISABLED_CODE");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("csrf");
    }

    @Test
    void flagsCsrfWithMethodReferenceArgument() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.csrf(AbstractHttpConfigurer::disable);
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CSRF_DISABLED_CODE");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("csrf");
    }

    @Test
    void doesNotFlagFileWithNoCsrfDisable() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.authorizeRequests().anyRequest().authenticated();
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CSRF_DISABLED_CODE")).isNull();
    }

    @Test
    void doesNotFlagFileWithNoCsrfAtAll() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderService.java",
                """
                package com.example;
                public class OrderService {
                    public void save() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_CSRF_DISABLED_CODE")).isNull();
    }

    // ── SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD ─────────────────────────────────

    @Test
    void flagsPreAuthorizeOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                import org.springframework.security.access.prepost.PreAuthorize;
                public class AdminService {
                    @PreAuthorize("hasRole('ADMIN')")
                    private void deleteAll() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("AdminService#deleteAll");
        assertThat(f.message()).contains("PreAuthorize").contains("private");
    }

    @Test
    void flagsPostAuthorizeOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                import org.springframework.security.access.prepost.PostAuthorize;
                public class AdminService {
                    @PostAuthorize("returnObject.owner == authentication.name")
                    private Object loadSecret() { return null; }
                }
                """);

        Finding f = byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("PostAuthorize");
    }

    @Test
    void flagsSecuredOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                import org.springframework.security.access.annotation.Secured;
                public class AdminService {
                    @Secured("ROLE_ADMIN")
                    private void sensitiveOp() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("Secured");
    }

    @Test
    void flagsRolesAllowedOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                import jakarta.annotation.security.RolesAllowed;
                public class AdminService {
                    @RolesAllowed("ADMIN")
                    private void sensitiveOp() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("RolesAllowed");
    }

    @Test
    void doesNotFlagPreAuthorizeOnPublicMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                import org.springframework.security.access.prepost.PreAuthorize;
                public class AdminService {
                    @PreAuthorize("hasRole('ADMIN')")
                    public void deleteAll() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD")).isNull();
    }

    @Test
    void doesNotFlagPrivateMethodWithoutSecurityAnnotation() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                public class AdminService {
                    private void helper() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD")).isNull();
    }
}
