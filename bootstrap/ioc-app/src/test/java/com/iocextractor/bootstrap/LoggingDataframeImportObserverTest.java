package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryEvidence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryRetryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingDataframeImportObserverTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH = "a".repeat(64);
    private static final String PRIVATE_VALUE = "hxxp://private.example/path?secret=value";

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingDataframeImportObserver.class);

    @AfterEach
    void detachAppenders() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
    }

    @Test
    void emitsTypedValueFreeEventsAtDurableDeliveryCheckpoints() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        var appender = appender();
        var observer = new LoggingDataframeImportObserver(diagnostics::add, CLOCK);
        ImportDelivery delivery = delivery();

        observer.deliveryDetected(delivery);
        observer.claimCompleted(delivery, Duration.ofMillis(2));
        observer.stagingCompleted(delivery, Duration.ofMillis(3));
        observer.promotionCompleted(delivery, promotion(), Duration.ofMillis(4));
        observer.retryScheduled(delivery, Optional.of(IllegalStateException.class.getName()));
        observer.deliveryCompleted(delivery, rejectedReport(), Duration.ofMillis(5));

        assertThat(appender.list).extracting(event -> fields(event).get(LogField.EVENT_ACTION.key()))
                .containsExactly(
                        EventAction.IMPORT_START.value(), EventAction.IMPORT_CLAIM.value(),
                        EventAction.IMPORT_STAGE.value(), EventAction.IMPORT_PROMOTE.value(),
                        EventAction.IMPORT_RETRY.value(), EventAction.IMPORT_COMPLETE.value());
        assertThat(appender.list).allSatisfy(event -> assertThat(fields(event))
                .containsEntry(LogField.IOC_IMPORT_DELIVERY_ID.key(), "delivery-1")
                .containsEntry(LogField.IOC_SOURCE_ID.key(), "source-1")
                .containsEntry(LogField.IOC_IMPORT_CONTRACT_ID.key(), "ip-list-v1"));
        assertThat(fields(appender.list.get(3)))
                .containsEntry(LogField.IOC_IMPORT_ACCEPTED_ROWS.key(), 2L)
                .containsEntry(LogField.IOC_IMPORT_AFFECTED_ARTIFACTS.key(), "ip_list,masks");
        assertThat(fields(appender.list.get(4)))
                .containsEntry(LogField.EVENT_OUTCOME.key(), EventOutcome.FAILURE.value())
                .containsEntry(LogField.IOC_IMPORT_RETRY_DELAY_MS.key(), 5000L);
        assertThat(fields(appender.list.getLast()))
                .containsEntry(LogField.IOC_IMPORT_OUTCOME.key(), ImportTerminalOutcome.REJECTED.name());
        assertThat(diagnostics).extracting(diagnostic -> diagnostic.code().id())
                .containsExactly("IMPORT.PROCESSING_FAILED", "IMPORT.INPUT_INVALID");
        assertThat(appender.list).noneSatisfy(event -> assertThat(event.toString())
                .contains(PRIVATE_VALUE));
    }

    private ImportDelivery delivery() {
        ImportContractPin contract = new ImportContractPin(
                new ImportContractId("ip-list-v1"), 2, new ImportContractFingerprint(HASH));
        ImportSnapshot snapshot = new ImportSnapshot(
                new ImportSnapshotReference("snapshot:test"), new ImportSha256(HASH), 128);
        return new ImportDelivery(
                new ImportDeliveryId("delivery-1"), new ImportDeliverySequence(7),
                new ImportSourceId("source-1"), PRIVATE_VALUE, Optional.empty(),
                ImportDeliveryState.CONTRACT_PINNED, 3,
                new ImportDeliveryEvidence(
                        Optional.of(snapshot), Optional.of(contract), Optional.empty()),
                new ImportDeliveryRetryState(
                        2, Optional.of(NOW.plusSeconds(5)), Optional.of("IMPORT.PROCESSING_FAILED")),
                Optional.empty(), NOW.minusSeconds(10), NOW);
    }

    private CanonicalImportResult promotion() {
        return new CanonicalImportResult(
                ImportPromotionOutcome.COMMITTED, 2, 1, 2,
                Set.of("masks", "ip_list"), Set.of("masks", "ip_list"),
                Map.of("masks", 1L, "ip_list", 2L), NOW);
    }

    private PublishImportReportCommand rejectedReport() {
        return new PublishImportReportCommand(
                new ImportDeliveryId("delivery-1"), new ImportSourceId("source-1"),
                new ImportSnapshotReference("snapshot:test"),
                Optional.of(new ImportContractPin(
                        new ImportContractId("ip-list-v1"), 2, new ImportContractFingerprint(HASH))),
                ImportTerminalOutcome.REJECTED, 0, 0, 0, Set.of(),
                List.of("IMPORT.INPUT_INVALID"), List.of());
    }

    private ListAppender<ILoggingEvent> appender() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        var appender = new PreparingListAppender();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        return fields;
    }

    private static final class PreparingListAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            super.append(eventObject);
        }
    }
}
