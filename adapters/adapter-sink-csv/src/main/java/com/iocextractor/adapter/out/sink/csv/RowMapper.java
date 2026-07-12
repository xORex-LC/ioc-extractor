package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;

import java.util.List;
import java.util.Optional;

/**
 * Maps a classified indicator to one CSV row for a specific artifact schema.
 * A {@code null} element in the returned row is rendered as the CSV null literal.
 * One mapper per artifact = adding an artifact never touches the others (OCP).
 */
public interface RowMapper {

    /** Column names, in order, for this artifact. */
    List<String> header();

    /** Ordered cell values aligned to {@link #header()}; {@code null} = NULL. */
    List<String> toRow(long id, ClassifiedIndicator indicator);

    /**
     * Returns the configured public-id column, when present.
     *
     * <p>The id cell is a deferred slot: its final value is assigned only after
     * preparation diagnostics pass the pipeline failure-policy checkpoint.</p>
     */
    default Optional<String> idColumn() {
        return Optional.empty();
    }
}
