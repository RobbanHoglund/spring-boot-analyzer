package com.robbanhoglund.springbootanalyzer.analyzer.model;

public record RelatedFindingSignal(
        String ruleId,
        String title,
        FindingSeverity severity,
        FindingConfidence confidence,
        String evidence,
        SourceLocation sourceLocation
) {

    public RelatedFindingSignal withGithubUrl(String githubUrl) {
        return new RelatedFindingSignal(
                ruleId,
                title,
                severity,
                confidence,
                evidence,
                sourceLocation == null ? null : sourceLocation.withGithubUrl(githubUrl)
        );
    }
}
