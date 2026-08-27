package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadiness;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadinessPhase;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadinessStatus;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceResult;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataframeImportDetectionServiceTest {

    private static final ImportSourceId CLOSED = new ImportSourceId("closed-source");
    private static final ImportSourceId READY = new ImportSourceId("ready-source");

    @Test
    void capabilityClosesOnlyTheAffectedSourceBeforeListing() {
        List<ImportSourceId> listed = new ArrayList<>();
        ManagedImportSourceLifecycle lifecycle = new ManagedImportSourceLifecycle() {
            @Override
            public List<ImportSourceCandidate> detect(ImportSourceId sourceId, Instant observedAt) {
                listed.add(sourceId);
                return List.of();
            }

            @Override
            public ClaimImportSourceResult claim(ClaimImportSourceCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void disposition(DispositionImportSourceCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void purgeSnapshot(ImportDeliveryId deliveryId, ImportSourceId sourceId) {
                throw new UnsupportedOperationException();
            }
        };
        var readiness = new DataframeImportSourceReadinessCoordinator(sourceId ->
                sourceId.equals(CLOSED)
                        ? new ImportSourceReadiness(sourceId,
                                ImportSourceReadinessPhase.NAMESPACE,
                                ImportSourceReadinessStatus.INCOMPATIBLE,
                                "IMPORT.SOURCE_NAMESPACE_INCOMPATIBLE", false)
                        : ImportSourceReadiness.ready(sourceId));
        var detector = new DataframeImportDetectionService(
                lifecycle,
                command -> {
                    throw new AssertionError("empty listings must not admit candidates");
                },
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC),
                () -> new ImportDeliveryId("unused"),
                readiness);

        assertThat(detector.detect(CLOSED)).isZero();
        assertThat(detector.detect(READY)).isZero();
        assertThat(listed).containsExactly(READY);
    }
}
