package com.iocextractor.config.boot;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately small grammar: record fields and explicit numeric-list placeholders. */
final class MigrationPath {

    private static final Pattern TEMPLATE = Pattern.compile(
            "[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:\\[\\])?)+");

    private final String value;
    private final List<String> segments;
    private final Pattern concrete;

    MigrationPath(String value) {
        if (!TEMPLATE.matcher(value).matches()) {
            throw new IllegalArgumentException("Expected a canonical migration path with optional [] indexes");
        }
        this.value = value;
        this.segments = List.of(value.split("\\."));
        this.concrete = Pattern.compile(String.join("\\.", segments.stream()
                .map(segment -> Pattern.quote(segment.replace("[]", ""))
                        + (segment.endsWith("[]") ? "\\[([0-9]+)]" : "")).toList()));
    }

    String prefix() {
        return segments.getFirst();
    }

    String relaxed() {
        return value.replace("-", "");
    }

    String environmentShape() {
        return relaxed().replace(".", "");
    }

    boolean contains(MigrationPath other) {
        return other.relaxed().startsWith(relaxed() + ".") || other.relaxed().startsWith(relaxed() + "[");
    }

    void validateReplacement(MigrationPath replacement) {
        if (!prefix().equals(replacement.prefix()) || concrete.matcher("").groupCount()
                != replacement.concrete.matcher("").groupCount() || relaxed().equals(replacement.relaxed())) {
            throw new IllegalArgumentException("Replacement must change the path within its namespace and preserve indexes");
        }
    }

    boolean matches(String path) {
        return concrete.matcher(path).matches();
    }

    String replacement(String path, String template) {
        Matcher matcher = concrete.matcher(path);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Declaration does not match migration path");
        }
        String result = template;
        for (int i = 1; i <= matcher.groupCount(); i++) {
            int position = result.indexOf("[]");
            result = result.substring(0, position) + "[" + matcher.group(i) + "]" + result.substring(position + 2);
        }
        return result;
    }

    SchemaModel.Node root() {
        SchemaModel.Node node = new SchemaModel.Node(SchemaModel.Kind.SCALAR, List.of(), null, false);
        for (int i = segments.size() - 1; i > 0; i--) {
            String segment = segments.get(i);
            if (segment.endsWith("[]")) {
                node = new SchemaModel.Node(SchemaModel.Kind.LIST, List.of(), node, false);
            }
            node = new SchemaModel.Node(SchemaModel.Kind.RECORD,
                    List.of(new SchemaModel.Field(segment.replace("[]", ""), node)), null, false);
        }
        return node;
    }
}
