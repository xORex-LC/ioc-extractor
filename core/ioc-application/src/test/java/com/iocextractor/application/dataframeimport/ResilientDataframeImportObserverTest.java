package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.DataframeImportObserver;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;

class ResilientDataframeImportObserverTest {

    @Test
    void isolatesEveryObserverCallbackFailure() {
        var observer = new ResilientDataframeImportObserver(new ThrowingObserver());

        assertThatCode(() -> observer.deliveryDetected(null)).doesNotThrowAnyException();
        assertThatCode(() -> observer.claimCompleted(null, Duration.ZERO)).doesNotThrowAnyException();
        assertThatCode(() -> observer.stagingCompleted(null, Duration.ZERO)).doesNotThrowAnyException();
        assertThatCode(() -> observer.promotionCompleted(null, null, Duration.ZERO))
                .doesNotThrowAnyException();
        assertThatCode(() -> observer.retryScheduled(null, Optional.empty()))
                .doesNotThrowAnyException();
        assertThatCode(() -> observer.deliveryCompleted(null, null, Duration.ZERO))
                .doesNotThrowAnyException();
    }

    private static final class ThrowingObserver implements DataframeImportObserver {

        @Override
        public void deliveryDetected(ImportDelivery delivery) {
            fail();
        }

        @Override
        public void claimCompleted(ImportDelivery delivery, Duration duration) {
            fail();
        }

        @Override
        public void stagingCompleted(ImportDelivery delivery, Duration duration) {
            fail();
        }

        @Override
        public void promotionCompleted(
                ImportDelivery delivery, CanonicalImportResult result, Duration duration) {
            fail();
        }

        @Override
        public void retryScheduled(ImportDelivery delivery, Optional<String> errorType) {
            fail();
        }

        @Override
        public void deliveryCompleted(
                ImportDelivery delivery, PublishImportReportCommand report, Duration duration) {
            fail();
        }

        private void fail() {
            throw new IllegalStateException("observer unavailable");
        }
    }
}
