package com.robbanhoglund.springbootanalyzer.analyzer;

import com.robbanhoglund.springbootanalyzer.analyzer.JavaSourceAnalyzer.SourceAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.ConfigurationAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleModelAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.http.HttpSurfaceAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.messaging.MessagingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.model.AnalysisResult;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.DetectedClass;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SpringComponentType;
import com.robbanhoglund.springbootanalyzer.analyzer.runtime.RuntimeStackAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.scheduling.SchedulingAnalyzer;
import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Orchestrator for the full Spring Boot static analysis pipeline.
 *
 * <p>Each {@link #analyze} call runs the following stages in order:
 * <ol>
 *   <li><b>Build</b> — {@link BuildFileAnalyzer} extracts Spring Boot version, Java version,
 *       and the dependency set from build scripts.</li>
 *   <li><b>Source</b> — {@link JavaSourceAnalyzer} discovers all Spring-stereotype-annotated
 *       classes and emits any structural findings (default package, etc.).</li>
 *   <li><b>Configuration</b> — {@link ConfigurationAnalyzer} parses properties/YAML files and
 *       builds a {@link com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis}.</li>
 *   <li><b>Gradle model</b> — {@link GradleModelAnalyzer} optionally invokes Gradle tooling to
 *       resolve the dependency graph and declared plugins.</li>
 *   <li><b>Runtime stack</b> — {@link RuntimeStackAnalyzer} classifies the detected runtime
 *       stacks (web, persistence, messaging, etc.).</li>
 *   <li><b>HTTP surface</b> — {@link HttpSurfaceAnalyzer} maps exposed endpoints.</li>
 *   <li><b>Scheduling</b> — {@link SchedulingAnalyzer} identifies scheduled tasks.</li>
 *   <li><b>Messaging</b> — {@link MessagingAnalyzer} identifies messaging listeners.</li>
 *   <li><b>Finding generation</b> — three finding analyzers each contribute rule-based findings:
 *     <ul>
 *       <li>{@link StaticPracticeFindingAnalyzer} — source-code practice rules (transaction,
 *           async, exception handling, etc.)</li>
 *       <li>{@link ConfigurationFindingAnalyzer} — configuration and Gradle model rules
 *           (secrets, profile drift, actuator exposure, etc.)</li>
 *       <li>{@link ObservabilityFindingAnalyzer} — observability gaps ({@code @Timed} vs
 *           {@code @Observed}, unobserved scheduled tasks and messaging listeners)</li>
 *     </ul>
 *   </li>
 *   <li><b>Component scan validation</b> — warns when Spring components exist outside the
 *       package tree rooted at the {@code @SpringBootApplication} class.</li>
 * </ol>
 *
 * <p>All collected findings are stored in the returned {@link AnalysisResult} as an immutable
 * list. No deduplication or normalisation is performed here; that is the responsibility of
 * {@link com.robbanhoglund.springbootanalyzer.application.FindingNormalizer}.
 */
@Component
public class SpringBootProjectAnalyzer implements StaticAnalyzer {

    private final BuildFileAnalyzer buildFileAnalyzer;
    private final JavaSourceAnalyzer javaSourceAnalyzer;
    private final ConfigurationAnalyzer configurationAnalyzer;
    private final GradleModelAnalyzer gradleModelAnalyzer;
    private final RuntimeStackAnalyzer runtimeStackAnalyzer;
    private final HttpSurfaceAnalyzer httpSurfaceAnalyzer;
    private final SchedulingAnalyzer schedulingAnalyzer;
    private final MessagingAnalyzer messagingAnalyzer;
    private final StaticPracticeFindingAnalyzer staticPracticeFindingAnalyzer;
    private final ConfigurationFindingAnalyzer configurationFindingAnalyzer;
    private final ObservabilityFindingAnalyzer observabilityFindingAnalyzer;
    private final TestingPracticeFindingAnalyzer testingPracticeFindingAnalyzer;
    private final CachingPracticeFindingAnalyzer cachingPracticeFindingAnalyzer;
    private final ObservabilityGapFindingAnalyzer observabilityGapFindingAnalyzer;
    private final TransactionPracticeFindingAnalyzer transactionPracticeFindingAnalyzer;
    private final SecurityPracticeFindingAnalyzer securityPracticeFindingAnalyzer;
    private final AnalyzerProperties analyzerProperties;

    public SpringBootProjectAnalyzer(
            BuildFileAnalyzer buildFileAnalyzer,
            JavaSourceAnalyzer javaSourceAnalyzer,
            ConfigurationAnalyzer configurationAnalyzer,
            GradleModelAnalyzer gradleModelAnalyzer,
            RuntimeStackAnalyzer runtimeStackAnalyzer,
            HttpSurfaceAnalyzer httpSurfaceAnalyzer,
            SchedulingAnalyzer schedulingAnalyzer,
            MessagingAnalyzer messagingAnalyzer,
            StaticPracticeFindingAnalyzer staticPracticeFindingAnalyzer,
            ConfigurationFindingAnalyzer configurationFindingAnalyzer,
            ObservabilityFindingAnalyzer observabilityFindingAnalyzer,
            TestingPracticeFindingAnalyzer testingPracticeFindingAnalyzer,
            CachingPracticeFindingAnalyzer cachingPracticeFindingAnalyzer,
            ObservabilityGapFindingAnalyzer observabilityGapFindingAnalyzer,
            TransactionPracticeFindingAnalyzer transactionPracticeFindingAnalyzer,
            SecurityPracticeFindingAnalyzer securityPracticeFindingAnalyzer,
            AnalyzerProperties analyzerProperties) {
        this.buildFileAnalyzer = buildFileAnalyzer;
        this.javaSourceAnalyzer = javaSourceAnalyzer;
        this.configurationAnalyzer = configurationAnalyzer;
        this.gradleModelAnalyzer = gradleModelAnalyzer;
        this.runtimeStackAnalyzer = runtimeStackAnalyzer;
        this.httpSurfaceAnalyzer = httpSurfaceAnalyzer;
        this.schedulingAnalyzer = schedulingAnalyzer;
        this.messagingAnalyzer = messagingAnalyzer;
        this.staticPracticeFindingAnalyzer = staticPracticeFindingAnalyzer;
        this.configurationFindingAnalyzer = configurationFindingAnalyzer;
        this.observabilityFindingAnalyzer = observabilityFindingAnalyzer;
        this.testingPracticeFindingAnalyzer = testingPracticeFindingAnalyzer;
        this.cachingPracticeFindingAnalyzer = cachingPracticeFindingAnalyzer;
        this.observabilityGapFindingAnalyzer = observabilityGapFindingAnalyzer;
        this.transactionPracticeFindingAnalyzer = transactionPracticeFindingAnalyzer;
        this.securityPracticeFindingAnalyzer = securityPracticeFindingAnalyzer;
        this.analyzerProperties = analyzerProperties;
    }

    /**
     * Runs the full analysis pipeline against a locally cloned repository and assembles the
     * combined {@link AnalysisResult}.
     *
     * <p>The {@code workspaceId} is passed to sub-analyzers that need a stable identifier for
     * caching or logging purposes (e.g. the Gradle tooling integration).
     *
     * @param repositoryReference the remote repository reference (URL + branch/commit) used to
     *                            generate GitHub permalinks in findings
     * @param repositoryRoot      root directory of the locally checked-out repository
     * @param workspaceId         opaque workspace identifier, unique per analysis job
     * @return the fully assembled {@link AnalysisResult}; never null
     */
    @Override
    public AnalysisResult analyze(
            GitRepositoryReference repositoryReference, Path repositoryRoot, String workspaceId) {
        BuildInfo buildInfo = buildFileAnalyzer.analyze(repositoryRoot);
        SourceAnalysis sourceAnalysis = javaSourceAnalyzer.analyze(repositoryRoot);
        ConfigurationAnalyzer.Result configurationResult =
                configurationAnalyzer.analyze(repositoryRoot, buildInfo);
        GradleModelAnalyzer.Result gradleResult =
                gradleModelAnalyzer.analyze(
                        repositoryReference, repositoryRoot, buildInfo, analyzerProperties);

        List<DetectedClass> detectedClasses = sourceAnalysis.detectedClasses();
        List<Finding> findings = new ArrayList<>(sourceAnalysis.findings());
        findings.addAll(configurationResult.findings());
        findings.addAll(gradleResult.findings());

        List<String> mainApplicationClasses =
                detectedClasses.stream()
                        .filter(
                                detectedClass ->
                                        detectedClass.componentType()
                                                == SpringComponentType.MAIN_APPLICATION)
                        .map(detectedClass -> detectedClass.fullyQualifiedClassName())
                        .toList();

        addApplicationStructureFindings(detectedClasses, mainApplicationClasses, findings);
        RuntimeStackAnalyzer.Result runtimeResult =
                runtimeStackAnalyzer.analyze(
                        repositoryRoot,
                        buildInfo,
                        gradleResult.gradleModelAnalysis(),
                        configurationResult.configurationAnalysis(),
                        detectedClasses,
                        mainApplicationClasses);
        findings.addAll(runtimeResult.findings());

        HttpSurfaceAnalyzer.Result httpResult =
                httpSurfaceAnalyzer.analyze(
                        repositoryRoot,
                        configurationResult.configurationAnalysis(),
                        buildInfo,
                        runtimeResult.runtimeStackAnalysis().webStack());
        findings.addAll(httpResult.findings());
        findings.addAll(
                staticPracticeFindingAnalyzer.analyze(
                        repositoryRoot,
                        buildInfo,
                        configurationResult.configurationAnalysis(),
                        gradleResult.gradleModelAnalysis(),
                        runtimeResult.runtimeStackAnalysis(),
                        httpResult.httpSurfaceAnalysis(),
                        detectedClasses));
        findings.addAll(
                configurationFindingAnalyzer.analyze(
                        repositoryRoot,
                        buildInfo,
                        configurationResult.configurationAnalysis(),
                        gradleResult.gradleModelAnalysis()));
        findings.addAll(
                observabilityFindingAnalyzer.analyze(
                        repositoryRoot, runtimeResult.runtimeStackAnalysis()));
        findings.addAll(testingPracticeFindingAnalyzer.analyze(repositoryRoot));
        findings.addAll(cachingPracticeFindingAnalyzer.analyze(repositoryRoot));
        findings.addAll(observabilityGapFindingAnalyzer.analyze(repositoryRoot));
        findings.addAll(transactionPracticeFindingAnalyzer.analyze(repositoryRoot));
        findings.addAll(securityPracticeFindingAnalyzer.analyze(repositoryRoot));

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
                gradleResult.gradleModelAnalysis(),
                schedulingAnalyzer.analyze(repositoryRoot),
                messagingAnalyzer.analyze(repositoryRoot));
    }

    private void addApplicationStructureFindings(
            List<DetectedClass> detectedClasses,
            List<String> mainApplicationClasses,
            List<Finding> findings) {
        if (mainApplicationClasses.isEmpty()) {
            findings.add(
                    new Finding(
                            FindingSeverity.WARNING,
                            "No @SpringBootApplication class was found. The project may not be a"
                                    + " Spring Boot application or the main class may be outside"
                                    + " src/main/java.",
                            null));
            return;
        }

        if (mainApplicationClasses.size() > 1) {
            findings.add(
                    new Finding(
                            FindingSeverity.INFO,
                            "Multiple @SpringBootApplication classes were found. Review the"
                                    + " intended application entry point and component scan"
                                    + " boundaries.",
                            null));
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
            boolean underMainPackage =
                    mainPackages.stream()
                            .anyMatch(
                                    mainPackage ->
                                            detectedClass.packageName().startsWith(mainPackage));
            if (!underMainPackage) {
                findings.add(
                        new Finding(
                                FindingSeverity.WARNING,
                                "Detected component appears outside the main application package."
                                        + " This may cause component scanning issues.",
                                detectedClass.filePath()));
            }
        }
    }
}
