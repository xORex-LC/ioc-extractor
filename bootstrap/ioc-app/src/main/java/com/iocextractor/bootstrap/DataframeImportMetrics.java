package com.iocextractor.bootstrap;

import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;

/** Low-cardinality gauges for the managed-import recovery and in-memory accelerators. */
final class DataframeImportMetrics {

    DataframeImportMetrics(MeterRegistry registry,
                           DataframeImportRuntimeState state,
                           KeyedSerialExecutor lanes) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(lanes, "lanes");
        registry.gauge("ioc.dataframe.import.recovery.complete", state,
                value -> value.recoveryComplete() ? 1.0 : 0.0);
        registry.gauge("ioc.dataframe.import.lanes.queued", lanes,
                value -> value.snapshot().keys().stream()
                        .mapToInt(snapshot -> snapshot.queuedDepth()).sum());
        registry.gauge("ioc.dataframe.import.lanes.running", lanes,
                value -> value.snapshot().keys().stream()
                        .filter(snapshot -> snapshot.running()).count());
    }
}
