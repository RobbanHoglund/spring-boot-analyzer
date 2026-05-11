package com.robbanhoglund.springbootanalyzer.api;

import com.robbanhoglund.springbootanalyzer.analyzer.model.AnalysisResult;
import com.robbanhoglund.springbootanalyzer.api.dto.AnalysisMode;
import com.robbanhoglund.springbootanalyzer.api.dto.AnalyzeRepositoryCredentials;
import com.robbanhoglund.springbootanalyzer.api.dto.AnalyzeRepositoryRequest;
import com.robbanhoglund.springbootanalyzer.api.dto.AnalyzeRepositoryResponse;
import com.robbanhoglund.springbootanalyzer.api.dto.SourceSnippetResponse;
import com.robbanhoglund.springbootanalyzer.application.RepositoryAnalysisService;
import com.robbanhoglund.springbootanalyzer.application.SourceSnippetService;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryCredentials;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryReference;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisController.class);

    private final RepositoryAnalysisService repositoryAnalysisService;
    private final SourceSnippetService sourceSnippetService;

    public AnalysisController(
            RepositoryAnalysisService repositoryAnalysisService,
            SourceSnippetService sourceSnippetService) {
        this.repositoryAnalysisService = repositoryAnalysisService;
        this.sourceSnippetService = sourceSnippetService;
    }

    @PostMapping("/analyze")
    public AnalyzeRepositoryResponse analyze(@Valid @RequestBody AnalyzeRepositoryRequest request) {
        AnalysisMode analysisMode =
                request.analysisMode() == null ? AnalysisMode.STATIC_ONLY : request.analysisMode();
        LOGGER.info(
                "Analyze request received: repositoryUrl={}, branch={}, analysisMode={},"
                        + " credentialsPresent={}",
                request.repositoryUrl(),
                request.branch(),
                analysisMode,
                request.credentials() != null && request.credentials().hasToken());
        GitRepositoryCredentials credentials = mapCredentials(request.credentials());
        AnalysisResult result =
                repositoryAnalysisService.analyze(
                        new GitRepositoryReference(
                                request.repositoryUrl(),
                                request.branch(),
                                credentials,
                                analysisMode));

        return new AnalyzeRepositoryResponse(
                result.repositoryUrl(),
                result.branch(),
                result.workspaceId(),
                result.analysisId(),
                result.commitSha(),
                result.buildInfo().buildTool(),
                result.buildInfo().javaVersionHint(),
                result.buildInfo().springBootDetected(),
                result.mainApplicationClasses(),
                result.detectedComponents(),
                result.buildInfo().dependencies(),
                result.findings(),
                result.configurationAnalysis(),
                result.runtimeStackAnalysis(),
                result.httpSurfaceAnalysis(),
                result.gradleModelAnalysis(),
                result.schedulingAnalysis(),
                result.messagingAnalysis());
    }

    @GetMapping("/analyses/{analysisId}/source-snippet")
    public SourceSnippetResponse sourceSnippet(
            @PathVariable String analysisId,
            @RequestParam("path") String path,
            @RequestParam(name = "startLine", required = false) Integer startLine,
            @RequestParam(name = "endLine", required = false) Integer endLine,
            @RequestParam(name = "context", defaultValue = "4") int context) {
        return sourceSnippetService.loadSnippet(analysisId, path, startLine, endLine, context);
    }

    private GitRepositoryCredentials mapCredentials(AnalyzeRepositoryCredentials credentials) {
        if (credentials == null || !credentials.hasToken()) {
            return null;
        }
        return new GitRepositoryCredentials(credentials.username(), credentials.token());
    }
}
