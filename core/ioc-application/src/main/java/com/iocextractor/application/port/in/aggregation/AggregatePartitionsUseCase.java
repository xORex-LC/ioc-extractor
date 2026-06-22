package com.iocextractor.application.port.in.aggregation;

/**
 * Driving port for daemon partition aggregation.
 */
public interface AggregatePartitionsUseCase {

    /**
     * Aggregates source-scoped partition artifacts into canonical artifacts.
     *
     * @param command aggregation command
     * @return aggregation summary
     */
    AggregationResult aggregate(AggregationCommand command);
}
