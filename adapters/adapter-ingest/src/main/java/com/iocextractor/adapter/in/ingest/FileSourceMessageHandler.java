package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.in.ingest.IngestionRejectionResult;
import com.iocextractor.application.port.in.ingest.RejectIngestionUseCase;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvent;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles one file message from Spring Integration and delegates the business
 * operation to {@link IngestSourceUseCase}.
 */
public final class FileSourceMessageHandler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileSourceMessageHandler.class);

    private final FileSourceHasher hasher;
    private final IngestSourceUseCase useCase;
    private final RejectIngestionUseCase rejectUseCase;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration backoff;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnostics;
    private final ScheduledExecutorService retryScheduler;
    private final Set<Path> retrying = ConcurrentHashMap.newKeySet();

    public FileSourceMessageHandler(FileSourceHasher hasher,
                                    IngestSourceUseCase useCase,
                                    RejectIngestionUseCase rejectUseCase,
                                    Clock clock,
                                    int maxAttempts,
                                    Duration backoff,
                                    DiagnosticSink diagnosticSink) {
        this(hasher, useCase, rejectUseCase, clock, maxAttempts, backoff, diagnosticSink,
                Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                        .daemon().name("ioc-ingest-retry", 0).factory()));
    }

    FileSourceMessageHandler(FileSourceHasher hasher,
                             IngestSourceUseCase useCase,
                             RejectIngestionUseCase rejectUseCase,
                             Clock clock,
                             int maxAttempts,
                             Duration backoff,
                             DiagnosticSink diagnosticSink,
                             ScheduledExecutorService retryScheduler) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.rejectUseCase = Objects.requireNonNull(rejectUseCase, "rejectUseCase");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff == null ? Duration.ZERO : backoff;
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnostics = new DiagnosticFactory(clock);
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "retryScheduler");
    }

    public void handle(File file) {
        Path source = file.toPath().toAbsolutePath().normalize();
        ObservationId observationId = new ObservationId(UUID.randomUUID().toString());
        if (backoff.isPositive() && maxAttempts > 1) {
            if (retrying.add(source)) {
                hashAsync(new RetryContext(source, observationId), 1);
            }
            return;
        }
        handleSynchronously(source, observationId);
    }

    @Override
    public void close() {
        retryScheduler.shutdownNow();
        retrying.clear();
    }

    private void handleSynchronously(Path source, ObservationId observationId) {
        SourceKey key;
        try {
            key = hashWithRetries(source);
        } catch (HashingExhaustedException exhausted) {
            rejectUnreadable(source, observationId, exhausted.failure());
            return;
        }
        RuntimeException last = null;
        boolean alreadyRejected = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                IngestSourceResult result = useCase.ingest(
                        new IngestSourceCommand(source, observationId, key, Instant.now(clock)));
                if (result.status() == IngestionStatus.FAILED) {
                    if (last == null) {
                        return;
                    }
                    alreadyRejected = true;
                    break;
                }
                logHandledSource(result, source, key);
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        RuntimeException terminal = last;
        if (!alreadyRejected) {
            try {
                rejectUseCase.reject(
                        observationId, key, last == null ? "source ingestion failed" : last.getMessage());
            } catch (RuntimeException rejectionFailure) {
                if (last != null) {
                    rejectionFailure.addSuppressed(last);
                }
                terminal = rejectionFailure;
            }
        }
        emitIngestDiagnostic(terminal);
        throw new IocExtractorException("Source ingestion failed after retries: " + source, terminal);
    }

    private SourceKey hashWithRetries(Path source) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return hasher.sha256(source);
            } catch (RuntimeException failure) {
                last = failure;
            }
        }
        throw new HashingExhaustedException(Objects.requireNonNull(last, "hashing failure"));
    }

    private void rejectUnreadable(Path source,
                                  ObservationId observationId,
                                  RuntimeException hashingFailure) {
        SourceKey fallbackKey = hasher.fingerprint(source);
        Diagnostic diagnostic = diagnostics.create(IngestDiagnosticCodes.SOURCE_UNREADABLE)
                .with("source", source)
                .with("reason", reason(hashingFailure))
                .cause(hashingFailure)
                .build();
        IngestionRejectionResult rejection;
        try {
            rejection = rejectUseCase.reject(observationId, fallbackKey, reason(hashingFailure));
        } catch (RuntimeException rejectionFailure) {
            emitIngestDiagnostic(rejectionFailure);
            throw new IocExtractorException("Failed to reject unreadable source: " + source, rejectionFailure);
        }
        if (rejection == IngestionRejectionResult.REJECTED) {
            diagnosticSink.emit(diagnostic);
        }
    }

    private void emitIngestDiagnostic(RuntimeException failure) {
        if (failure instanceof DiagnosticException diagnosticFailure
                && diagnosticFailure.diagnostic().category() == DiagnosticCategory.INGEST) {
            diagnosticSink.emit(diagnosticFailure.diagnostic());
        }
    }

    private String reason(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private void logHandledSource(IngestSourceResult result, Path source, SourceKey key) {
        var extraction = result.extractionResultOptional();
        if (extraction.isEmpty()) {
            LogEvents.info(log)
                    .action(EventAction.SOURCE_INGEST)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.FILE_PATH, source)
                    .field(LogField.IOC_SOURCE_CONTENT_HASH, key.value())
                    .field(LogField.IOC_INGEST_DISPOSITION,
                            result.duplicate() ? "duplicate" : "handled_without_extraction")
                    .message(result.duplicate()
                            ? "source confirmation receipt replayed"
                            : "source handled without extraction")
                    .log();
            return;
        }

        ExtractionResult completed = extraction.orElseThrow();
        CompletionStatus status = completed.completionStatus();
        LogEvent event = status == CompletionStatus.COMPLETED ? LogEvents.info(log) : LogEvents.warn(log);
        event.action(EventAction.SOURCE_INGEST)
                .outcome(status == CompletionStatus.COMPLETED_WITH_ERRORS
                        ? EventOutcome.FAILURE : EventOutcome.SUCCESS)
                .field(LogField.IOC_RUN_ID, completed.runId())
                .field(LogField.FILE_PATH, source)
                .field(LogField.IOC_SOURCE_CONTENT_HASH, key.value());
        addDiagnosticFields(event, completed);
        event.message(completionMessage(status)).log();
    }

    private void addDiagnosticFields(LogEvent event, ExtractionResult result) {
        DiagnosticSummary summary = result.diagnosticSummary();
        event.field(LogField.IOC_COMPLETION_STATUS, result.completionStatus())
                .field(LogField.IOC_DIAGNOSTIC_TOTAL, summary.total())
                .field(LogField.IOC_DIAGNOSTIC_SUPPRESSED, summary.suppressed())
                .field(LogField.IOC_DIAGNOSTIC_FATAL_COUNT, summary.count(DiagnosticSeverity.FATAL))
                .field(LogField.IOC_DIAGNOSTIC_ERROR_COUNT, summary.count(DiagnosticSeverity.ERROR))
                .field(LogField.IOC_DIAGNOSTIC_WARN_COUNT, summary.count(DiagnosticSeverity.WARN))
                .field(LogField.IOC_DIAGNOSTIC_INFO_COUNT, summary.count(DiagnosticSeverity.INFO))
                .field(LogField.IOC_DIAGNOSTIC_DEBUG_COUNT, summary.count(DiagnosticSeverity.DEBUG))
                .field(LogField.IOC_DIAGNOSTIC_TRACE_COUNT, summary.count(DiagnosticSeverity.TRACE));
    }

    private String completionMessage(CompletionStatus status) {
        return switch (status) {
            case COMPLETED -> "source ingested";
            case COMPLETED_WITH_WARNINGS -> "source ingested with warnings";
            case COMPLETED_WITH_ERRORS -> "source ingested with errors";
        };
    }

    private void hashAsync(RetryContext context, int attempt) {
        try {
            SourceKey key = hasher.sha256(context.source());
            ingestAsync(context, key, 1, null);
        } catch (RuntimeException failure) {
            if (attempt < maxAttempts && schedule(() -> hashAsync(context, attempt + 1), context)) {
                return;
            }
            retrying.remove(context.source());
            rejectUnreadable(context.source(), context.observationId(), failure);
        }
    }

    private void ingestAsync(RetryContext context,
                             SourceKey key,
                             int attempt,
                             RuntimeException previousFailure) {
        try {
            IngestSourceResult result = useCase.ingest(new IngestSourceCommand(
                    context.source(), context.observationId(), key, Instant.now(clock)));
            if (result.status() == IngestionStatus.FAILED && previousFailure != null) {
                completeAsyncFailure(context, key, previousFailure, true);
                return;
            }
            retrying.remove(context.source());
            if (result.status() != IngestionStatus.FAILED) {
                logHandledSource(result, context.source(), key);
            }
        } catch (RuntimeException failure) {
            if (attempt < maxAttempts
                    && schedule(() -> ingestAsync(context, key, attempt + 1, failure), context)) {
                return;
            }
            completeAsyncFailure(context, key, failure, false);
        }
    }

    private boolean schedule(Runnable retry, RetryContext context) {
        try {
            retryScheduler.schedule(retry, backoff.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException rejected) {
            retrying.remove(context.source());
            emitIngestDiagnostic(rejected);
            return false;
        }
    }

    private void completeAsyncFailure(RetryContext context,
                                      SourceKey key,
                                      RuntimeException failure,
                                      boolean alreadyRejected) {
        RuntimeException terminal = failure;
        if (!alreadyRejected) {
            try {
                rejectUseCase.reject(context.observationId(), key, reason(failure));
            } catch (RuntimeException rejectionFailure) {
                rejectionFailure.addSuppressed(failure);
                terminal = rejectionFailure;
            }
        }
        retrying.remove(context.source());
        emitIngestDiagnostic(terminal);
        log.error("Source ingestion failed after scheduled retries: {}", context.source(), terminal);
    }

    private record RetryContext(Path source, ObservationId observationId) {
    }

    private static final class HashingExhaustedException extends RuntimeException {

        private final RuntimeException failure;

        private HashingExhaustedException(RuntimeException failure) {
            super(failure);
            this.failure = failure;
        }

        private RuntimeException failure() {
            return failure;
        }
    }
}
