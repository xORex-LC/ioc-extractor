package com.iocextractor.bootstrap;

import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * Value-free operator diagnostic extracted from a SnakeYAML parser failure.
 *
 * <p>The source line itself is deliberately omitted because configuration may contain
 * secret placeholders or, on a misconfigured host, literal credentials.</p>
 */
final class IocYamlFailureDetails {

    private static final int MAX_PROBLEM_LENGTH = 200;

    private final int line;
    private final int column;
    private final String problem;

    private IocYamlFailureDetails(int line, int column, String problem) {
        this.line = line;
        this.column = column;
        this.problem = problem;
    }

    static IocYamlFailureDetails from(MarkedYAMLException failure) {
        Mark mark = failure.getProblemMark();
        int line = mark == null ? -1 : mark.getLine() + 1;
        int column = mark == null ? -1 : mark.getColumn() + 1;
        return new IocYamlFailureDetails(line, column, sanitize(failure.getProblem()));
    }

    String description(String source) {
        String location = line > 0
                ? "%s:%d:%d".formatted(source, line, column)
                : source;
        return String.join(System.lineSeparator(),
                "CONFIG.YAML_INVALID",
                "YAML configuration is not syntactically valid.",
                "Location: " + location,
                "Parser: " + problem);
    }

    static String action() {
        return String.join(System.lineSeparator(),
                "Correct the YAML syntax before restarting the service.",
                "For an installed service, validate and atomically apply a candidate with:",
                "  sudo <prefix>/bin/ioc-config apply <candidate.yml>");
    }

    private static String sanitize(String value) {
        String normalized = value == null || value.isBlank()
                ? "YAML parser rejected the document"
                : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= MAX_PROBLEM_LENGTH
                ? normalized
                : normalized.substring(0, MAX_PROBLEM_LENGTH) + "...";
    }
}
