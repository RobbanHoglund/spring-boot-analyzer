package com.robbanhoglund.springbootanalyzer.analyzer;

/**
 * Compares Spring Boot version strings for rules whose verdict depends on the Spring Framework
 * line a project runs on.
 *
 * <p>Every comparison returns {@code false} for a missing or unparseable version. Version-gated
 * rules therefore stay silent when the version is unknown instead of guessing, which matters most
 * for rules that would otherwise report a certain startup failure.
 */
public final class SpringBootVersions {

    private SpringBootVersions() {}

    /** Returns true when {@code version} is known and at least {@code major.minor}. */
    public static boolean isAtLeast(String version, int major, int minor) {
        int[] parsed = parse(version);
        return parsed != null && (parsed[0] > major || (parsed[0] == major && parsed[1] >= minor));
    }

    /** Returns true when {@code version} is known and older than {@code major.minor}. */
    public static boolean isBefore(String version, int major, int minor) {
        int[] parsed = parse(version);
        return parsed != null && (parsed[0] < major || (parsed[0] == major && parsed[1] < minor));
    }

    /** Returns the major version, or {@code -1} when the version is missing or unparseable. */
    public static int major(String version) {
        int[] parsed = parse(version);
        return parsed == null ? -1 : parsed[0];
    }

    private static int[] parse(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String[] parts = version.trim().split("\\.");
        try {
            int major = Integer.parseInt(parts[0].replaceAll("[^0-9].*$", ""));
            int minor =
                    parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("[^0-9].*$", "")) : 0;
            return new int[] {major, minor};
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
