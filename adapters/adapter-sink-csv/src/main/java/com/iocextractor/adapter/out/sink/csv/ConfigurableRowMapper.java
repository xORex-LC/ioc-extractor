package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.common.IocExtractorException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.iocextractor.adapter.out.sink.csv.RowMappingException.ComponentKind.PROVIDER;
import static com.iocextractor.adapter.out.sink.csv.RowMappingException.ComponentKind.TRANSFORM;

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
    private final List<String> header;
    private final Optional<String> idColumn;

    public ConfigurableRowMapper(List<ColumnSpec> columns,
                                 Map<String, ValueProvider> providers,
                                 Map<String, Transform> transforms) {
        this.columns = List.copyOf(columns);
        this.providers = Map.copyOf(providers);
        this.transforms = Map.copyOf(transforms);
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

    @Override
    public Optional<String> idColumn() {
        return idColumn;
    }

    @Override
    public List<String> toRow(ClassifiedIndicator indicator) {
        List<String> row = new ArrayList<>(columns.size());
        for (ColumnSpec column : columns) {
            row.add(cell(column, indicator));
        }
        return row;
    }

    private String cell(ColumnSpec column, ClassifiedIndicator classified) {
        if (column.whenType() != null && classified.indicator().type() != column.whenType()) {
            return null;
        }
        String value;
        if (CONST.equals(column.from())) {
            value = column.value();
        } else {
            value = provide(column, classified);
        }
        if (value != null && column.transform() != null) {
            for (String spec : column.transform()) {
                value = applyTransform(column, spec, value);
            }
        }
        return value;
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

    private String applyTransform(ColumnSpec column, String spec, String value) {
        int sep = spec.indexOf(':');
        String name = sep < 0 ? spec : spec.substring(0, sep);
        String arg = sep < 0 ? null : spec.substring(sep + 1);
        Transform transform = transforms.get(name);
        if (transform == null) {
            throw new IocExtractorException("Unknown transform: " + name);
        }
        try {
            return transform.apply(value, arg);
        } catch (MappingValueException failure) {
            throw new RowMappingException(column.name(), TRANSFORM, name, failure);
        }
    }
}
