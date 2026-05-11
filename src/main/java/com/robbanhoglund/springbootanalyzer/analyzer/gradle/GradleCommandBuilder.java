package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GradleCommandBuilder {

    public List<String> buildCommand(
            String executable,
            Path initScript,
            Path reportFile,
            int maxResolvedDependencies,
            boolean allowNetwork,
            AnalyzerProperties.GradleProperties properties) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--no-daemon");
        command.add("--console=plain");
        command.addAll(GradleExecutionSupport.joinJvmArgs(properties));
        if (!allowNetwork) {
            command.add("--offline");
        }
        command.add("--init-script");
        command.add(initScript.toString());
        command.add("-PsbaReportFile=" + reportFile);
        command.add("-PsbaMaxResolvedDependencies=" + maxResolvedDependencies);
        command.add("springBootAnalyzerModel");
        return command;
    }
}
