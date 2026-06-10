package com.robbanhoglund.springbootanalyzer.application;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * In-memory registry mapping an analysis id to the on-disk workspace that backs its source-snippet
 * lookups. Bounded with least-recently-used eviction so a long-running server does not accumulate
 * one entry per analysis for the lifetime of the process. Evicted entries simply lose snippet
 * support; their workspaces are reclaimed independently by the scheduled cleanup task.
 */
@Component
public class AnalysisSessionRegistry {

    /** Maximum number of analysis sessions retained for source-snippet browsing. */
    static final int MAX_SESSIONS = 200;

    private final Map<String, AnalysisSession> sessions =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, AnalysisSession> eldest) {
                            return size() > MAX_SESSIONS;
                        }
                    });

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
            String commitSha) {}
}
