package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.DataframeImportObserver;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;

import java.time.Duration;
import java.util.Optional;

/** Default operational observer used when no external adapter is configured. */
public enum NoopDataframeImportObserver implements DataframeImportObserver {
    INSTANCE;

    @Override
    public void deliveryDetected(ImportDelivery delivery) {
    }

    @Override
    public void claimCompleted(ImportDelivery delivery, Duration duration) {
    }

    @Override
    public void stagingCompleted(ImportDelivery delivery, Duration duration) {
    }

    @Override
    public void promotionCompleted(ImportDelivery delivery,
                                   CanonicalImportResult result,
                                   Duration duration) {
    }

    @Override
    public void retryScheduled(ImportDelivery delivery, Optional<String> errorType) {
    }

    @Override
    public void deliveryCompleted(ImportDelivery delivery,
                                  PublishImportReportCommand report,
                                  Duration duration) {
    }
}
