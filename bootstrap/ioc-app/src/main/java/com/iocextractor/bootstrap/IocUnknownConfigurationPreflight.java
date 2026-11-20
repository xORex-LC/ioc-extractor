package com.iocextractor.bootstrap;

import com.iocextractor.config.boot.PropertyInspection;
import com.iocextractor.config.boot.PropertyInspector;
import com.iocextractor.config.boot.PropertySchema;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
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
    private static final PropertySchema SCHEMA = new PropertySchema(PREFIX, IocProperties.class);

    private final ConfigurableEnvironment environment;
    private final IocEnvironmentPropertyMatcher environmentMatcher = new IocEnvironmentPropertyMatcher();

    IocUnknownConfigurationPreflight(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Map<String, String> unknownNames = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            collectUnknownKeys(source, unknownNames);
        }
        if (!unknownNames.isEmpty()) {
            throw new UnboundConfigurationPropertiesException(properties(unknownNames));
        }
    }

    private void collectUnknownKeys(PropertySource<?> source, Map<String, String> unknownNames) {
        if (IocConfigurationPropertyShape.skipInspection(source)) {
            return;
        }
        PropertyInspection inspection = PropertyInspector.inspect(SCHEMA, List.of(source));
        for (PropertyInspection.Declaration declaration : inspection.declarations()) {
            String rawName = declaration.rawName();
            if (source instanceof SystemEnvironmentPropertySource) {
                collectUnknownEnvironmentKey(rawName, unknownNames);
                continue;
            }
            ConfigurationPropertyName name = canonicalName(rawName, source);
            if (name == null) {
                continue;
            }
            String canonical = name.toString();
            // IOC retains its stricter spelling policy. Unsupported generic shapes,
            // including IdStart's service converter, keep the existing local check.
            boolean known = declaration.status() == PropertyInspection.Status.KNOWN
                    && hasLegacyCanonicalName(declaration, canonical);
            if (declaration.status() == PropertyInspection.Status.UNSUPPORTED
                    || declaration.status() == PropertyInspection.Status.KNOWN && !known) {
                known = isKnownIocProperty(canonical);
            }
            if (canonical.startsWith(PREFIX_DOT) && !known) {
                unknownNames.putIfAbsent(canonical, null);
            }
        }
    }

    private boolean hasLegacyCanonicalName(PropertyInspection.Declaration declaration, String canonical) {
        for (String candidate : declaration.canonicalNames()) {
            ConfigurationPropertyName name = canonicalName(candidate, '.');
            if (name != null && canonical.equals(name.toString())) {
                return true;
            }
        }
        return false;
    }

    private void collectUnknownEnvironmentKey(String rawName, Map<String, String> unknownNames) {
        IocEnvironmentPropertyMatcher.MatchResult result = environmentMatcher.match(rawName);
        if (!result.ioc()) {
            return;
        }
        if (result.isAmbiguous()) {
            throw new IllegalStateException("Ambiguous IOC environment configuration name '%s': %s"
                    .formatted(rawName, result.canonicalNames()));
        }
        if (result.isKnown()) {
            return;
        }
        unknownNames.putIfAbsent(environmentUnknownName(rawName), rawName);
    }

    private ConfigurationPropertyName canonicalName(String rawName, PropertySource<?> source) {
        return canonicalName(rawName, source instanceof SystemEnvironmentPropertySource ? '_' : '.');
    }

    private ConfigurationPropertyName canonicalName(String rawName, char separator) {
        ConfigurationPropertyName name = ConfigurationPropertyName.ofIfValid(rawName);
        if (name != null) {
            return name;
        }
        try {
            return ConfigurationPropertyName.adapt(rawName, separator);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String environmentUnknownName(String rawName) {
        ConfigurationPropertyName name = canonicalName(rawName, '_');
        if (name == null) {
            return rawName;
        }
        return canonicalEnvironmentName(name.toString());
    }

    private String canonicalEnvironmentName(String adapted) {
        StringBuilder result = new StringBuilder();
        for (String token : adapted.split("\\.")) {
            if (token.matches("\\d+")) {
                result.append('[').append(token).append(']');
            } else if (result.isEmpty()) {
                result.append(token);
            } else {
                result.append('.').append(token);
            }
        }
        return result.toString();
    }

    private boolean isKnownIocProperty(String canonical) {
        List<String> tokens = IocConfigurationPropertyShape.tokens(canonical);
        if (tokens.isEmpty() || !PREFIX.equals(tokens.getFirst())) {
            return false;
        }
        return matches(IocProperties.class, tokens, 1);
    }

    private boolean matches(Type type, List<String> tokens, int index) {
        Class<?> raw = IocConfigurationPropertyShape.rawClass(type);
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

    static boolean canTerminateAt(Class<?> raw) {
        return raw != null;
    }

    private boolean matchesRecord(Class<?> raw, List<String> tokens, int index) {
        RecordComponent component = recordComponent(raw, tokens.get(index));
        return component != null && matches(component.getGenericType(), tokens, index + 1);
    }

    private RecordComponent recordComponent(Class<?> raw, String token) {
        for (RecordComponent component : raw.getRecordComponents()) {
            if (token.equals(IocConfigurationPropertyShape.kebabCase(component.getName()))) {
                return component;
            }
        }
        return null;
    }

    private boolean matchesList(Type type, List<String> tokens, int index) {
        return IocConfigurationPropertyShape.isIndex(tokens.get(index))
                && matches(IocConfigurationPropertyShape.listElementType(type), tokens, index + 1);
    }

    private boolean matchesMap(List<String> tokens, int index) {
        return index + 1 == tokens.size();
    }


    private Set<ConfigurationProperty> properties(Map<String, String> names) {
        Set<ConfigurationProperty> properties = new LinkedHashSet<>();
        names.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ConfigurationProperty(
                        ConfigurationPropertyName.of(entry.getKey()),
                        entry.getValue() == null ? "<unknown>"
                                : new IocConfigurationFailureAnalyzer.EnvironmentName(entry.getValue()),
                        null))
                .forEach(properties::add);
        return properties;
    }
}
