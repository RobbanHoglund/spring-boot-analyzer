package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebHandlerFindingAnalyzerTest {

    private static final String OPTIONAL_PRIMITIVE = "SPRING_OPTIONAL_PRIMITIVE_REQUEST_PARAMETER";
    private static final String MULTIPLE_BODIES = "SPRING_MULTIPLE_REQUEST_BODY";
    private static final String AMBIGUOUS_MAPPING = "SPRING_AMBIGUOUS_HANDLER_MAPPING";

    @TempDir Path repoRoot;

    private final WebHandlerFindingAnalyzer analyzer = new WebHandlerFindingAnalyzer();

    private void write(String fileName, String content) throws IOException {
        Path file = repoRoot.resolve("src/main/java/com/example").resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Finding> findings(String ruleId) {
        return analyzer.analyze(JavaSources.from(repoRoot)).stream()
                .filter(finding -> ruleId.equals(finding.ruleId()))
                .toList();
    }

    // ── SPRING_OPTIONAL_PRIMITIVE_REQUEST_PARAMETER ───────────────────────────

    @Test
    void flagsOptionalPrimitiveWithoutDefault() throws IOException {
        write(
                "SearchController.java",
                """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class SearchController {
                    @GetMapping("/search")
                    String search(
                            @RequestParam(required = false) int page,
                            @RequestParam(required = false, defaultValue = "20") int size,
                            @RequestParam(required = false) boolean exact,
                            @RequestParam(required = false) Integer offset,
                            @RequestHeader(value = "X-Limit", required = false) long limit) {
                        return "";
                    }
                }
                """);

        List<Finding> findings = findings(OPTIONAL_PRIMITIVE);

        assertThat(findings)
                .extracting(Finding::message)
                .containsExactlyInAnyOrder(
                        "@RequestParam(required = false) int page in SearchController#search cannot"
                                + " be missing — requests without it fail with 500.",
                        "@RequestHeader(required = false) long limit in SearchController#search"
                                + " cannot be missing — requests without it fail with 500.");
        assertThat(findings)
                .allSatisfy(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.primaryLocation().startLine()).isGreaterThan(10);
                        });
        assertThat(findings.get(0).recommendation()).contains("Integer");
    }

    @Test
    void ignoresOptionalPrimitivesOutsideControllers() throws IOException {
        write(
                "Client.java",
                """
                package com.example;

                import org.springframework.stereotype.Component;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestParam;

                @Component
                class Client {
                    @GetMapping("/search")
                    String search(@RequestParam(required = false) int page) {
                        return "";
                    }
                }
                """);

        assertThat(findings(OPTIONAL_PRIMITIVE)).isEmpty();
    }

    // ── SPRING_MULTIPLE_REQUEST_BODY ──────────────────────────────────────────

    @Test
    void flagsHandlerWithTwoRequestBodies() throws IOException {
        write(
                "TransferController.java",
                """
                package com.example;

                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class TransferController {
                    @PostMapping("/transfers")
                    void transfer(@RequestBody Account from, @RequestBody Account to) {
                    }

                    @PostMapping("/notes")
                    void note(@RequestBody Note note, @RequestBody(required = false) Tag tag) {
                    }

                    @PostMapping("/single")
                    void single(@RequestBody Note note) {
                    }
                }

                record Account(String iban) {
                }

                record Note(String text) {
                }

                record Tag(String name) {
                }
                """);

        List<Finding> findings = findings(MULTIPLE_BODIES);

        assertThat(findings)
                .extracting(Finding::target)
                .containsExactly("TransferController#transfer", "TransferController#note");
        assertThat(findings.get(0).message()).contains("from, to", "every call fails with 400");
        assertThat(findings.get(1).message()).contains("only the first one ever receives the body");
    }

    // ── SPRING_AMBIGUOUS_HANDLER_MAPPING ──────────────────────────────────────

    @Test
    void flagsIdenticalMappingsAcrossControllers() throws IOException {
        write(
                "OrderController.java",
                """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/orders")
                class OrderController {
                    @GetMapping("/{id}")
                    String find(String id) {
                        return id;
                    }
                }
                """);
        write(
                "LegacyOrderController.java",
                """
                package com.example;

                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class LegacyOrderController {
                    @RequestMapping(path = "api/orders/{id}", method = RequestMethod.GET)
                    String legacyFind(String id) {
                        return id;
                    }
                }
                """);

        assertThat(findings(AMBIGUOUS_MAPPING))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.message())
                                    .contains(
                                            "GET /api/orders/{id}",
                                            "LegacyOrderController#legacyFind",
                                            "OrderController#find");
                            assertThat(finding.primaryLocation().filePath())
                                    .endsWith("/OrderController.java");
                        });
    }

    @Test
    void doesNotFlagMappingsThatDifferInMethodParamsOrMediaType() throws IOException {
        write(
                "ReportController.java",
                """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/reports")
                class ReportController {
                    @GetMapping
                    String list() {
                        return "";
                    }

                    @PostMapping
                    String create() {
                        return "";
                    }

                    @GetMapping(params = "format=csv")
                    String csv() {
                        return "";
                    }

                    @GetMapping(produces = "application/pdf")
                    String pdf() {
                        return "";
                    }

                    @RequestMapping
                    String any() {
                        return "";
                    }
                }
                """);

        assertThat(findings(AMBIGUOUS_MAPPING)).isEmpty();
    }

    @Test
    void doesNotCompareProfileGuardedAbstractOrMultiApplicationControllers() throws IOException {
        write(
                "Controllers.java",
                """
                package com.example;

                import org.springframework.context.annotation.Profile;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class StatusController {
                    @GetMapping("/status")
                    String status() {
                        return "up";
                    }
                }

                @RestController
                @Profile("demo")
                class DemoStatusController {
                    @GetMapping("/status")
                    String status() {
                        return "demo";
                    }
                }

                @RestController
                abstract class BaseStatusController {
                    @GetMapping("/status")
                    String status() {
                        return "base";
                    }
                }
                """);

        assertThat(findings(AMBIGUOUS_MAPPING)).isEmpty();

        write(
                "DuplicateStatus.java",
                """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class DuplicateStatus {
                    @GetMapping("/status")
                    String status() {
                        return "dup";
                    }
                }
                """);
        assertThat(findings(AMBIGUOUS_MAPPING)).hasSize(1);

        // Two applications in one source tree usually never share a context.
        write(
                "AdminApplication.java",
                "package com.example;\n@SpringBootApplication\nclass AdminApplication {}\n");
        write(
                "ShopApplication.java",
                "package com.example;\n@SpringBootApplication\nclass ShopApplication {}\n");
        assertThat(findings(AMBIGUOUS_MAPPING)).isEmpty();
    }
}
