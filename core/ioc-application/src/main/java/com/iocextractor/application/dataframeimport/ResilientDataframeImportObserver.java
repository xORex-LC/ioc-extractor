package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.DataframeImportObserver;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Prevents an operational observer failure from changing durable import behavior. */
final class ResilientDataframeImportObserver implements DataframeImportObserver {

    private final DataframeImportObserver delegate;

    ResilientDataframeImportObserver(DataframeImportObserver delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void deliveryDetected(ImportDelivery delivery) {
        observe(() -> delegate.deliveryDetected(delivery));
    }

    @Override
    public void claimCompleted(ImportDelivery delivery, Duration duration) {
        observe(() -> delegate.claimCompleted(delivery, duration));
    }

    @Override
    public void stagingCompleted(ImportDelivery delivery, Duration duration) {
        observe(() -> delegate.stagingCompleted(delivery, duration));
    }

    @Override
    public void promotionCompleted(ImportDelivery delivery,
                                   CanonicalImportResult result,
                                   Duration duration) {
        observe(() -> delegate.promotionCompleted(delivery, result, duration));
    }

    @Override
    public void retryScheduled(ImportDelivery delivery, Optional<String> errorType) {
        observe(() -> delegate.retryScheduled(delivery, errorType));
    }

    @Override
    public void deliveryCompleted(ImportDelivery delivery,
                                  PublishImportReportCommand report,
                                  Duration duration) {
        observe(() -> delegate.deliveryCompleted(delivery, report, duration));
    }

    private void observe(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException ignored) {
            // Durable import evidence remains authoritative when observation fails.
        }
    }
}
