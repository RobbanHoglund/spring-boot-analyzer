package com.example.springbootanalyzer.analyzer;

import com.example.springbootanalyzer.analyzer.JavaSourceAnalyzer.SourceAnalysis;
import com.example.springbootanalyzer.analyzer.configuration.ConfigurationAnalyzer;
import com.example.springbootanalyzer.analyzer.gradle.GradleModelAnalyzer;
import com.example.springbootanalyzer.analyzer.http.HttpSurfaceAnalyzer;
import com.example.springbootanalyzer.analyzer.model.AnalysisResult;
import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.DetectedClass;
import com.example.springbootanalyzer.analyzer.model.Finding;
import com.example.springbootanalyzer.analyzer.model.FindingSeverity;
import com.example.springbootanalyzer.analyzer.model.SpringComponentType;
import com.example.springbootanalyzer.analyzer.runtime.RuntimeStackAnalyzer;
import com.example.springbootanalyzer.config.AnalyzerProperties;
import com.example.springbootanalyzer.git.GitRepositoryReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SpringBootProjectAnalyzer implements StaticAnalyzer {

    private final BuildFileAnalyzer buildFileAnalyzer;
    private final JavaSourceAnalyzer javaSourceAnalyzer;
    private final ConfigurationAnalyzer configurationAnalyzer;
    private final GradleModelAnalyzer gradleModelAnalyzer;
    private final RuntimeStackAnalyzer runtimeStackAnalyzer;
    private final HttpSurfaceAnalyzer httpSurfaceAnalyzer;
    private final StaticPracticeFindingAnalyzer staticPracticeFindingAnalyzer;
    private final AnalyzerProperties analyzerProperties;

    public SpringBootProjectAnalyzer(
            BuildFileAnalyzer buildFileAnalyzer,
            JavaSourceAnalyzer javaSourceAnalyzer,
            ConfigurationAnalyzer configurationAnalyzer,
            GradleModelAnalyzer gradleModelAnalyzer,
            RuntimeStackAnalyzer runtimeStackAnalyzer,
            HttpSurfaceAnalyzer httpSurfaceAnalyzer,
            StaticPracticeFindingAnalyzer staticPracticeFindingAnalyzer,
            AnalyzerProperties analyzerProperties
    ) {
        this.buildFileAnalyzer = buildFileAnalyzer;
        this.javaSourceAnalyzer = javaSourceAnalyzer;
        this.configurationAnalyzer = configurationAnalyzer;
        this.gradleModelAnalyzer = gradleModelAnalyzer;
        this.runtimeStackAnalyzer = runtimeStackAnalyzer;
        this.httpSurfaceAnalyzer = httpSurfaceAnalyzer;
        this.staticPracticeFindingAnalyzer = staticPracticeFindingAnalyzer;
        this.analyzerProperties = analyzerProperties;
    }

    @Override
    public AnalysisResult analyze(GitRepositoryReference repositoryReference, Path repositoryRoot, String workspaceId) {
        BuildInfo buildInfo = buildFileAnalyzer.analyze(repositoryRoot);
        SourceAnalysis sourceAnalysis = javaSourceAnalyzer.analyze(repositoryRoot);
        ConfigurationAnalyzer.Result configurationResult = configurationAnalyzer.analyze(repositoryRoot, buildInfo);
        GradleModelAnalyzer.Result gradleResult = gradleModelAnalyzer.analyze(
                repositoryReference,
                repositoryRoot,
                buildInfo,
                analyzerProperties
        );

        List<DetectedClass> detectedClasses = sourceAnalysis.detectedClasses();
        List<Finding> findings = new ArrayList<>(sourceAnalysis.findings());
        findings.addAll(configurationResult.findings());
        findings.addAll(gradleResult.findings());

        List<String> mainApplicationClasses = detectedClasses.stream()
                .filter(detectedClass -> detectedClass.componentType() == SpringComponentType.MAIN_APPLICATION)
                .map(detectedClass -> detectedClass.fullyQualifiedClassName())
                .toList();

        addApplicationStructureFindings(detectedClasses, mainApplicationClasses, findings);
        RuntimeStackAnalyzer.Result runtimeResult = runtimeStackAnalyzer.analyze(
                repositoryRoot,
                buildInfo,
                gradleResult.gradleModelAnalysis(),
                configurationResult.configurationAnalysis(),
                detectedClasses,
                mainApplicationClasses
        );
        findings.addAll(runtimeResult.findings());

        HttpSurfaceAnalyzer.Result httpResult = httpSurfaceAnalyzer.analyze(
                repositoryRoot,
                configurationResult.configurationAnalysis(),
                buildInfo,
                runtimeResult.runtimeStackAnalysis().webStack()
        );
        findings.addAll(httpResult.findings());
        findings.addAll(staticPracticeFindingAnalyzer.analyze(
                repositoryRoot,
                buildInfo,
                configurationResult.configurationAnalysis(),
                gradleResult.gradleModelAnalysis(),
                runtimeResult.runtimeStackAnalysis(),
                httpResult.httpSurfaceAnalysis(),
                detectedClasses
        ));

        return new AnalysisResult(
                repositoryReference.repositoryUrl(),
                repositoryReference.branch(),
                workspaceId,
                workspaceId,
                null,
                buildInfo,
                mainApplicationClasses,
                detectedClasses,
                List.copyOf(findings),
                configurationResult.configurationAnalysis(),
                runtimeResult.runtimeStackAnalysis(),
                httpResult.httpSurfaceAnalysis(),
                gradleResult.gradleModelAnalysis()
        );
    }

    private void addApplicationStructureFindings(
            List<DetectedClass> detectedClasses,
            List<String> mainApplicationClasses,
            List<Finding> findings
    ) {
        if (mainApplicationClasses.isEmpty()) {
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "No @SpringBootApplication class was found. The project may not be a Spring Boot application or the main class may be outside src/main/java.",
                    null
            ));
            return;
        }

        if (mainApplicationClasses.size() > 1) {
            findings.add(new Finding(
                    FindingSeverity.INFO,
                    "Multiple @SpringBootApplication classes were found. Review the intended application entry point and component scan boundaries.",
                    null
            ));
        }

        Set<String> mainPackages = new LinkedHashSet<>();
        for (String mainApplicationClass : mainApplicationClasses) {
            int separatorIndex = mainApplicationClass.lastIndexOf('.');
            if (separatorIndex > 0) {
                mainPackages.add(mainApplicationClass.substring(0, separatorIndex));
            }
        }

        for (DetectedClass detectedClass : detectedClasses) {
            if (detectedClass.componentType() == SpringComponentType.MAIN_APPLICATION) {
                continue;
            }
            if (detectedClass.packageName() == null || detectedClass.packageName().isBlank()) {
                continue;
            }
            boolean underMainPackage = mainPackages.stream()
                    .anyMatch(mainPackage -> detectedClass.packageName().startsWith(mainPackage));
            if (!underMainPackage) {
                findings.add(new Finding(
                        FindingSeverity.WARNING,
                        "Detected component appears outside the main application package. This may cause component scanning issues.",
                        detectedClass.filePath()
                ));
            }
        }
    }
}
