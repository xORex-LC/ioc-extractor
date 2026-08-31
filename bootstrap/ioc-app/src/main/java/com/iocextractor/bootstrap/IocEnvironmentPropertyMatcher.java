package com.iocextractor.bootstrap;

import org.springframework.boot.context.properties.source.ConfigurationPropertyName;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches a raw environment variable to the finite {@link IocProperties} shape.
 *
 * <p>Spring Boot's environment-name adaptation intentionally loses kebab-case
 * word boundaries. This matcher restores only those boundaries that are already
 * declared by the configuration record tree; it does not parse values or try to
 * emulate the binder.</p>
 */
final class IocEnvironmentPropertyMatcher {

    private static final String PREFIX = "ioc";

    private final Class<?> rootType;

    IocEnvironmentPropertyMatcher() {
        this(IocProperties.class);
    }

    IocEnvironmentPropertyMatcher(Class<?> rootType) {
        this.rootType = rootType;
    }

    MatchResult match(String rawName) {
        ConfigurationPropertyName adapted = adaptedName(rawName);
        if (adapted == null) {
            return MatchResult.notIoc();
        }
        List<String> tokens = IocConfigurationPropertyShape.environmentTokens(adapted.toString());
        if (tokens.size() == 1 || !PREFIX.equals(tokens.getFirst())) {
            return MatchResult.notIoc();
        }
        Set<String> canonicalNames = new LinkedHashSet<>();
        match(rootType, tokens, 1, PREFIX, canonicalNames);
        return new MatchResult(true, canonicalNames);
    }

    private ConfigurationPropertyName adaptedName(String rawName) {
        try {
            return ConfigurationPropertyName.adapt(rawName, '_');
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void match(Type type, List<String> tokens, int index, String path, Set<String> matches) {
        Class<?> raw = IocConfigurationPropertyShape.rawClass(type);
        if (raw == null) {
            return;
        }
        if (index == tokens.size()) {
            if (!raw.isRecord() && !List.class.isAssignableFrom(raw) && !Map.class.isAssignableFrom(raw)) {
                matches.add(path);
            }
            return;
        }
        if (raw.isRecord()) {
            matchRecord(raw, tokens, index, path, matches);
        } else if (List.class.isAssignableFrom(raw)) {
            matchList(type, tokens, index, path, matches);
        } else if (Map.class.isAssignableFrom(raw) && index + 1 == tokens.size()) {
            matches.add(path + "." + tokens.get(index));
        }
    }

    private void matchRecord(Class<?> raw, List<String> tokens, int index, String path, Set<String> matches) {
        for (RecordComponent component : raw.getRecordComponents()) {
            String componentName = IocConfigurationPropertyShape.kebabCase(component.getName());
            List<String> componentTokens = List.of(componentName.split("-"));
            if (startsWith(tokens, index, componentTokens)) {
                match(component.getGenericType(), tokens, index + componentTokens.size(),
                        path + "." + componentName, matches);
            }
        }
    }

    private boolean startsWith(List<String> tokens, int index, List<String> expected) {
        if (index + expected.size() > tokens.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(tokens.get(index + i))) {
                return false;
            }
        }
        return true;
    }

    private void matchList(Type type, List<String> tokens, int index, String path, Set<String> matches) {
        String token = tokens.get(index);
        if (IocConfigurationPropertyShape.isIndex(token)) {
            match(IocConfigurationPropertyShape.listElementType(type), tokens, index + 1, path + token, matches);
        }
    }

    record MatchResult(boolean ioc, Set<String> canonicalNames) {

        static MatchResult notIoc() {
            return new MatchResult(false, Set.of());
        }

        boolean isKnown() {
            return !canonicalNames.isEmpty();
        }

        boolean isAmbiguous() {
            return canonicalNames.size() > 1;
        }
    }
}
