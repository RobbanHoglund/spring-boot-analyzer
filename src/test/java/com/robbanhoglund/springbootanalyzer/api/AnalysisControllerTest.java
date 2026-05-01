package com.robbanhoglund.springbootanalyzer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.robbanhoglund.springbootanalyzer.api.dto.AnalysisMode;
import com.robbanhoglund.springbootanalyzer.api.dto.SourceSnippetResponse;
import com.robbanhoglund.springbootanalyzer.analyzer.model.AnalysisResult;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.DetectedClass;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.HighlightRange;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SpringComponentType;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.http.HttpSurfaceAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.VirtualThreadAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.WebStack;
import com.robbanhoglund.springbootanalyzer.application.RepositoryAnalysisService;
import com.robbanhoglund.springbootanalyzer.application.SourceSnippetService;
import com.robbanhoglund.springbootanalyzer.error.GlobalExceptionHandler;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryReference;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AnalysisController.class)
@Import(GlobalExceptionHandler.class)
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RepositoryAnalysisService repositoryAnalysisService;

    @MockitoBean
    private SourceSnippetService sourceSnippetService;

    @Test
    void acceptsValidAnalyzeRequest() throws Exception {
        AnalysisResult analysisResult = new AnalysisResult(
                "https://github.com/example/demo.git",
                "main",
                "workspace-123",
                "workspace-123",
                "abc123",
                buildInfo("25"),
                List.of("com.example.demo.DemoApplication"),
                List.of(new DetectedClass(
                        "com.example.demo.DemoApplication",
                        "DemoApplication",
                        "com.example.demo",
                        "src/main/java/com/example/demo/DemoApplication.java",
                        SpringComponentType.MAIN_APPLICATION,
                        List.of("SpringBootApplication")
                )),
                List.of(new Finding(FindingSeverity.INFO, "Looks good", null)),
                ConfigurationAnalysis.empty(),
                runtimeStack("25", "com.example.demo.DemoApplication"),
                HttpSurfaceAnalysis.empty(),
                GradleModelAnalysis.empty(GradleAnalysisStatus.NOT_REQUESTED, "SYSTEM_GRADLE", List.of())
        );

        given(repositoryAnalysisService.analyze(any())).willReturn(analysisResult);

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryUrl": "https://github.com/example/demo.git",
                                  "branch": "main"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryUrl").value("https://github.com/example/demo.git"))
                .andExpect(jsonPath("$.branch").value("main"))
                .andExpect(jsonPath("$.workspaceId").value("workspace-123"))
                .andExpect(jsonPath("$.analysisId").value("workspace-123"))
                .andExpect(jsonPath("$.commitSha").value("abc123"))
                .andExpect(jsonPath("$.buildTool").value("GRADLE"))
                .andExpect(jsonPath("$.javaVersionHint").value("25"))
                .andExpect(jsonPath("$.springBootDetected").value(true))
                .andExpect(jsonPath("$.mainApplicationClasses[0]").value("com.example.demo.DemoApplication"))
                .andExpect(jsonPath("$.runtimeStackAnalysis.springBootVersion").value("3.5.13"))
                .andExpect(jsonPath("$.dependencies[0]").value("org.springframework.boot:spring-boot-starter-web"))
                .andExpect(jsonPath("$.findings[0].severity").value("INFO"))
                .andExpect(jsonPath("$.credentials").doesNotExist())
                .andExpect(jsonPath("$.gradleModelAnalysis.status").value("NOT_REQUESTED"));
    }

    @Test
    void mapsCredentialsIntoRepositoryReferenceWithoutReturningThem() throws Exception {
        AnalysisResult analysisResult = new AnalysisResult(
                "https://github.com/example/private-demo.git",
                "main",
                "workspace-credentials",
                "workspace-credentials",
                null,
                buildInfo("21"),
                List.of(),
                List.of(),
                List.of(),
                ConfigurationAnalysis.empty(),
                runtimeStack("21", null),
                HttpSurfaceAnalysis.empty(),
                GradleModelAnalysis.empty(GradleAnalysisStatus.NOT_REQUESTED, "SYSTEM_GRADLE", List.of())
        );

        given(repositoryAnalysisService.analyze(any())).willReturn(analysisResult);

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryUrl": "https://github.com/example/private-demo.git",
                                  "branch": "main",
                                  "credentials": {
                                    "username": "octocat",
                                    "token": "ghp_example"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryUrl").value("https://github.com/example/private-demo.git"))
                .andExpect(jsonPath("$.credentials").doesNotExist());

        ArgumentCaptor<GitRepositoryReference> referenceCaptor = ArgumentCaptor.forClass(GitRepositoryReference.class);
        then(repositoryAnalysisService).should().analyze(referenceCaptor.capture());

        GitRepositoryReference repositoryReference = referenceCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(repositoryReference.repositoryUrl())
                .isEqualTo("https://github.com/example/private-demo.git");
        org.assertj.core.api.Assertions.assertThat(repositoryReference.branch()).isEqualTo("main");
        org.assertj.core.api.Assertions.assertThat(repositoryReference.hasCredentials()).isTrue();
        org.assertj.core.api.Assertions.assertThat(repositoryReference.credentials().username()).isEqualTo("octocat");
        org.assertj.core.api.Assertions.assertThat(repositoryReference.credentials().token()).isEqualTo("ghp_example");
        org.assertj.core.api.Assertions.assertThat(repositoryReference.analysisMode()).isEqualTo(AnalysisMode.STATIC_ONLY);
    }

    @Test
    void mapsAnalysisModeIntoRepositoryReference() throws Exception {
        AnalysisResult analysisResult = new AnalysisResult(
                "https://github.com/example/private-demo.git",
                "main",
                "workspace-credentials",
                "workspace-credentials",
                null,
                buildInfo("21"),
                List.of(),
                List.of(),
                List.of(),
                ConfigurationAnalysis.empty(),
                runtimeStack("21", null),
                HttpSurfaceAnalysis.empty(),
                GradleModelAnalysis.empty(GradleAnalysisStatus.NOT_REQUESTED, "SYSTEM_GRADLE", List.of())
        );

        given(repositoryAnalysisService.analyze(any())).willReturn(analysisResult);

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryUrl": "https://github.com/example/private-demo.git",
                                  "analysisMode": "STATIC_PLUS_GRADLE_MODEL"
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<GitRepositoryReference> referenceCaptor = ArgumentCaptor.forClass(GitRepositoryReference.class);
        then(repositoryAnalysisService).should().analyze(referenceCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(referenceCaptor.getValue().analysisMode())
                .isEqualTo(AnalysisMode.STATIC_PLUS_GRADLE_MODEL);
    }

    @Test
    void rejectsBlankRepositoryUrl() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryUrl": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.repositoryUrl").value("repositoryUrl is required"));
    }

    @Test
    void servesSourceSnippet() throws Exception {
        given(sourceSnippetService.loadSnippet("workspace-123", "src/main/java/com/example/demo/Demo.java", 10, 12, 4))
                .willReturn(new SourceSnippetResponse(
                        "src/main/java/com/example/demo/Demo.java",
                        "java",
                        6,
                        16,
                        "https://github.com/example/demo/blob/abc123/src/main/java/com/example/demo/Demo.java#L10-L12",
                        List.of(),
                        List.of(new HighlightRange(10, 12, null, null, "issue"))
                ));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/analyses/workspace-123/source-snippet")
                        .param("path", "src/main/java/com/example/demo/Demo.java")
                        .param("startLine", "10")
                        .param("endLine", "12")
                        .param("context", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filePath").value("src/main/java/com/example/demo/Demo.java"))
                .andExpect(jsonPath("$.language").value("java"))
                .andExpect(jsonPath("$.highlightRanges[0].kind").value("issue"));
    }

    @Test
    void servesSourceSnippetWithoutExactLineRange() throws Exception {
        given(sourceSnippetService.loadSnippet("workspace-123", "src/main/java/com/example/demo/Demo.java", null, null, 6))
                .willReturn(new SourceSnippetResponse(
                        "src/main/java/com/example/demo/Demo.java",
                        "java",
                        1,
                        20,
                        "https://github.com/example/demo/blob/abc123/src/main/java/com/example/demo/Demo.java",
                        List.of(),
                        List.of()
                ));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/analyses/workspace-123/source-snippet")
                        .param("path", "src/main/java/com/example/demo/Demo.java")
                        .param("context", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filePath").value("src/main/java/com/example/demo/Demo.java"))
                .andExpect(jsonPath("$.highlightRanges").isArray())
                .andExpect(jsonPath("$.highlightRanges").isEmpty());
    }

    private BuildInfo buildInfo(String javaVersion) {
        return new BuildInfo(
                BuildTool.GRADLE,
                true,
                javaVersion,
                List.of("org.springframework.boot:spring-boot-starter-web"),
                "3.5.13",
                "build.gradle plugin",
                "HIGH"
        );
    }

    private RuntimeStackAnalysis runtimeStack(String javaVersion, String mainClass) {
        return new RuntimeStackAnalysis(
                "3.5.13",
                "build.gradle plugin",
                javaVersion,
                WebStack.SERVLET_MVC,
                "Servlet web dependencies were detected in the build.",
                new VirtualThreadAnalysis(false, true, false, false, false, "Disabled", List.of()),
                mainClass
        );
    }
}
