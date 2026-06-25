package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
    private final RowValues mapper;
    private final LongSupplier ids;
    private final CanonicalArtifactRepository repository;
    private final Consumer<String> afterWrite;
    private final String sourceKey;

    public JdbcIocSink(String name,
                       Set<IndicatorType> accepts,
                       Predicate<Indicator> filter,
                       List<String> header,
                       RowValues mapper,
                       LongSupplier ids,
                       CanonicalArtifactRepository repository,
                       Consumer<String> afterWrite) {
        this(name, accepts, filter, header, mapper, ids, repository, afterWrite, null);
    }

    public JdbcIocSink(String name,
                       Set<IndicatorType> accepts,
                       Predicate<Indicator> filter,
                       List<String> header,
                       RowValues mapper,
                       LongSupplier ids,
                       CanonicalArtifactRepository repository,
                       Consumer<String> afterWrite,
                       String sourceKey) {
        this.name = Objects.requireNonNull(name, "name");
        this.accepts = Set.copyOf(Objects.requireNonNull(accepts, "accepts"));
        this.filter = filter == null ? indicator -> true : filter;
        this.header = List.copyOf(Objects.requireNonNull(header, "header"));
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.afterWrite = afterWrite == null ? ignored -> { } : afterWrite;
        this.sourceKey = sourceKey;
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

    /**
     * Maps an indicator to artifact column values using a primitive {@code long}
     * id, so no autoboxing happens on the hot extraction path.
     */
    @FunctionalInterface
    public interface RowValues {
        List<String> map(long id, Indicator indicator);
    }

    private ArtifactRow row(Indicator indicator) {
        List<String> values = mapper.map(ids.getAsLong(), indicator);
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
        if (sourceKey != null && !sourceKey.isBlank()) {
            return sourceKey;
        }
        if (indicator.source().label() != null && !indicator.source().label().isBlank()) {
            return indicator.source().label();
        }
        return "oneshot";
    }
}
