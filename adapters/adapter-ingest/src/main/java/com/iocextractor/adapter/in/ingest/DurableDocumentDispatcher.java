package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.PreparedIngestion;
import com.iocextractor.application.port.in.ingest.PrepareIngestionUseCase;
import com.iocextractor.application.port.in.ingest.RejectIngestionUseCase;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;

import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded execution of durable document references. Only the oldest admission may promote. */
public final class DurableDocumentDispatcher implements AutoCloseable {
    private static final Instant BLOCKED = Instant.ofEpochMilli(Long.MAX_VALUE);
    private final Object monitor = new Object();
    private final OrderedDocumentAdmissionHandler admissions;
    private final PrepareIngestionUseCase preparation;
    private final RejectIngestionUseCase rejection;
    private final IngestAdapterProperties.Execution limits;
    private final int preparationWindow;
    private final int maxAttempts;
    private final Duration backoff;
    private final Clock clock;
    private final DiagnosticSink diagnostics;
    private final DiagnosticFactory factory;
    private final Map<ObservationId, Job> jobs = new LinkedHashMap<>();
    private final AtomicBoolean nudged = new AtomicBoolean();
    private final ThreadPoolExecutor preparers;
    private final ThreadPoolExecutor promoter;
    private final ScheduledExecutorService coordinator;
    private boolean running;
    private boolean closed;
    private boolean failed;
    private long saturationCount;
    private long completedDocuments;
    private long completedBytes;
    private long writtenRows;
    private volatile String lastFailure;

    public DurableDocumentDispatcher(OrderedDocumentAdmissionHandler admissions,
            PrepareIngestionUseCase preparation, RejectIngestionUseCase rejection,
            IngestAdapterProperties properties, Clock clock, DiagnosticSink diagnostics) {
        this(admissions, preparation, rejection, properties, clock, diagnostics, Integer.MAX_VALUE);
    }

    public DurableDocumentDispatcher(OrderedDocumentAdmissionHandler admissions,
            PrepareIngestionUseCase preparation, RejectIngestionUseCase rejection,
            IngestAdapterProperties properties, Clock clock, DiagnosticSink diagnostics, int workspaceCapacity) {
        if (workspaceCapacity < 1) { throw new IllegalArgumentException("Workspace capacity must be positive"); }
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.rejection = Objects.requireNonNull(rejection, "rejection");
        this.limits = properties.execution();
        preparationWindow = Math.min(limits.window(), workspaceCapacity);
        this.maxAttempts = properties.retry().maxAttempts();
        this.backoff = properties.retry().backoff();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        factory = new DiagnosticFactory(clock);
        preparers = executor(limits.preparationWorkers(), limits.window(), "ioc-document-prepare-");
        promoter = executor(1, 1, "ioc-document-promote-");
        coordinator = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("ioc-document-dispatch-", 0).factory());
    }

    private static ThreadPoolExecutor executor(int workers, int capacity, String name) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), Thread.ofPlatform().daemon().name(name, 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Called only after the shared startup recovery barrier. */
    public void start() {
        synchronized (monitor) {
            if (closed) { throw new IllegalStateException("Document dispatcher is closed"); }
            if (failed) { throw new IllegalStateException("Document dispatcher requires restart after fatal failure"); }
            if (running) { return; }
            running = true;
            coordinator.scheduleWithFixedDelay(this::reconcileSafely, 0, 1, TimeUnit.SECONDS);
        }
    }

    /** Short detection path. Saturation leaves the file in the listed inbox. */
    public void handle(File file) {
        Path source = file.toPath().toAbsolutePath().normalize();
        synchronized (monitor) {
            if (!running) { return; }
            List<DocumentAdmission> pending = admissions.pending(limits.maxPendingDocuments() + 1);
            if (pending.stream().anyMatch(value -> value.candidatePath().equals(source))) { return; }
            var evidence = new FileDocumentCandidateEvidenceReader().read(source);
            long bytes = evidence.size();
            long pendingBytes = pending.stream().mapToLong(value -> value.candidateEvidence().size()).sum();
            if (pending.size() >= limits.maxPendingDocuments() || bytes > limits.maxSourceBytes()
                    || bytes > limits.maxPendingSourceBytes() - pendingBytes) {
                saturationCount++;
                return;
            }
            admissions.claim(source, new ObservationId(UUID.randomUUID().toString()), clock.instant(), evidence);
        }
        nudge();
    }

    /** A hint; the periodic journal scan owns recovery of rejected/lost hints. */
    public void nudge() {
        if (!nudged.compareAndSet(false, true)) { return; }
        try {
            coordinator.execute(() -> { nudged.set(false); reconcileSafely(); });
        } catch (RejectedExecutionException stopped) { nudged.set(false); }
    }

    private void reconcileSafely() {
        try { reconcile(); }
        catch (RuntimeException failure) { report("document-dispatch", failure); }
        catch (Error fatal) { failStop(fatal); throw fatal; }
    }

    private void reconcile() {
        synchronized (monitor) {
            if (!running) { return; }
            var pending = admissions.pending(limits.maxPendingDocuments() + 1);
            for (var admission : pending.subList(0, Math.min(pending.size(), preparationWindow))) {
                if (jobs.size() >= preparationWindow) { break; }
                if (jobs.containsKey(admission.observationId())
                        || admission.execution().retryAfter().isAfter(clock.instant())) { continue; }
                Job job = new Job(admission);
                jobs.put(admission.observationId(), job);
                try { preparers.execute(() -> prepare(job)); }
                catch (RejectedExecutionException saturated) { jobs.remove(admission.observationId()); }
            }
            if (pending.isEmpty()) { return; }
            Job head = jobs.get(pending.getFirst().observationId());
            if (head != null && head.ready && !head.promoting) {
                head.promoting = true;
                try { promoter.execute(() -> promote(head)); }
                catch (RejectedExecutionException stopped) { head.promoting = false; }
            }
        }
    }

    private void prepare(Job job) {
        PreparedIngestion prepared = null;
        RuntimeException failure = null;
        try {
            job.admission = admissions.beginExecution(job.admission.observationId());
            var document = admissions.finish(job.admission.observationId());
            job.command = new IngestSourceCommand(document.source(), document.registration());
            prepared = preparation.prepare(job.command);
        } catch (RuntimeException caught) { failure = caught; }
        catch (Error fatal) { failStop(fatal); throw fatal; }
        boolean abandoned;
        synchronized (monitor) {
            abandoned = failed;
            if (!abandoned) {
                job.prepared = prepared;
                job.failure = failure;
                job.ready = true;
            }
        }
        if (abandoned) { if (prepared != null) { prepared.close(); } return; }
        nudge();
    }

    private void promote(Job job) {
        Error primary = null;
        try { promoteReady(job); }
        catch (Error fatal) { primary = fatal; failStop(fatal); throw fatal; }
        finally { finishPromotion(job, primary); }
    }

    private void promoteReady(Job job) {
        try {
            synchronized (monitor) {
                if (failed) { return; }
                job.promotionStarted = true;
            }
            if (job.failure != null) {
                retryOrBlock(job, job.failure);
                return;
            }
            var result = job.prepared.promote();
            try { DocumentCompletionObserver.completed(result, job.admission.candidatePath(), job.command.key()); }
            catch (RuntimeException observationFailure) { report(job.admission.observationId().value(), observationFailure); }
            if (result.status() == com.iocextractor.application.ingest.IngestionStatus.FAILED) { return; }
            synchronized (monitor) {
                completedDocuments++;
                completedBytes += job.admission.candidateEvidence().size();
                writtenRows += result.extractionResultOptional().map(value -> value.writtenPerArtifact()
                        .values().stream().mapToLong(Integer::longValue).sum()).orElse(0L);
            }
        } catch (RuntimeException failure) {
            boolean recoverable;
            synchronized (monitor) { recoverable = !failed; }
            if (recoverable) { retryOrBlock(job, failure); }
        }
    }

    private void finishPromotion(Job job, Throwable primary) {
        PreparedIngestion owned;
        synchronized (monitor) { owned = job.prepared; job.prepared = null; }
        try { if (owned != null) { owned.close(); } }
        catch (RuntimeException cleanup) {
            if (primary != null) { primary.addSuppressed(cleanup); }
            else { report(job.admission.observationId().value(), cleanup); }
        } catch (Error fatal) {
            if (primary == null) { failStop(fatal); throw fatal; }
            if (fatal != primary) { primary.addSuppressed(fatal); }
        }
        synchronized (monitor) { jobs.remove(job.admission.observationId()); }
        nudge();
    }

    private void retryOrBlock(Job job, RuntimeException failure) {
        try {
            if (job.admission.execution().attempts() >= maxAttempts && job.command != null) {
                rejection.reject(job.admission.observationId(), job.command.key(), failure.getMessage());
                admissions.complete(job.admission.observationId(), DocumentTerminalOutcome.REJECTED);
            } else {
                Instant due = job.admission.execution().attempts() >= maxAttempts
                        ? BLOCKED : clock.instant().plus(backoff);
                admissions.retryExecution(job.admission.observationId(), due, failure.getMessage());
            }
        } catch (RuntimeException accounting) { failure.addSuppressed(accounting); }
        report(job.admission.observationId().value(), failure, job.command == null
                ? IngestDiagnosticCodes.SOURCE_UNREADABLE : IngestDiagnosticCodes.RECOVERY_FAILED);
    }

    private void report(String source, RuntimeException failure) {
        report(source, failure, IngestDiagnosticCodes.RECOVERY_FAILED);
    }

    private void report(String source, RuntimeException failure, IngestDiagnosticCodes code) {
        synchronized (monitor) { if (!failed) { lastFailure = failure.getClass().getSimpleName(); } }
        try { diagnostics.emit(factory.create(code)
                .with("source", source).with("reason", Objects.toString(failure.getMessage(), "execution failed"))
                .cause(failure).build()); }
        catch (RuntimeException observationFailure) { failure.addSuppressed(observationFailure); }
    }

    /** Fatal VM/worker errors stop intake and promotions; durable occurrences belong to restart recovery. */
    private void failStop(Error fatal) {
        var abandoned = new java.util.ArrayList<PreparedIngestion>();
        synchronized (monitor) {
            if (failed) { return; }
            failed = true;
            running = false;
            lastFailure = fatal.getClass().getSimpleName();
            for (var job : jobs.values()) {
                if (job.prepared != null && !job.promotionStarted) {
                    abandoned.add(job.prepared);
                    job.prepared = null;
                }
            }
            jobs.clear();
        }
        coordinator.shutdownNow();
        preparers.shutdownNow();
        promoter.shutdownNow();
        for (var prepared : abandoned) {
            try { prepared.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != fatal) { fatal.addSuppressed(cleanup); } }
        }
    }

    /** Bounded metadata-only operational snapshot. */
    public Snapshot snapshot() {
        synchronized (monitor) {
            var pending = admissions.inspectPending(limits.maxPendingDocuments() + 1);
            long bytes = pending.stream().mapToLong(value -> value.candidateEvidence().size()).sum();
            long age = pending.stream().map(DocumentAdmission::createdAt)
                    .mapToLong(at -> Math.max(0, Duration.between(at, clock.instant()).toMillis())).max().orElse(0);
            long ready = jobs.values().stream().filter(job -> job.ready && !job.promoting).count();
            long promoting = jobs.values().stream().filter(job -> job.promoting).count();
            long blocked = pending.stream().filter(value -> value.execution().retryAfter().equals(BLOCKED)).count();
            return new Snapshot(running, pending.size(), bytes, age, jobs.size() - ready - promoting,
                    ready, promoting, blocked, saturationCount, completedDocuments, completedBytes, writtenRows, lastFailure);
        }
    }

    public record Snapshot(boolean running, int pending, long sourceBytes, long oldestAgeMillis,
            long preparing, long ready, long promoting, long blocked, long saturationCount,
            long completedDocuments, long completedBytes, long writtenRows, String lastFailure) { }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) { return; }
            running = false;
            closed = true;
        }
        coordinator.shutdown();
        preparers.shutdown();
        promoter.shutdown();
        stopWorkers();
        closePreparedJobs();
    }

    private void stopWorkers() {
        RuntimeException failure = null;
        for (var worker : List.of(coordinator, preparers, promoter)) {
            try { stop(worker); }
            catch (RuntimeException stopped) {
                if (failure == null) { failure = stopped; } else { failure.addSuppressed(stopped); }
            }
        }
        if (failure != null) { throw failure; }
    }

    private void closePreparedJobs() {
        var owned = new java.util.ArrayList<PreparedIngestion>();
        synchronized (monitor) {
            for (var job : jobs.values()) {
                if (job.prepared != null) { owned.add(job.prepared); job.prepared = null; }
            }
            jobs.clear();
        }
        Throwable failure = null;
        for (var prepared : owned) {
            try { prepared.close(); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) { failure = cleanup; }
                else if (cleanup instanceof Error && !(failure instanceof Error)) {
                    cleanup.addSuppressed(failure); failure = cleanup;
                } else if (failure != cleanup) { failure.addSuppressed(cleanup); }
            }
        }
        if (failure instanceof Error fatal) { lastFailure = fatal.getClass().getSimpleName(); throw fatal; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
    }

    private static void stop(java.util.concurrent.ExecutorService worker) {
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Document worker did not terminate");
                }
            }
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Document shutdown interrupted", interrupted);
        }
    }

    private static final class Job {
        private DocumentAdmission admission;
        private IngestSourceCommand command;
        private PreparedIngestion prepared;
        private RuntimeException failure;
        private boolean ready;
        private boolean promoting;
        private boolean promotionStarted;
        private Job(DocumentAdmission admission) { this.admission = admission; }
    }
}
