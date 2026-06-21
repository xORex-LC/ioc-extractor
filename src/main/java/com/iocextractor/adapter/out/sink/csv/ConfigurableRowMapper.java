package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.common.IocExtractorException;
import com.iocextractor.domain.model.Indicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generic {@link RowMapper} driven by declarative {@link ColumnSpec}s plus
 * registries of {@link ValueProvider}s and {@link Transform}s. Adding an output
 * format is configuration, not code.
 *
 * <p>Per column: {@code const} uses the literal value (null ⇒ CSV NULL), otherwise
 * the named provider supplies it; a {@code when-type} gate nulls the cell for other
 * indicator types; finally the ordered transforms are applied. The DSL is limited
 * by design — no expressions or conditions beyond {@code when-type}.
 */
public final class ConfigurableRowMapper implements RowMapper {

    private static final String CONST = "const";

    private final List<ColumnSpec> columns;
    private final Map<String, ValueProvider> providers;
    private final Map<String, Transform> transforms;

    public ConfigurableRowMapper(List<ColumnSpec> columns,
                                 Map<String, ValueProvider> providers,
                                 Map<String, Transform> transforms) {
        this.columns = List.copyOf(columns);
        this.providers = Map.copyOf(providers);
        this.transforms = Map.copyOf(transforms);
    }

    @Override
    public List<String> header() {
        return columns.stream().map(ColumnSpec::name).toList();
    }

    @Override
    public List<String> toRow(long id, Indicator indicator) {
        List<String> row = new ArrayList<>(columns.size());
        for (ColumnSpec column : columns) {
            row.add(cell(column, id, indicator));
        }
        return row;
    }

    private String cell(ColumnSpec column, long id, Indicator indicator) {
        String value;
        if (CONST.equals(column.from())) {
            value = column.value();
        } else {
            ValueProvider provider = providers.get(column.from());
            if (provider == null) {
                throw new IocExtractorException("Unknown value provider: " + column.from());
            }
            value = provider.provide(id, indicator);
        }
        if (column.whenType() != null && indicator.type() != column.whenType()) {
            return null;
        }
        if (value != null && column.transform() != null) {
            for (String spec : column.transform()) {
                value = applyTransform(spec, value);
            }
        }
        return value;
    }

    private String applyTransform(String spec, String value) {
        int sep = spec.indexOf(':');
        String name = sep < 0 ? spec : spec.substring(0, sep);
        String arg = sep < 0 ? null : spec.substring(sep + 1);
        Transform transform = transforms.get(name);
        if (transform == null) {
            throw new IocExtractorException("Unknown transform: " + name);
        }
        return transform.apply(value, arg);
    }
}
