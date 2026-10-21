package com.iocextractor.bootstrap;

import com.iocextractor.config.boot.PropertyInspection;
import com.iocextractor.config.boot.PropertyInspector;
import com.iocextractor.config.boot.PropertySchema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reports the first declared external occurrence of each {@code ioc.*} name.
 *
 * <p>This preserves the service's display policy; it is not final Binder provenance,
 * especially for lists and null declarations. Values are never read for this report.</p>
 */
@Component
final class IocConfigurationOverrideReporter {

    private static final Logger log = LoggerFactory.getLogger(IocConfigurationOverrideReporter.class);
    private static final String PREFIX_DOT = "ioc.";
    private static final PropertySchema SCHEMA = new PropertySchema("ioc", IocProperties.class);

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
            override.migration().ifPresent(migration ->
                    log.warn("{}: {} is accepted temporarily for rollback compatibility; migrate to {}",
                            migration.diagnosticCode(), override.key(), migration.replacement()));
        }
    }

    List<Override> effectiveOverrides(Iterable<PropertySource<?>> sources) {
        Map<String, Override> winners = new LinkedHashMap<>();
        for (PropertySource<?> source : sources) {
            if (isBaseline(source) || IocConfigurationPropertyShape.skipInspection(source)) {
                continue;
            }
            PropertyInspection inspection = PropertyInspector.inspect(SCHEMA, List.of(source));
            Map<PropertyInspection.Declaration, IocConfigurationMigrationCatalog.Notice> notices =
                    IocConfigurationMigrationCatalog.notices(inspection);
            for (PropertyInspection.Declaration declaration : inspection.declarations()) {
                for (String key : canonicalNames(declaration.rawName(), source)) {
                    Optional<IocConfigurationMigrationCatalog.Notice> notice = Optional.ofNullable(notices.get(declaration))
                            .filter(candidate -> candidate.path().equals(key));
                    winners.putIfAbsent(key, new Override(key, sourceLabel(source), notice));
                }
            }
        }
        return List.copyOf(winners.values());
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

    record Override(String key, String source, Optional<IocConfigurationMigrationCatalog.Notice> migration) {
    }
}
