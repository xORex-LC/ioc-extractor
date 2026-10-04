package com.iocextractor.processing.mapping;

import com.iocextractor.processing.model.ClassifiedIndicator;
import com.iocextractor.common.IocExtractorException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static com.iocextractor.processing.mapping.RowMappingException.ComponentKind.PROVIDER;
import static com.iocextractor.processing.mapping.RowMappingException.ComponentKind.TRANSFORM;

/**
 * Generic {@link RowMapper} driven by declarative {@link ColumnSpec}s plus
 * registries of {@link ValueProvider}s and {@link Transform}s. Adding an output
 * format is configuration, not code.
 *
 * <p>Per column: a {@code when-type} gate nulls the cell for other indicator
 * types; {@code const} then uses the literal value (null ⇒ CSV NULL), otherwise
 * the named provider supplies it; finally the ordered transforms are applied.
 * The DSL is limited by design — no expressions or conditions beyond
 * {@code when-type}.
 */
public final class ConfigurableRowMapper implements RowMapper {

    private static final String CONST = "const";

    private final List<ColumnSpec> columns;
    private final Map<String, ValueProvider> providers;
    private final Map<String, Transform> transforms;
    private final Map<String, Predicate<ClassifiedIndicator>> conditions;
    private final Map<String, BoundTransform> boundTransforms;
    private final List<String> header;
    private final Optional<String> idColumn;

    public ConfigurableRowMapper(List<ColumnSpec> columns,
                                 Map<String, ValueProvider> providers,
                                 Map<String, Transform> transforms) {
        this(columns, providers, transforms, Map.of());
    }

    public ConfigurableRowMapper(List<ColumnSpec> columns,
                                 Map<String, ValueProvider> providers,
                                 Map<String, Transform> transforms,
                                 Map<String, Predicate<ClassifiedIndicator>> conditions) {
        this.columns = List.copyOf(columns);
        this.providers = Map.copyOf(providers);
        this.transforms = Map.copyOf(transforms);
        this.conditions = Map.copyOf(conditions);
        var bound = new java.util.HashMap<String, BoundTransform>();
        for (ColumnSpec column : this.columns) {
            if (column.transform() != null) {
                for (String spec : column.transform()) {
                    if (spec != null) {
                        bound.computeIfAbsent(spec, this::bindTransform);
                    }
                }
            }
        }
        this.boundTransforms = Map.copyOf(bound);
        this.header = this.columns.stream().map(ColumnSpec::name).toList();
        this.idColumn = this.columns.stream()
                .filter(column -> "id".equals(column.from()))
                .map(ColumnSpec::name)
                .findFirst();
    }

    @Override
    public List<String> header() {
        return header;
    }

    /** Returns the immutable declarative columns for adapter-owned policy reuse. */
    public List<ColumnSpec> columns() {
        return columns;
    }

    @Override
    public Optional<String> idColumn() {
        return idColumn;
    }

    @Override
    public List<String> toRow(ClassifiedIndicator indicator) {
        return toRow(indicator, Map.of());
    }

    /**
     * Maps a row with independently resolved views for named output columns.
     * The caller admits column/view bindings; an unlisted column uses the default.
     * Gates, providers and transforms all see the same resolved column view.
     */
    public List<String> toRow(ClassifiedIndicator defaultView,
                              Map<String, ClassifiedIndicator> columnViews) {
        List<String> row = new ArrayList<>(columns.size());
        for (ColumnSpec column : columns) {
            row.add(cell(column, columnViews.getOrDefault(column.name(), defaultView)));
        }
        return row;
    }

    private String cell(ColumnSpec column, ClassifiedIndicator classified) {
        if (!applies(column, classified)) {
            return null;
        }
        String value;
        if (CONST.equals(column.from())) {
            value = column.value();
        } else {
            value = provide(column, classified);
        }
        return transformValue(column, value);
    }

    /** Applies the declared source-label presentation transforms to an admitted label. */
    public String mapSourceLabel(String target, String admittedLabel) {
        ColumnSpec column = columns.stream().filter(candidate -> candidate.name().equals(target))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown source-label column: " + target));
        if (!"source.label".equals(column.from())) {
            throw new IllegalArgumentException("Column is not a source-label mapping: " + target);
        }
        return transformValue(column, admittedLabel);
    }

    private String transformValue(ColumnSpec column, String value) {
        if (value != null && column.transform() != null) {
            for (String spec : column.transform()) {
                value = applyTransform(column, spec, value);
            }
        }
        return value;
    }

    /** Shared gate for ordinary mapping and processed-import preparation. */
    public boolean applies(ColumnSpec column, ClassifiedIndicator classified) {
        if (column.whenType() != null && classified.indicator().type() != column.whenType()) {
            return false;
        }
        if (column.whenTypes() != null && !column.whenTypes().contains(classified.indicator().type())) {
            return false;
        }
        if (column.when() != null) {
            for (String key : column.when()) {
                Predicate<ClassifiedIndicator> condition = conditions.get(key);
                if (condition == null) {
                    throw new IocExtractorException("Unknown column condition: " + key);
                }
                if (!condition.test(classified)) {
                    return false;
                }
            }
        }
        return true;
    }

    private String provide(ColumnSpec column, ClassifiedIndicator classified) {
        ValueProvider provider = providers.get(column.from());
        if (provider == null) {
            throw new IocExtractorException("Unknown value provider: " + column.from());
        }
        try {
            return provider.provide(classified);
        } catch (MappingValueException failure) {
            throw new RowMappingException(column.name(), PROVIDER, column.from(), failure);
        }
    }

    private BoundTransform bindTransform(String spec) {
        int sep = spec.indexOf(':');
        String name = sep < 0 ? spec : spec.substring(0, sep);
        String arg = sep < 0 ? null : spec.substring(sep + 1);
        return new BoundTransform(name, arg, transforms.get(name));
    }

    private String applyTransform(ColumnSpec column, String spec, String value) {
        BoundTransform bound = boundTransforms.get(spec);
        if (bound.transform() == null) {
            throw new IocExtractorException("Unknown transform: " + bound.name());
        }
        try {
            return bound.transform().apply(value, bound.argument());
        } catch (MappingValueException failure) {
            throw new RowMappingException(column.name(), TRANSFORM, bound.name(), failure);
        }
    }

    private record BoundTransform(String name, String argument, Transform transform) { }
}
