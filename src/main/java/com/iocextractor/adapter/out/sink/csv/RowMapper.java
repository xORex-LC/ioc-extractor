package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.Indicator;

import java.util.List;

/**
 * Maps a domain {@link Indicator} to one CSV row for a specific artifact schema.
 * A {@code null} element in the returned row is rendered as the CSV null literal.
 * One mapper per artifact = adding an artifact never touches the others (OCP).
 */
public interface RowMapper {

    /** Column names, in order, for this artifact. */
    List<String> header();

    /** Ordered cell values aligned to {@link #header()}; {@code null} = NULL. */
    List<String> toRow(long id, Indicator indicator);
}
