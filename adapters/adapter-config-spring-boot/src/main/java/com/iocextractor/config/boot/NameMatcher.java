package com.iocextractor.config.boot;

import org.springframework.boot.context.properties.source.ConfigurationPropertyName;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.iocextractor.config.boot.PropertyInspection.Status;

/** Restores schema-declared word boundaries; never chooses between ambiguous paths. */
final class NameMatcher {

    private final List<String> tokens;
    private final boolean environment;
    private final Set<String> candidates = new LinkedHashSet<>();
    private boolean unsupported;

    private NameMatcher(List<String> tokens, boolean environment) {
        this.tokens = tokens;
        this.environment = environment;
    }

    static PropertyInspection.Declaration match(String prefix, SchemaModel.Node root, String rawName,
                                                PropertyInspection.SourceReference source,
                                                boolean environment, boolean qualified) {
        ConfigurationPropertyName name = ConfigurationPropertyName.adapt(rawName, environment ? '_' : '.');
        if (name.isEmpty() || !prefix.replace("-", "").equals(name.getElement(0, ConfigurationPropertyName.Form.UNIFORM))) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i < name.getNumberOfElements(); i++) {
            String token = name.getElement(i, ConfigurationPropertyName.Form.ORIGINAL);
            tokens.add(name.chop(i + 1).isLastElementIndexed() || environment && token.matches("[0-9]+")
                    ? "[" + token + "]" : token);
        }
        NameMatcher matcher = new NameMatcher(tokens, environment);
        matcher.match(root, 0, prefix);
        Status status = !qualified || matcher.unsupported ? Status.UNSUPPORTED
                : matcher.candidates.size() > 1 ? Status.AMBIGUOUS
                : matcher.candidates.isEmpty() ? Status.UNKNOWN : Status.KNOWN;
        return new PropertyInspection.Declaration(rawName, matcher.candidates, status, source);
    }

    private void match(SchemaModel.Node node, int index, String path) {
        if (node.kind() == SchemaModel.Kind.UNSUPPORTED) {
            unsupported = true;
            candidates.add(path);
            return;
        }
        if (index == tokens.size()) {
            candidates.add(path);
            // A scalar-converted record cannot be inferred from reflection.
            unsupported |= node.kind() == SchemaModel.Kind.RECORD
                    || node.kind() == SchemaModel.Kind.LIST && node.element().kind() != SchemaModel.Kind.SCALAR;
            return;
        }
        switch (node.kind()) {
            case RECORD -> matchRecord(node, index, path);
            case LIST -> matchList(node, index, path);
            case MAP -> matchMap(index, path);
            default -> { /* Scalars have no children. */ }
        }
    }

    private void matchRecord(SchemaModel.Node node, int index, String path) {
        boolean matched = false;
        for (SchemaModel.Field field : node.fields()) {
            String expected = field.name().replace("-", "");
            StringBuilder joined = new StringBuilder();
            for (int end = index; end < tokens.size(); end++) {
                String token = tokens.get(end);
                if (token.startsWith("[")) {
                    break;
                }
                joined.append(token.replace("-", "").toLowerCase(Locale.ROOT));
                if (expected.contentEquals(joined)) {
                    match(field.node(), end + 1, path + "." + field.name());
                    matched = true;
                }
                if (!environment || joined.length() >= expected.length()) {
                    break;
                }
            }
        }
        unsupported |= !matched && node.uncertainNames();
    }

    private void matchList(SchemaModel.Node node, int index, String path) {
        String token = tokens.get(index);
        if (token.matches("\\[[0-9]+]")) {
            match(node.element(), index + 1, path + token);
        }
    }

    private void matchMap(int index, String path) {
        String token = tokens.get(index);
        String key = token.startsWith("[") && token.endsWith("]") ? token.substring(1, token.length() - 1) : token;
        if (index + 1 == tokens.size() && key.matches("[a-zA-Z0-9_-]+")) {
            candidates.add(path + "." + (environment ? key.toLowerCase(Locale.ROOT) : key));
        } else {
            unsupported = true;
        }
    }
}
