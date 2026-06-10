package com.robbanhoglund.springbootanalyzer.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.application.AnalysisSessionRegistry.AnalysisSession;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AnalysisSessionRegistryTest {

    private static AnalysisSession session(String id) {
        return new AnalysisSession(
                id, Path.of("/tmp", id), "https://example.com/repo.git", "main", null);
    }

    @Test
    void registersAndFindsSession() {
        AnalysisSessionRegistry registry = new AnalysisSessionRegistry();
        registry.register(session("a1"));

        assertThat(registry.find("a1")).isPresent();
        assertThat(registry.find("missing")).isEmpty();
    }

    @Test
    void evictsLeastRecentlyUsedSessionWhenCapacityExceeded() {
        AnalysisSessionRegistry registry = new AnalysisSessionRegistry();
        for (int i = 0; i < AnalysisSessionRegistry.MAX_SESSIONS; i++) {
            registry.register(session("s" + i));
        }
        // Touch the oldest entry so it is no longer the least-recently-used.
        assertThat(registry.find("s0")).isPresent();

        // One more registration must evict an entry, but not the just-accessed "s0".
        registry.register(session("overflow"));

        assertThat(registry.find("s0")).isPresent();
        assertThat(registry.find("s1")).isEmpty();
        assertThat(registry.find("overflow")).isPresent();
    }
}
