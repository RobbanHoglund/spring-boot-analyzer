package com.robbanhoglund.springbootanalyzer.analyzer.model.runtime;

public record RuntimeStackAnalysis(
        String springBootVersion,
        String springBootVersionSource,
        String javaVersion,
        WebStack webStack,
        String webStackReason,
        VirtualThreadAnalysis virtualThreads,
        String mainClass
) {
}
