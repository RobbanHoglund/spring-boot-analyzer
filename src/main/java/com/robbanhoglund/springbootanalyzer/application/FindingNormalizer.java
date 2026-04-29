package com.robbanhoglund.springbootanalyzer.application;

import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.RelatedFindingSignal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Normalizes raw findings before they are returned in the API response.
 *
 * When multiple rules fire on the same catch block or handler method, the more
 * specific/actionable finding is kept as the primary visible finding and the
 * others are demoted to related signals inside it. No rule match is discarded.
 */
@Component
public class FindingNormalizer {

    // Rules that fire at catch-clause level and are eligible for same-block overlap detection.
    private static final Set<String> CATCH_BLOCK_RULES = Set.of(
            "JAVA_EMPTY_CATCH_BLOCK",
            "SPRING_SWALLOWED_EXCEPTION_FALLBACK",
            "SPRING_INTERRUPTED_EXCEPTION_SWALLOWED",
            "SPRING_BROAD_FATAL_ERROR_CATCH",
            "SPRING_BROAD_EXCEPTION_SPRING_BOUNDARY",
            "SPRING_PRINT_STACK_TRACE",
            "SPRING_RAW_EXCEPTION_MESSAGE_HTTP"
    );

    // Explicit rule-id pairs that overlap at the enclosing method (target) level.
    private static final List<Set<String>> TARGET_OVERLAP_PAIRS = List.of(
            Set.of("SPRING_INTERRUPTED_EXCEPTION_SWALLOWED", "SPRING_PRINT_STACK_TRACE"),
            Set.of("SPRING_RAW_EXCEPTION_MESSAGE_HTTP", "SPRING_BROAD_EXCEPTION_HANDLER"),
            Set.of("SPRING_RAW_EXCEPTION_MESSAGE_HTTP", "SPRING_BROAD_EXCEPTION_SPRING_BOUNDARY")
    );

    // Higher number = more dominant when findings overlap.
    private static final Map<String, Integer> DOMINANCE = Map.ofEntries(
            Map.entry("SPRING_INTERRUPTED_EXCEPTION_SWALLOWED", 100),
            Map.entry("SPRING_RAW_EXCEPTION_MESSAGE_HTTP", 95),
            Map.entry("JAVA_EMPTY_CATCH_BLOCK", 90),
            Map.entry("SPRING_SWALLOWED_EXCEPTION_FALLBACK", 80),
            Map.entry("SPRING_BROAD_FATAL_ERROR_CATCH", 60),
            Map.entry("SPRING_BROAD_EXCEPTION_SPRING_BOUNDARY", 50),
            Map.entry("SPRING_BROAD_EXCEPTION_HANDLER", 45),
            Map.entry("SPRING_PRINT_STACK_TRACE", 40)
    );

    public List<Finding> normalize(List<Finding> findings) {
        int n = findings.size();
        if (n <= 1) {
            return findings;
        }

        // Union-Find: group findings that overlap with each other.
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (overlaps(findings.get(i), findings.get(j))) {
                    union(parent, i, j);
                }
            }
        }

        // Collect groups, preserving original insertion order.
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }

        List<Finding> result = new ArrayList<>(groups.size());
        for (List<Integer> group : groups.values()) {
            if (group.size() == 1) {
                result.add(findings.get(group.get(0)));
            } else {
                result.add(mergeGroup(findings, group));
            }
        }
        return List.copyOf(result);
    }

    private Finding mergeGroup(List<Finding> all, List<Integer> indices) {
        int primaryIdx = indices.stream()
                .max(Comparator.comparingInt(i -> dominanceOf(all.get(i).ruleId())))
                .orElse(indices.get(0));

        Finding primary = all.get(primaryIdx);
        List<RelatedFindingSignal> signals = indices.stream()
                .filter(i -> i != primaryIdx)
                .sorted(Comparator.comparingInt(i -> -dominanceOf(all.get(i).ruleId())))
                .map(i -> toSignal(all.get(i)))
                .toList();

        return primary.withRelatedSignals(signals);
    }

    private boolean overlaps(Finding a, Finding b) {
        return overlapsAtSameBlock(a, b) || overlapsAtSameTarget(a, b);
    }

    // Two catch-block-level findings overlap when they share the same source file and
    // the same catch-clause start line.
    private boolean overlapsAtSameBlock(Finding a, Finding b) {
        if (!isCatchBlockRule(a.ruleId()) || !isCatchBlockRule(b.ruleId())) {
            return false;
        }
        if (!sameSourceFile(a, b)) {
            return false;
        }
        if (a.primaryLocation() == null || b.primaryLocation() == null) {
            return false;
        }
        return a.primaryLocation().startLine() == b.primaryLocation().startLine();
    }

    // Two findings overlap at the method level when they share the same source file and
    // enclosing method target, and their rule-id pair is in the explicit overlap list.
    private boolean overlapsAtSameTarget(Finding a, Finding b) {
        if (!sameSourceFile(a, b)) {
            return false;
        }
        if (a.target() == null || !a.target().equals(b.target())) {
            return false;
        }
        String ruleA = a.ruleId();
        String ruleB = b.ruleId();
        if (ruleA == null || ruleB == null) {
            return false;
        }
        return TARGET_OVERLAP_PAIRS.stream()
                .anyMatch(pair -> pair.contains(ruleA) && pair.contains(ruleB));
    }

    private boolean sameSourceFile(Finding a, Finding b) {
        return a.sourceFile() != null && a.sourceFile().equals(b.sourceFile());
    }

    private boolean isCatchBlockRule(String ruleId) {
        return ruleId != null && CATCH_BLOCK_RULES.contains(ruleId);
    }

    private int dominanceOf(String ruleId) {
        return ruleId == null ? 0 : DOMINANCE.getOrDefault(ruleId, 30);
    }

    private RelatedFindingSignal toSignal(Finding finding) {
        return new RelatedFindingSignal(
                finding.ruleId(),
                finding.title(),
                finding.severity(),
                finding.confidence(),
                finding.evidence(),
                finding.primaryLocation()
        );
    }

    private int find(int[] parent, int i) {
        if (parent[i] != i) {
            parent[i] = find(parent, parent[i]);
        }
        return parent[i];
    }

    private void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }
}
