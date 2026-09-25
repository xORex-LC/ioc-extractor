package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.ingest.OrderedDocumentAdmissionHandler;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.observation.ObservationOrderingPolicy;

import java.util.Objects;

/** Drains pre-hash admission recovery before ordinary ledger recovery opens intake. */
final class OrderedDocumentStartupRecovery {

    private static final int BATCH_SIZE = 256;

    private final OrderedDocumentAdmissionHandler admissions;
    private final IngestSourceUseCase ingestion;
    private final ObservationOrderingPolicy orderingPolicy;

    OrderedDocumentStartupRecovery(OrderedDocumentAdmissionHandler admissions,
                                   IngestSourceUseCase ingestion) {
        this(admissions, ingestion, new ObservationOrderingPolicy(true));
    }

    OrderedDocumentStartupRecovery(OrderedDocumentAdmissionHandler admissions,
                                   IngestSourceUseCase ingestion,
                                   ObservationOrderingPolicy orderingPolicy) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
        this.orderingPolicy = Objects.requireNonNull(orderingPolicy, "orderingPolicy");
    }

    int recover() {
        if (!orderingPolicy.enabled()) {
            return 0;
        }
        int recovered = 0;
        while (true) {
            var batch = admissions.recover(BATCH_SIZE);
            for (var admitted : batch) {
                ingestion.ingest(new IngestSourceCommand(
                        admitted.source(), admitted.registration()));
                recovered++;
            }
            if (batch.size() < BATCH_SIZE) {
                return recovered;
            }
        }
    }
}
