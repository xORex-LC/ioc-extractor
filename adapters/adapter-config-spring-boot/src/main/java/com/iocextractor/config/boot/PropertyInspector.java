package com.iocextractor.config.boot;

import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.iocextractor.config.boot.PropertyInspection.Coverage;
import static com.iocextractor.config.boot.PropertyInspection.SourceKind;
import static com.iocextractor.config.boot.PropertyInspection.SourceReference;

/**
 * Inspects declarations in a prepared Spring environment without reading property values.
 * Call this after source preparation and before service-selected enforcement. Binder still
 * owns conversion and precedence; Boot prepares sources. This class starts no context or background work.
 */
public final class PropertyInspector {

    private PropertyInspector() {
    }

    /**
     * Inspects a stable, highest-precedence-first source sequence. Composite children retain
     * their order. The attached {@code configurationProperties} aggregate is excluded;
     * nonenumerable sources and unqualified environment-source names disclose incomplete coverage.
     *
     * <p>Results include lower-priority and null-valued declarations. They are observations,
     * not effective overrides. Do not mutate the environment during this traversal.</p>
     *
     * @param schema explicit namespace and record root
     * @param sources prepared sources in precedence order
     * @return immutable name, schema and source observations
     * @throws NullPointerException if an argument, source or enumerated name is null
     * @throws RuntimeException if a source cannot enumerate its names; no partial success is returned
     */
    public static PropertyInspection inspect(PropertySchema schema, Iterable<PropertySource<?>> sources) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(sources, "sources");
        SchemaModel model = new SchemaModel(schema);
        List<PropertyInspection.Declaration> declarations = new ArrayList<>();
        List<PropertyInspection.SourceCoverage> coverage = new ArrayList<>();
        for (PropertySource<?> source : sources) {
            inspectSource(Objects.requireNonNull(source, "source"), model, declarations, coverage);
        }
        return new PropertyInspection(declarations, coverage, model.gaps());
    }

    private static void inspectSource(PropertySource<?> source, SchemaModel model,
                                      List<PropertyInspection.Declaration> declarations,
                                      List<PropertyInspection.SourceCoverage> coverage) {
        SourceReference ref = new SourceReference(coverage.size(), kind(source));
        if (ref.kind() == SourceKind.AGGREGATE) {
            coverage.add(new PropertyInspection.SourceCoverage(ref, Coverage.EXCLUDED));
        } else if (source instanceof CompositePropertySource composite) {
            coverage.add(new PropertyInspection.SourceCoverage(ref, Coverage.EXPANDED));
            for (PropertySource<?> child : composite.getPropertySources()) {
                inspectSource(child, model, declarations, coverage);
            }
        } else if (source instanceof EnumerablePropertySource<?> enumerable) {
            coverage.add(new PropertyInspection.SourceCoverage(ref,
                    ref.kind() == SourceKind.OTHER ? Coverage.INCOMPLETE : Coverage.ENUMERATED));
            inspectNames(enumerable, ref, model, declarations);
        } else {
            coverage.add(new PropertyInspection.SourceCoverage(ref, Coverage.INCOMPLETE));
        }
    }

    private static void inspectNames(EnumerablePropertySource<?> source, SourceReference ref, SchemaModel model,
                                     List<PropertyInspection.Declaration> declarations) {
        boolean environment = source instanceof SystemEnvironmentPropertySource;
        boolean qualified = !environment || ref.kind() == SourceKind.ENVIRONMENT;
        String[] names = source.getPropertyNames().clone();
        Arrays.sort(names);
        for (String name : names) {
            PropertyInspection.Declaration declaration = model.match(name, ref, environment, qualified);
            if (declaration != null) {
                declarations.add(declaration);
            }
        }
    }

    private static SourceKind kind(PropertySource<?> source) {
        if ("configurationProperties".equals(source.getName())) {
            return SourceKind.AGGREGATE;
        }
        if (source instanceof CompositePropertySource) {
            return SourceKind.COMPOSITE;
        }
        if (source instanceof SystemEnvironmentPropertySource) {
            return "systemEnvironment".equals(source.getName()) || source.getName().endsWith("-systemEnvironment")
                    ? SourceKind.ENVIRONMENT : SourceKind.OTHER;
        }
        return source instanceof EnumerablePropertySource<?> ? SourceKind.PROPERTIES : SourceKind.OTHER;
    }
}
