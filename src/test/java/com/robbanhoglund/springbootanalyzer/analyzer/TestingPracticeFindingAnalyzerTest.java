package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestingPracticeFindingAnalyzerTest {

    @TempDir Path repoRoot;

    private TestingPracticeFindingAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new TestingPracticeFindingAnalyzer();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void writeTestFile(String relativePath, String content) throws IOException {
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

    private List<Finding> findingsWithBootVersion(String bootVersion) {
        return analyzer.analyze(
                repoRoot,
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "21",
                        List.of(),
                        bootVersion,
                        "build.gradle plugin",
                        "HIGH"));
    }

    // ── No test sources ───────────────────────────────────────────────────────

    @Test
    void returnsEmptyListWhenNoTestDirectory() {
        assertThat(findings()).isEmpty();
    }

    // ── SPRING_TEST_SPRINGBOOTTEST_OVERUSED — controller ─────────────────────

    @Test
    void flagsSpringBootTestWithControllerFieldAsWebMvcTestCandidate() throws IOException {
        writeTestFile(
                "src/test/java/com/example/UserControllerTest.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.beans.factory.annotation.Autowired;
                @SpringBootTest
                class UserControllerTest {
                    @Autowired UserController userController;
                }
                """);

        List<Finding> results = findings();
        Finding f = byRule(results, "SPRING_TEST_SPRINGBOOTTEST_OVERUSED");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("@WebMvcTest");
        assertThat(f.target()).isEqualTo("UserControllerTest");
    }

    // ── SPRING_TEST_SPRINGBOOTTEST_OVERUSED — repository ─────────────────────

    @Test
    void flagsSpringBootTestWithRepositoryFieldAsDataJpaTestCandidate() throws IOException {
        writeTestFile(
                "src/test/java/com/example/OrderRepositoryTest.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.beans.factory.annotation.Autowired;
                @SpringBootTest
                class OrderRepositoryTest {
                    @Autowired OrderRepository orderRepository;
                }
                """);

        Finding f = byRule(findings(), "SPRING_TEST_SPRINGBOOTTEST_OVERUSED");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("@DataJpaTest");
    }

    @Test
    void doesNotFlagSpringBootTestWithNoControllerOrRepositoryField() throws IOException {
        writeTestFile(
                "src/test/java/com/example/AppIT.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.beans.factory.annotation.Autowired;
                @SpringBootTest
                class AppIT {
                    @Autowired UserService userService;
                }
                """);

        assertThat(byRule(findings(), "SPRING_TEST_SPRINGBOOTTEST_OVERUSED")).isNull();
    }

    // ── SPRING_TEST_NO_TRANSACTIONAL_ROLLBACK ─────────────────────────────────

    @Test
    void flagsIntegrationTestWithRepositoryButNoTransactional() throws IOException {
        writeTestFile(
                "src/test/java/com/example/UserIT.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.beans.factory.annotation.Autowired;
                @SpringBootTest
                class UserIT {
                    @Autowired UserRepository userRepository;
                }
                """);

        Finding f = byRule(findings(), "SPRING_TEST_NO_TRANSACTIONAL_ROLLBACK");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("UserIT");
    }

    @Test
    void doesNotFlagIntegrationTestThatHasTransactional() throws IOException {
        writeTestFile(
                "src/test/java/com/example/UserIT.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.transaction.annotation.Transactional;
                @SpringBootTest
                @Transactional
                class UserIT {
                    @Autowired UserRepository userRepository;
                }
                """);

        assertThat(byRule(findings(), "SPRING_TEST_NO_TRANSACTIONAL_ROLLBACK")).isNull();
    }

    @Test
    void doesNotFlagTestWithoutRepositoryField() throws IOException {
        writeTestFile(
                "src/test/java/com/example/ServiceIT.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.beans.factory.annotation.Autowired;
                @SpringBootTest
                class ServiceIT {
                    @Autowired UserService userService;
                }
                """);

        assertThat(byRule(findings(), "SPRING_TEST_NO_TRANSACTIONAL_ROLLBACK")).isNull();
    }

    // ── SPRING_TEST_MOCKBEAN_OVERUSE ──────────────────────────────────────────

    @Test
    void flagsTestClassWithMoreThanFiveMockBeans() throws IOException {
        writeTestFile(
                "src/test/java/com/example/HeavyTest.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.test.mock.mockito.MockBean;
                @SpringBootTest
                class HeavyTest {
                    @MockBean ServiceA a;
                    @MockBean ServiceB b;
                    @MockBean ServiceC c;
                    @MockBean ServiceD d;
                    @MockBean ServiceE e;
                    @MockBean ServiceF f;
                }
                """);

        Finding f = byRule(findings(), "SPRING_TEST_MOCKBEAN_OVERUSE");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("6");
        assertThat(f.target()).isEqualTo("HeavyTest");
    }

    @Test
    void doesNotFlagTestClassWithFiveOrFewerMockBeans() throws IOException {
        writeTestFile(
                "src/test/java/com/example/NormalTest.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.test.mock.mockito.MockBean;
                @SpringBootTest
                class NormalTest {
                    @MockBean ServiceA a;
                    @MockBean ServiceB b;
                    @MockBean ServiceC c;
                }
                """);

        assertThat(byRule(findings(), "SPRING_TEST_MOCKBEAN_OVERUSE")).isNull();
    }

    @Test
    void flagsTestClassWithMoreThanFiveMockitoBeans() throws IOException {
        // @MockitoBean (Spring Boot 3.4+ replacement for the deprecated @MockBean) fragments the
        // test-context cache the same way and must be counted too.
        writeTestFile(
                "src/test/java/com/example/BigMockitoTest.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.bean.override.mockito.MockitoBean;
                @SpringBootTest
                class BigMockitoTest {
                    @MockitoBean ServiceA a;
                    @MockitoBean ServiceB b;
                    @MockitoBean ServiceC c;
                    @MockitoBean ServiceD d;
                    @MockitoBean ServiceE e;
                    @MockitoBean ServiceF f;
                }
                """);

        Finding f = byRule(findings(), "SPRING_TEST_MOCKBEAN_OVERUSE");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("BigMockitoTest");
    }

    // ── SPRING_MOCKBEAN_DEPRECATED ────────────────────────────────────────────

    @Test
    void flagsMockBeanOnBoot34Plus() throws IOException {
        writeTestFile(
                "src/test/java/com/example/UserServiceTest.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.test.mock.mockito.MockBean;
                @SpringBootTest
                class UserServiceTest {
                    @MockBean UserRepository userRepository;
                }
                """);

        Finding f = byRule(findingsWithBootVersion("3.5.0"), "SPRING_MOCKBEAN_DEPRECATED");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("UserServiceTest");
        assertThat(f.message()).contains("3.4");
    }

    @Test
    void doesNotFlagMockBeanOnBoot2() throws IOException {
        // @MockBean is not deprecated before Spring Boot 3.4 — the rule must stay silent.
        writeTestFile(
                "src/test/java/com/example/UserServiceTest.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.test.mock.mockito.MockBean;
                @SpringBootTest
                class UserServiceTest {
                    @MockBean UserRepository userRepository;
                }
                """);

        assertThat(byRule(findingsWithBootVersion("2.7.18"), "SPRING_MOCKBEAN_DEPRECATED"))
                .isNull();
    }

    @Test
    void doesNotFlagMockitoBeanOnBoot34Plus() throws IOException {
        // The replacement annotation must not trigger the deprecation rule.
        writeTestFile(
                "src/test/java/com/example/UserServiceTest.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.bean.override.mockito.MockitoBean;
                @SpringBootTest
                class UserServiceTest {
                    @MockitoBean UserRepository userRepository;
                }
                """);

        assertThat(byRule(findingsWithBootVersion("3.5.0"), "SPRING_MOCKBEAN_DEPRECATED")).isNull();
    }

    // ── SPRING_TEST_FIXED_CLOCK_MISSING ──────────────────────────────────────

    @Test
    void flagsSpringTestThatCallsNowWithoutClock() throws IOException {
        writeTestFile(
                "src/test/java/com/example/ExpiryTest.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import java.time.LocalDateTime;
                @SpringBootTest
                class ExpiryTest {
                    void test() {
                        LocalDateTime now = LocalDateTime.now();
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_TEST_FIXED_CLOCK_MISSING");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("ExpiryTest");
    }

    @Test
    void doesNotFlagTestThatInjectsAClock() throws IOException {
        writeTestFile(
                "src/test/java/com/example/ExpiryTest.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import java.time.Clock;
                import java.time.LocalDateTime;
                @SpringBootTest
                class ExpiryTest {
                    Clock clock;
                    void test() {
                        LocalDateTime now = LocalDateTime.now(clock);
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_TEST_FIXED_CLOCK_MISSING")).isNull();
    }

    @Test
    void doesNotFlagNonSpringTestThatCallsNow() throws IOException {
        writeTestFile(
                "src/test/java/com/example/PureUnitTest.java",
                """
                package com.example;
                import java.time.LocalDateTime;
                class PureUnitTest {
                    void test() {
                        LocalDateTime now = LocalDateTime.now();
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_TEST_FIXED_CLOCK_MISSING")).isNull();
    }

    // ── SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING ────────────────────────

    @Test
    void flagsSpringBootTestWithDefaultWebEnvAndNoWebTestingFields() throws IOException {
        writeTestFile(
                "src/test/java/com/example/ServiceIT.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.beans.factory.annotation.Autowired;
                @SpringBootTest
                class ServiceIT {
                    @Autowired UserService userService;
                }
                """);

        Finding f = byRule(findings(), "SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("ServiceIT");
        assertThat(f.recommendation()).contains("NONE");
    }

    @Test
    void doesNotFlagSpringBootTestWhenWebEnvIsNone() throws IOException {
        writeTestFile(
                "src/test/java/com/example/ServiceIT.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.beans.factory.annotation.Autowired;
                @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
                class ServiceIT {
                    @Autowired UserService userService;
                }
                """);

        assertThat(byRule(findings(), "SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING")).isNull();
    }

    @Test
    void doesNotFlagSpringBootTestWhenMockMvcIsInjected() throws IOException {
        writeTestFile(
                "src/test/java/com/example/ControllerIT.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.web.servlet.MockMvc;
                import org.springframework.beans.factory.annotation.Autowired;
                @SpringBootTest
                class ControllerIT {
                    @Autowired MockMvc mockMvc;
                }
                """);

        assertThat(byRule(findings(), "SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING")).isNull();
    }

    @Test
    void doesNotFlagSpringBootTestWhenWebEnvIsRandomPort() throws IOException {
        writeTestFile(
                "src/test/java/com/example/FullIT.java",
                """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
                class FullIT {}
                """);

        assertThat(byRule(findings(), "SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING")).isNull();
    }
}
