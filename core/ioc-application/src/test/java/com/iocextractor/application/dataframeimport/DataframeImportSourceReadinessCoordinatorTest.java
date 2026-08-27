package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadiness;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadinessPhase;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadinessStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataframeImportSourceReadinessCoordinatorTest {

    private static final ImportSourceId SOURCE = new ImportSourceId("source-1");

    @Test
    void cachesPositiveCapabilityButReprobesClosedSource() {
        AtomicInteger probes = new AtomicInteger();
        DataframeImportSourceReadinessCoordinator coordinator =
                new DataframeImportSourceReadinessCoordinator(sourceId -> {
                    int attempt = probes.incrementAndGet();
                    return attempt == 1
                            ? new ImportSourceReadiness(sourceId,
                                    ImportSourceReadinessPhase.NAMESPACE,
                                    ImportSourceReadinessStatus.TRANSIENTLY_UNAVAILABLE,
                                    "IMPORT.SOURCE_CAPABILITY_FAILED", true)
                            : ImportSourceReadiness.ready(sourceId);
                });

        assertThat(coordinator.ready(SOURCE)).isFalse();
        assertThat(coordinator.ready(SOURCE)).isTrue();
        assertThat(coordinator.ready(SOURCE)).isTrue();
        assertThat(probes).hasValue(2);
    }

    @Test
    void rejectsCapabilityEvidenceForAnotherSource() {
        DataframeImportSourceReadinessCoordinator coordinator =
                new DataframeImportSourceReadinessCoordinator(
                        ignored -> ImportSourceReadiness.ready(new ImportSourceId("other")));

        assertThatThrownBy(() -> coordinator.ready(SOURCE))
                .isInstanceOf(DataframeImportConsistencyException.class);
    }
}
