package com.iocextractor.bootstrap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict unknown-key preflight for the {@code ioc.*} tree.
 *
 * <p>Spring's built-in {@code ignoreUnknownFields=false} is too blunt for this
 * app: partial overlays of list elements can leave lower-precedence list tails
 * visible as unbound, while command-line properties are not always reported
 * through the same path. This preflight validates property names against the
 * current {@link IocProperties} record shape before regular binding starts.</p>
 */
final class IocUnknownConfigurationPreflight implements BeanFactoryPostProcessor, Ordered {

    private static final String PREFIX = "ioc";
    private static final String PREFIX_DOT = PREFIX + ".";
    private static final Set<Class<?>> SIMPLE_TYPES = Set.of(
            String.class,
            Boolean.class,
            Integer.class,
            Long.class,
            Double.class,
            Duration.class,
            IdStart.class);

    private final ConfigurableEnvironment environment;

    IocUnknownConfigurationPreflight(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Set<String> unknownNames = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            collectUnknownKeys(source, unknownNames);
        }
        if (!unknownNames.isEmpty()) {
            throw new UnboundConfigurationPropertiesException(properties(unknownNames));
        }
    }

    private void collectUnknownKeys(PropertySource<?> source, Set<String> unknownNames) {
        if (!(source instanceof EnumerablePropertySource<?> enumerable) || shouldSkip(source)) {
            return;
        }
        for (String rawName : enumerable.getPropertyNames()) {
            ConfigurationPropertyName name = canonicalName(rawName, source);
            if (name == null) {
                continue;
            }
            String canonical = name.toString();
            if (source instanceof SystemEnvironmentPropertySource && !isLegacyTombstone(canonical)) {
                continue;
            }
            if (canonical.startsWith(PREFIX_DOT) && !isKnownIocProperty(canonical)) {
                unknownNames.add(canonical);
            }
        }
    }

    private boolean shouldSkip(PropertySource<?> source) {
        return "configurationProperties".equals(source.getName());
    }

    private boolean isLegacyTombstone(String canonical) {
        return canonical.startsWith("ioc.lookup.")
                || (canonical.startsWith("ioc.sync.endpoints[") && canonical.endsWith(".smb.read-timeout"));
    }

    private ConfigurationPropertyName canonicalName(String rawName, PropertySource<?> source) {
        ConfigurationPropertyName name = ConfigurationPropertyName.ofIfValid(rawName);
        if (name != null) {
            return name;
        }
        char separator = source instanceof SystemEnvironmentPropertySource ? '_' : '.';
        try {
            return ConfigurationPropertyName.adapt(rawName, separator);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean isKnownIocProperty(String canonical) {
        List<String> tokens = tokenize(canonical);
        if (tokens.isEmpty() || !PREFIX.equals(tokens.getFirst())) {
            return false;
        }
        return matches(IocProperties.class, tokens, 1);
    }

    private boolean matches(Type type, List<String> tokens, int index) {
        Class<?> raw = rawClass(type);
        if (raw == null) {
            return false;
        }
        if (index >= tokens.size()) {
            return canTerminateAt(raw);
        }
        if (raw.isRecord()) {
            return matchesRecord(raw, tokens, index);
        }
        if (List.class.isAssignableFrom(raw)) {
            return matchesList(type, tokens, index);
        }
        if (Map.class.isAssignableFrom(raw)) {
            return matchesMap(tokens, index);
        }
        return false;
    }

    private boolean canTerminateAt(Class<?> raw) {
        return raw.isRecord() || List.class.isAssignableFrom(raw)
                || Map.class.isAssignableFrom(raw) || isSimple(raw);
    }

    private boolean matchesRecord(Class<?> raw, List<String> tokens, int index) {
        RecordComponent component = recordComponent(raw, tokens.get(index));
        return component != null && matches(component.getGenericType(), tokens, index + 1);
    }

    private RecordComponent recordComponent(Class<?> raw, String token) {
        for (RecordComponent component : raw.getRecordComponents()) {
            if (token.equals(kebabCase(component.getName()))) {
                return component;
            }
        }
        return null;
    }

    private boolean matchesList(Type type, List<String> tokens, int index) {
        return isIndex(tokens.get(index)) && matches(listElementType(type), tokens, index + 1);
    }

    private boolean matchesMap(List<String> tokens, int index) {
        return index + 1 == tokens.size();
    }

    private List<String> tokenize(String canonical) {
        List<String> tokens = new java.util.ArrayList<>();
        for (String part : canonical.split("\\.")) {
            int bracket = part.indexOf('[');
            if (bracket < 0) {
                tokens.add(part);
                continue;
            }
            if (bracket > 0) {
                tokens.add(part.substring(0, bracket));
            }
            int cursor = bracket;
            while (cursor >= 0 && cursor < part.length()) {
                int end = part.indexOf(']', cursor);
                if (end < 0) {
                    return List.of();
                }
                tokens.add(part.substring(cursor, end + 1));
                cursor = part.indexOf('[', end);
            }
        }
        return tokens;
    }

    private Type listElementType(Type type) {
        if (type instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1) {
            return parameterized.getActualTypeArguments()[0];
        }
        return Object.class;
    }

    private Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }

    private boolean isSimple(Class<?> raw) {
        return raw.isPrimitive() || raw.isEnum() || SIMPLE_TYPES.contains(raw);
    }

    private boolean isIndex(String token) {
        return token.length() > 2 && token.charAt(0) == '[' && token.charAt(token.length() - 1) == ']';
    }

    private String kebabCase(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isUpperCase(current)) {
                result.append('-').append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private Set<ConfigurationProperty> properties(Set<String> names) {
        Set<ConfigurationProperty> properties = new LinkedHashSet<>();
        names.stream()
                .sorted()
                .map(name -> new ConfigurationProperty(ConfigurationPropertyName.of(name), "<unknown>", null))
                .forEach(properties::add);
        return properties;
    }
}
