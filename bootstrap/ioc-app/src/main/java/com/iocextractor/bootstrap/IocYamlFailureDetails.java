package com.iocextractor.bootstrap;

import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * Value-free operator diagnostic extracted from a SnakeYAML parser failure.
 *
 * <p>Only numeric coordinates cross this boundary. Parser problem text can contain
 * aliases, scalar values or source fragments, even when the source line is omitted.</p>
 */
final class IocYamlFailureDetails {

    private final int line;
    private final int column;

    private IocYamlFailureDetails(int line, int column) {
        this.line = line;
        this.column = column;
    }

    static IocYamlFailureDetails from(MarkedYAMLException failure) {
        Mark mark = failure.getProblemMark();
        int line = mark == null ? -1 : mark.getLine() + 1;
        int column = mark == null ? -1 : mark.getColumn() + 1;
        return new IocYamlFailureDetails(line, column);
    }

    String description(String source) {
        String location = line > 0
                ? "%s:%d:%d".formatted(source, line, column)
                : source;
        return String.join(System.lineSeparator(),
                "CONFIG.YAML_INVALID",
                "YAML configuration is not syntactically valid.",
                "Location: " + location);
    }

    static String action() {
        return String.join(System.lineSeparator(),
                "Correct the YAML syntax before restarting the service.",
                "For an installed service, validate and atomically apply a candidate with:",
                "  sudo <prefix>/bin/ioc-config apply <candidate.yml>");
    }
}
