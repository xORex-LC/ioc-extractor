package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportUseCase;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Complete-listing detection path shared by polling and loss-tolerant hints. */
public final class DataframeImportDetectionService {

    private final ManagedImportSourceLifecycle sources;
    private final AdmitDataframeImportUseCase admission;
    private final Clock clock;
    private final Supplier<ImportDeliveryId> deliveryIds;

    /** Creates a source detector whose IDs identify occurrences, never file content. */
    public DataframeImportDetectionService(ManagedImportSourceLifecycle sources,
                                           AdmitDataframeImportUseCase admission,
                                           Clock clock,
                                           Supplier<ImportDeliveryId> deliveryIds) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deliveryIds = Objects.requireNonNull(deliveryIds, "deliveryIds");
    }

    /** Detects stable candidates in deterministic adapter order and admits each occurrence. */
    public int detect(ImportSourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        List<ImportSourceCandidate> candidates = sources.detect(sourceId, clock.instant());
        for (ImportSourceCandidate candidate : candidates) {
            admission.admit(new AdmitDataframeImportCommand(new ImportClaimReservation(
                    deliveryIds.get(), candidate.sourceId(), candidate.candidateToken(), candidate.detectedAt())));
        }
        return candidates.size();
    }
}
