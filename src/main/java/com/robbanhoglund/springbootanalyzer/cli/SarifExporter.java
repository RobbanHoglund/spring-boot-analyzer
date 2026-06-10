package com.robbanhoglund.springbootanalyzer.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingOccurrence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.api.dto.AnalyzeRepositoryResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an {@link AnalyzeRepositoryResponse} to a SARIF 2.1.0 JSON document.
 *
 * <p>Only the fields that downstream consumers (GitHub Code Scanning, VS Code SARIF viewer,
 * Azure DevOps) reliably use are populated. The output is intentionally lean; optional
 * SARIF extensions are omitted.
 *
 * <p>This is the server-side counterpart of the TypeScript {@code sarif.ts} module used by
 * the browser UI.
 */
public final class SarifExporter {

    private static final String SARIF_SCHEMA =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Documents/"
                    + "CommitteeSpecifications/2.1.0/sarif-schema-2.1.0.json";

    private static final String TOOL_NAME = "Spring Boot Analyzer";
    private static final String TOOL_URI = "https://github.com/RobbanHoglund/spring-boot-analyzer";
    private static final String SRCROOT = "%SRCROOT%";

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private SarifExporter() {}

    /**
     * Converts the analysis response to a pretty-printed SARIF 2.1.0 JSON string.
     *
     * @param response the completed analysis response
     * @return SARIF 2.1.0 JSON string
     */
    public static String toJson(AnalyzeRepositoryResponse response) {
        try {
            return MAPPER.writeValueAsString(buildDocument(response));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise SARIF output", e);
        }
    }

    // ---------------------------------------------------------------------------
    // Document construction
    // ---------------------------------------------------------------------------

    private static Map<String, Object> buildDocument(AnalyzeRepositoryResponse response) {
        List<Finding> findings = response.findings() == null ? List.of() : response.findings();

        // De-duplicate rules by ruleId
        Map<String, Map<String, Object>> ruleMap = new LinkedHashMap<>();
        for (Finding f : findings) {
            String ruleId = ruleId(f);
            if (!ruleMap.containsKey(ruleId)) {
                ruleMap.put(ruleId, buildRule(f));
            }
        }

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", buildDriver(ruleMap)));
        run.put("results", findings.stream().map(SarifExporter::buildResult).toList());

        if (response.repositoryUrl() != null) {
            Map<String, Object> vcp = new LinkedHashMap<>();
            vcp.put("repositoryUri", response.repositoryUrl());
            if (response.commitSha() != null) vcp.put("revisionId", response.commitSha());
            if (response.branch() != null) vcp.put("branch", response.branch());
            run.put("versionControlProvenance", List.of(vcp));
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("$schema", SARIF_SCHEMA);
        doc.put("version", "2.1.0");
        doc.put("runs", List.of(run));
        return doc;
    }

    private static Map<String, Object> buildDriver(Map<String, Map<String, Object>> ruleMap) {
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", TOOL_NAME);
        driver.put("informationUri", TOOL_URI);
        driver.put("rules", new ArrayList<>(ruleMap.values()));
        return driver;
    }

    private static Map<String, Object> buildRule(Finding f) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", ruleId(f));
        rule.put("name", camelCase(ruleId(f)));
        rule.put(
                "shortDescription",
                Map.of(
                        "text",
                        f.title() != null
                                ? f.title()
                                : (f.message() != null ? f.message() : ruleId(f))));
        if (f.whyBadPractice() != null) {
            rule.put("fullDescription", Map.of("text", f.whyBadPractice()));
        }
        rule.put("defaultConfiguration", Map.of("level", toLevel(f.severity())));

        List<String> tags = new ArrayList<>();
        if (f.category() != null) tags.add(f.category().name());
        if (f.confidence() != null) tags.add("confidence:" + f.confidence().name());
        if (f.runtimeDetection() != null)
            tags.add("runtimeDetection:" + f.runtimeDetection().name());
        if (!tags.isEmpty()) {
            rule.put("properties", Map.of("tags", tags));
        }
        return rule;
    }

    private static Map<String, Object> buildResult(Finding f) {
        String messageText = buildMessageText(f);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", ruleId(f));
        result.put("level", toLevel(f.severity()));
        result.put("message", Map.of("text", messageText));

        // Primary location
        String primaryFile =
                f.primaryLocation() != null ? f.primaryLocation().filePath() : f.sourceFile();
        int primaryStart =
                f.primaryLocation() != null
                        ? f.primaryLocation().startLine()
                        : (f.line() != null ? f.line() : 0);
        int primaryEnd = f.primaryLocation() != null ? f.primaryLocation().endLine() : primaryStart;

        Map<String, Object> physLoc = buildPhysicalLocation(primaryFile, primaryStart, primaryEnd);
        if (physLoc != null) {
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("physicalLocation", physLoc);
            if (f.target() != null) {
                loc.put("logicalLocations", List.of(Map.of("name", f.target(), "kind", "member")));
            }
            result.put("locations", List.of(loc));
        }

        // Related locations from occurrences
        if (f.occurrences() != null && !f.occurrences().isEmpty()) {
            List<Map<String, Object>> related = new ArrayList<>();
            int id = 1;
            for (FindingOccurrence occ : f.occurrences()) {
                if (occ.location() == null) continue;
                Map<String, Object> occPhys =
                        buildPhysicalLocation(
                                occ.location().filePath(),
                                occ.location().startLine(),
                                occ.location().endLine());
                if (occPhys == null) continue;
                Map<String, Object> rel = new LinkedHashMap<>();
                rel.put("id", id++);
                if (occ.message() != null) rel.put("message", Map.of("text", occ.message()));
                rel.put("physicalLocation", occPhys);
                related.add(rel);
            }
            if (!related.isEmpty()) {
                result.put("relatedLocations", related);
            }
        }

        // Extra properties
        Map<String, Object> props = new LinkedHashMap<>();
        if (f.confidence() != null) props.put("confidence", f.confidence().name());
        if (f.runtimeDetection() != null)
            props.put("runtimeDetection", f.runtimeDetection().name());
        if (f.category() != null) props.put("category", f.category().name());
        if (f.possibleImpact() != null) props.put("possibleImpact", f.possibleImpact());
        if (!props.isEmpty()) result.put("properties", props);

        return result;
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static String buildMessageText(Finding f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.message() != null ? f.message() : (f.title() != null ? f.title() : ruleId(f)));
        if (f.recommendation() != null) {
            sb.append("\n\nRecommendation: ").append(f.recommendation());
        }
        if (f.evidence() != null) {
            sb.append("\n\nEvidence: ").append(f.evidence());
        }
        return sb.toString();
    }

    private static Map<String, Object> buildPhysicalLocation(
            String filePath, int startLine, int endLine) {
        if (filePath == null || filePath.isBlank()) return null;
        String uri = filePath.replace('\\', '/');
        Map<String, Object> artifactLocation = Map.of("uri", uri, "uriBaseId", SRCROOT);

        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("artifactLocation", artifactLocation);
        if (startLine > 0) {
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("startLine", startLine);
            if (endLine >= startLine) region.put("endLine", endLine);
            loc.put("region", region);
        }
        return loc;
    }

    private static String ruleId(Finding f) {
        return f.ruleId() != null ? f.ruleId() : "UNKNOWN";
    }

    private static String toLevel(FindingSeverity severity) {
        if (severity == null) return "none";
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
        };
    }

    /** Converts SCREAMING_SNAKE_CASE rule IDs to UpperCamelCase for SARIF rule names. */
    private static String camelCase(String ruleId) {
        StringBuilder sb = new StringBuilder();
        for (String part : ruleId.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }
}
