package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.aggregation.ArtifactRow;
import com.iocextractor.application.aggregation.CanonicalArtifact;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.aggregation.CanonicalArtifactRepository;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * {@link IocSink} that writes extraction output to canonical dataframe storage.
 * Optional projection is triggered only after the canonical write succeeds.
 */
public final class JdbcIocSink implements IocSink {

    private final String name;
    private final Set<IndicatorType> accepts;
    private final Predicate<Indicator> filter;
    private final List<String> header;
    private final BiFunction<Long, Indicator, List<String>> mapper;
    private final LongSupplier ids;
    private final CanonicalArtifactRepository repository;
    private final Consumer<String> afterWrite;

    public JdbcIocSink(String name,
                       Set<IndicatorType> accepts,
                       Predicate<Indicator> filter,
                       List<String> header,
                       BiFunction<Long, Indicator, List<String>> mapper,
                       LongSupplier ids,
                       CanonicalArtifactRepository repository,
                       Consumer<String> afterWrite) {
        this.name = Objects.requireNonNull(name, "name");
        this.accepts = Set.copyOf(Objects.requireNonNull(accepts, "accepts"));
        this.filter = filter == null ? indicator -> true : filter;
        this.header = List.copyOf(Objects.requireNonNull(header, "header"));
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.afterWrite = afterWrite == null ? ignored -> { } : afterWrite;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int write(List<Indicator> indicators) {
        List<ArtifactRow> rows = indicators.stream()
                .filter(indicator -> accepts.contains(indicator.type()))
                .filter(filter)
                .map(this::row)
                .toList();
        repository.write(name, new CanonicalArtifact(name, header, rows));
        afterWrite.accept(name);
        return rows.size();
    }

    private ArtifactRow row(Indicator indicator) {
        List<String> values = mapper.apply(ids.getAsLong(), indicator);
        var row = new LinkedHashMap<String, String>();
        for (int i = 0; i < header.size(); i++) {
            String column = header.get(i);
            String value = i < values.size() ? values.get(i) : null;
            row.put(column, value);
        }
        row.put("_source_key", sourceKey(indicator));
        return ArtifactRow.ordered(row);
    }

    private String sourceKey(Indicator indicator) {
        if (indicator.source().label() != null && !indicator.source().label().isBlank()) {
            return indicator.source().label();
        }
        return "oneshot";
    }
}
