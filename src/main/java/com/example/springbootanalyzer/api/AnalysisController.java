package com.example.springbootanalyzer.api;

import com.example.springbootanalyzer.api.dto.AnalyzeRepositoryCredentials;
import com.example.springbootanalyzer.api.dto.AnalyzeRepositoryRequest;
import com.example.springbootanalyzer.api.dto.AnalyzeRepositoryResponse;
import com.example.springbootanalyzer.api.dto.AnalysisMode;
import com.example.springbootanalyzer.analyzer.model.AnalysisResult;
import com.example.springbootanalyzer.git.GitRepositoryCredentials;
import com.example.springbootanalyzer.application.RepositoryAnalysisService;
import com.example.springbootanalyzer.git.GitRepositoryReference;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisController.class);

    private final RepositoryAnalysisService repositoryAnalysisService;

    public AnalysisController(RepositoryAnalysisService repositoryAnalysisService) {
        this.repositoryAnalysisService = repositoryAnalysisService;
    }

    @PostMapping("/analyze")
    public AnalyzeRepositoryResponse analyze(@Valid @RequestBody AnalyzeRepositoryRequest request) {
        AnalysisMode analysisMode = request.analysisMode() == null ? AnalysisMode.STATIC_ONLY : request.analysisMode();
        LOGGER.info(
                "Analyze request received: repositoryUrl={}, branch={}, analysisMode={}, credentialsPresent={}",
                request.repositoryUrl(),
                request.branch(),
                analysisMode,
                request.credentials() != null && request.credentials().hasToken()
        );
        GitRepositoryCredentials credentials = mapCredentials(request.credentials());
        AnalysisResult result = repositoryAnalysisService.analyze(
                new GitRepositoryReference(
                        request.repositoryUrl(),
                        request.branch(),
                        credentials,
                        analysisMode
                )
        );

        return new AnalyzeRepositoryResponse(
                result.repositoryUrl(),
                result.branch(),
                result.workspaceId(),
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
                result.gradleModelAnalysis()
        );
    }

    private GitRepositoryCredentials mapCredentials(AnalyzeRepositoryCredentials credentials) {
        if (credentials == null || !credentials.hasToken()) {
            return null;
        }
        return new GitRepositoryCredentials(credentials.username(), credentials.token());
    }
}
