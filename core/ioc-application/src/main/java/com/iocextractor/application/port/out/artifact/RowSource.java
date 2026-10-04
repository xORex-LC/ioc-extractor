package com.iocextractor.application.port.out.artifact;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Repeatable immutable rows with explicit cursor ownership and constant-size metadata. */
public interface RowSource<T> {
    /** Number of rows, validated by the owner before publication. */
    int size();
    /** Opens a new read cursor; the owner may permit only one live cursor at a time. */
    RowCursor<T> open();

    /** Visits rows without retaining an observation-wide collection. */
    default void forEach(Consumer<? super T> consumer) {
        try (var cursor = open()) {
            while (cursor.next()) {
                consumer.accept(cursor.value());
            }
        }
    }

    /** Short-circuit inspection still closes its cursor. */
    default boolean anyMatch(Predicate<? super T> predicate) {
        try (var cursor = open()) {
            while (cursor.next()) {
                if (predicate.test(cursor.value())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Maps each row inside the caller's cursor without collecting the result. */
    default <R> RowSource<R> map(Function<? super T, ? extends R> mapper) {
        return com.iocextractor.application.artifact.RowSources.map(this, mapper);
    }

    /** Explicit materialization for small callers/fixtures, never the document pipeline. */
    default List<T> snapshot() {
        var rows = new java.util.ArrayList<T>();
        forEach(rows::add);
        return List.copyOf(rows);
    }

    /** Adapts an already supplied finite command; no production spill fallback is implied. */
    static <T> RowSource<T> of(List<T> rows) {
        return com.iocextractor.application.artifact.RowSources.of(rows);
    }
}
