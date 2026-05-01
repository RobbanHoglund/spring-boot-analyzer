package com.example.springbootanalyzer.application;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class AnalysisSessionRegistry {

    private final Map<String, AnalysisSession> sessions = new ConcurrentHashMap<>();

    public void register(AnalysisSession session) {
        if (session != null && session.analysisId() != null) {
            sessions.put(session.analysisId(), session);
        }
    }

    public Optional<AnalysisSession> find(String analysisId) {
        return Optional.ofNullable(sessions.get(analysisId));
    }

    public record AnalysisSession(
            String analysisId,
            Path repositoryRoot,
            String repositoryUrl,
            String branch,
            String commitSha
    ) {
    }
}
