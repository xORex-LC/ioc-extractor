package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.in.ingest.RejectIngestionUseCase;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Handles one file message from Spring Integration and delegates the business
 * operation to {@link IngestSourceUseCase}.
 */
public final class FileSourceMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(FileSourceMessageHandler.class);

    private final FileSourceHasher hasher;
    private final IngestSourceUseCase useCase;
    private final RejectIngestionUseCase rejectUseCase;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration backoff;

    public FileSourceMessageHandler(FileSourceHasher hasher,
                                    IngestSourceUseCase useCase,
                                    RejectIngestionUseCase rejectUseCase,
                                    Clock clock,
                                    int maxAttempts,
                                    Duration backoff) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.rejectUseCase = Objects.requireNonNull(rejectUseCase, "rejectUseCase");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff == null ? Duration.ZERO : backoff;
    }

    public void handle(File file) {
        Path source = file.toPath();
        var key = hasher.sha256(source);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                useCase.ingest(new IngestSourceCommand(source, key, Instant.now(clock)));
                LogEvents.info(log)
                        .action(EventAction.SOURCE_READ)
                        .outcome(EventOutcome.SUCCESS)
                        .field(LogField.FILE_PATH, source)
                        .field(LogField.IOC_SOURCE_CONTENT_HASH, key.value())
                        .message("source ingested")
                        .log();
                return;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxAttempts) {
                    sleep();
                }
            }
        }
        LogEvents.error(log)
                .action(EventAction.SOURCE_READ)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.FILE_PATH, source)
                .field(LogField.IOC_SOURCE_CONTENT_HASH, key.value())
                .message("source ingestion failed")
                .log(last);
        rejectUseCase.reject(key, last == null ? "source ingestion failed" : last.getMessage());
        throw new IocExtractorException("Source ingestion failed after retries: " + source, last);
    }

    private void sleep() {
        if (backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IocExtractorException("Interrupted while waiting for ingest retry", e);
        }
    }
}
