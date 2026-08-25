package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDelivery;

import java.time.Duration;
import java.util.Optional;

/**
 * Observes managed-import durable checkpoints without owning processing or recovery.
 *
 * <p>Implementations are operational adapters. They must not mutate import state,
 * publish business commands or allow observation failures to escape.</p>
 */
public interface DataframeImportObserver {

    /** Called after a new delivery occurrence has been durably reserved. */
    void deliveryDetected(ImportDelivery delivery);

    /** Called after source ownership and the immutable snapshot checkpoint are durable. */
    void claimCompleted(ImportDelivery delivery, Duration duration);

    /** Called after exact-one recognition and sealed staging evidence are durable. */
    void stagingCompleted(ImportDelivery delivery, Duration duration);

    /** Called after canonical promotion and its dataframe receipt are durable. */
    void promotionCompleted(ImportDelivery delivery,
                            CanonicalImportResult result,
                            Duration duration);

    /** Called after a retry decision is durably scheduled. */
    void retryScheduled(ImportDelivery delivery, Optional<String> errorType);

    /** Called after report, source disposition and terminal ledger state are durable. */
    void deliveryCompleted(ImportDelivery delivery,
                           PublishImportReportCommand report,
                           Duration duration);
}
