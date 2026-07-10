package com.iocextractor.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports effective external {@code ioc.*} overrides after context refresh.
 *
 * <p>Only property names and winning source labels are logged. Values may carry
 * credentials or other deployment secrets and must never enter this report.</p>
 */
@Component
final class IocConfigurationOverrideReporter {

    private static final Logger log = LoggerFactory.getLogger(IocConfigurationOverrideReporter.class);
    private static final String PREFIX_DOT = "ioc.";

    private final ConfigurableEnvironment environment;
    private final IocEnvironmentPropertyMatcher environmentMatcher = new IocEnvironmentPropertyMatcher();

    IocConfigurationOverrideReporter(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @EventListener
    public void onStarted(ApplicationStartedEvent event) {
        reportOverrides();
    }

    void reportOverrides() {
        for (Override override : effectiveOverrides(environment.getPropertySources())) {
            log.info("IOC configuration override: {} <- {}", override.key(), override.source());
        }
    }

    List<Override> effectiveOverrides(Iterable<PropertySource<?>> sources) {
        Map<String, String> winners = new LinkedHashMap<>();
        for (PropertySource<?> source : sources) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)
                    || isBaseline(source)
                    || "configurationProperties".equals(source.getName())) {
                continue;
            }
            for (String rawName : enumerable.getPropertyNames()) {
                for (String key : canonicalNames(rawName, source)) {
                    winners.putIfAbsent(key, sourceLabel(source));
                }
            }
        }
        List<Override> overrides = new ArrayList<>();
        winners.forEach((key, source) -> overrides.add(new Override(key, source)));
        return List.copyOf(overrides);
    }

    private List<String> canonicalNames(String rawName, PropertySource<?> source) {
        if (source instanceof SystemEnvironmentPropertySource) {
            IocEnvironmentPropertyMatcher.MatchResult result = environmentMatcher.match(rawName);
            return result.ioc() && !result.isAmbiguous() ? List.copyOf(result.canonicalNames()) : List.of();
        }
        ConfigurationPropertyName name = ConfigurationPropertyName.ofIfValid(rawName);
        if (name == null) {
            try {
                name = ConfigurationPropertyName.adapt(rawName, '.');
            } catch (RuntimeException ex) {
                return List.of();
            }
        }
        return name.toString().startsWith(PREFIX_DOT) ? List.of(name.toString()) : List.of();
    }

    private boolean isBaseline(PropertySource<?> source) {
        String name = source.getName();
        return name.contains("class path resource") || name.contains("classpath:");
    }

    private String sourceLabel(PropertySource<?> source) {
        if (source instanceof SystemEnvironmentPropertySource) {
            return "environment";
        }
        return switch (source.getName()) {
            case "commandLineArgs" -> "command line";
            case "systemProperties" -> "system properties";
            default -> source.getName().contains("file [") ? "external file" : source.getName();
        };
    }

    record Override(String key, String source) {
    }
}
