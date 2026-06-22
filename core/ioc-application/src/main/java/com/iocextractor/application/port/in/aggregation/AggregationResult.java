package com.iocextractor.application.port.in.aggregation;

import java.util.Map;

/**
 * Summary of one aggregation attempt.
 */
public record AggregationResult(int sourcesProcessed,
                                int partitionsRead,
                                Map<String, Integer> rowsRead,
                                Map<String, Integer> rowsWritten,
                                int newStableIds,
                                int unchangedRows,
                                int skippedRows) {

    public AggregationResult {
        rowsRead = Map.copyOf(rowsRead);
        rowsWritten = Map.copyOf(rowsWritten);
    }

    public static AggregationResult empty() {
        return new AggregationResult(0, 0, Map.of(), Map.of(), 0, 0, 0);
    }
}
