package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentAdmissionPhase;
import com.iocextractor.application.ingest.admission.DocumentExecutionState;
import com.iocextractor.application.ingest.admission.DocumentExecutionState.Failure;
import com.iocextractor.application.ingest.admission.DocumentRejection;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.PreparedIngestion;
import com.iocextractor.application.port.in.ingest.PrepareIngestionUseCase;
import com.iocextractor.platform.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.platform.diagnostics.sink.DiagnosticSink;

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
    private static final int STARTUP_REJECTION_LIMIT = 256;
    private final Object monitor = new Object();
    private final OrderedDocumentAdmissionHandler admissions;
    private final PrepareIngestionUseCase preparation;
    private final FileDocumentCandidateEvidenceReader evidenceReader = new FileDocumentCandidateEvidenceReader();
    private final LinkedHashMap<Path, Instant> unclaimedBackoff = new LinkedHashMap<>();
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
            PrepareIngestionUseCase preparation,
            IngestAdapterProperties properties, Clock clock, DiagnosticSink diagnostics) {
        this(admissions, preparation, properties, clock, diagnostics, Integer.MAX_VALUE);
    }

    public DurableDocumentDispatcher(OrderedDocumentAdmissionHandler admissions,
            PrepareIngestionUseCase preparation,
            IngestAdapterProperties properties, Clock clock, DiagnosticSink diagnostics, int workspaceCapacity) {
        if (workspaceCapacity < 1) { throw new IllegalArgumentException("Workspace capacity must be positive"); }
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
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
            List<DocumentAdmission> pending = admissions.inspectPending(limits.maxPendingDocuments() + 1);
            if (pending.stream().anyMatch(value -> value.candidatePath().equals(source)
                    && value.phase() != DocumentAdmissionPhase.TERMINAL)) {
                return;
            }
            Instant now = clock.instant();
            if (unclaimedBackoff.getOrDefault(source, Instant.EPOCH).isAfter(now)) {
                return;
            }
            try {
                var evidence = evidenceReader.read(source);
                if (evidence.fileKey().isEmpty()) {
                    throw new IllegalStateException("Stable document identity is unavailable");
                }
                long pendingBytes = admittedBytes(pending);
                boolean oversize = evidence.size() > limits.maxSourceBytes();
                if (pending.size() >= limits.maxPendingDocuments()
                        || !oversize && evidence.size() > limits.maxPendingSourceBytes() - pendingBytes) {
                    saturationCount++;
                    return;
                }
                admissions.claim(source, new ObservationId(UUID.randomUUID().toString()), now, evidence);
                unclaimedBackoff.remove(source);
            } catch (RuntimeException failure) {
                unclaimedBackoff.put(source, now.plus(backoff.isZero() ? Duration.ofSeconds(1) : backoff));
                if (unclaimedBackoff.size() > limits.maxPendingDocuments()) {
                    unclaimedBackoff.pollFirstEntry();
                }
                report("document-intake", ownershipReason(failure, Failure.CLAIM_UNCONFIRMED), IngestDiagnosticCodes.CLAIM_FAILED);
            }
        }
        nudge();
    }

    private long admittedBytes(List<DocumentAdmission> pending) {
        return pending.stream().mapToLong(value -> value.candidateEvidence().size())
                .filter(bytes -> bytes <= limits.maxSourceBytes()).sum();
    }

    /** Resumes already-durable dispositions at the queue head before opening intake. */
    public int recoverBeforeIntake() {
        int recovered = 0;
        while (recovered < STARTUP_REJECTION_LIMIT) {
            var pending = admissions.pending(1, preparation::hasPromotionPlan);
            if (pending.isEmpty()) {
                break;
            }
            var head = pending.getFirst();
            if (head.rejection().isEmpty() || head.execution().retryAfter().isAfter(clock.instant())
                    || !finishRejection(admissions.beginExecution(head.observationId()))) {
                break;
            }
            recovered++;
        }
        return recovered;
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
            var pending = admissions.pending(limits.maxPendingDocuments() + 1, preparation::hasPromotionPlan);
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
        try {
            job.admission = admissions.beginExecution(job.admission.observationId());
            if (job.admission.rejection().isEmpty()) {
                prepared = prepareAdmission(job);
            }
        } catch (RuntimeException failure) {
            job.claimFailure = ownershipReason(failure, Failure.CLAIM_UNCONFIRMED);
        }
        catch (Error fatal) { failStop(fatal); throw fatal; }
        boolean abandoned;
        synchronized (monitor) {
            abandoned = failed;
            if (!abandoned) {
                job.prepared = prepared;
                job.ready = true;
            }
        }
        if (abandoned) { if (prepared != null) { prepared.close(); } return; }
        nudge();
    }

    private PreparedIngestion prepareAdmission(Job job) {
        var id = job.admission.observationId();
        OrderedDocumentAdmissionHandler.AdmittedDocument document;
        if (preparation.hasPromotionPlan(id)) {
            document = admissions.linkedDocument(job.admission);
        } else {
            if (admissions.promotionUncertain(job.admission)) {
                return null;
            }
            job.admission = admissions.ensureClaimed(id);
            if (oversize(job.admission)) {
                return null;
            }
            document = admissions.finish(id);
        }
        job.command = new IngestSourceCommand(document.source(), document.registration());
        return preparation.prepare(job.command);
    }

    private void promote(Job job) {
        Error primary = null;
        try { promoteReady(job); }
        catch (Error fatal) { primary = fatal; failStop(fatal); throw fatal; }
        finally { finishPromotion(job, primary); }
    }

    private void promoteReady(Job job) {
        synchronized (monitor) {
            if (failed) { return; }
            job.promotionStarted = true;
        }
        try {
            if (job.prepared == null) {
                reconcileUnprepared(job);
            } else {
                promotePrepared(job);
            }
        } catch (RuntimeException failure) {
            report(job.admission.observationId().value(), failure);
        }
    }

    private void promotePrepared(Job job) {
        com.iocextractor.application.port.in.ingest.IngestSourceResult result;
        try {
            result = job.prepared.promote();
        } catch (RuntimeException failure) {
            synchronized (monitor) {
                if (failed) { return; }
            }
            reconcileUnprepared(job);
            return;
        }
        try { DocumentCompletionObserver.completed(result, job.admission.candidatePath(), job.command.key()); }
        catch (RuntimeException failure) { report(job.admission.observationId().value(), failure); }
        if (result.status() == com.iocextractor.application.ingest.IngestionStatus.FAILED) { return; }
        synchronized (monitor) {
            completedDocuments++;
            completedBytes += job.admission.candidateEvidence().size();
            writtenRows += result.extractionResultOptional().map(value -> value.writtenPerArtifact()
                    .values().stream().mapToLong(Integer::longValue).sum()).orElse(0L);
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

    private boolean oversize(DocumentAdmission admission) {
        return admission.sourceKey().isEmpty() && admission.candidateEvidence().size() > limits.maxSourceBytes();
    }

    private void reconcileUnprepared(Job job) {
        var current = admissions.required(job.admission.observationId());
        if (current.registrationFinalized()) {
            return;
        }
        if (current.rejection().isPresent()) {
            finishRejection(current);
            return;
        }
        if (preparation.hasPromotionPlan(current.observationId())) {
            defer(current, Failure.PROMOTION_RECOVERY_FAILED.name(),
                    current.execution().attempts() >= maxAttempts, IngestDiagnosticCodes.RECOVERY_FAILED);
            return;
        }
        if (current.phase() == DocumentAdmissionPhase.TERMINAL) {
            return;
        }
        if (admissions.promotionUncertain(current)) {
            defer(current, Failure.PROMOTION_OUTCOME_UNKNOWN.name(), true, IngestDiagnosticCodes.RECOVERY_FAILED);
            return;
        }
        var code = rejectionCode(current, job.command != null);
        boolean exhausted = current.execution().attempts() >= maxAttempts;
        if (current.claimedEvidence().isEmpty()) {
            defer(current, job.claimFailure, exhausted, IngestDiagnosticCodes.CLAIM_FAILED);
        } else if (!exhausted && !oversize(current)) {
            defer(current, code.name(), false, IngestDiagnosticCodes.SOURCE_UNREADABLE);
        } else {
            beginRejection(current, code);
        }
    }

    private DocumentRejection.Code rejectionCode(DocumentAdmission admission, boolean preparationReached) {
        if (oversize(admission)) {
            return DocumentRejection.Code.SOURCE_TOO_LARGE;
        }
        return preparationReached ? DocumentRejection.Code.DOCUMENT_PREPARATION_FAILED
                : DocumentRejection.Code.SOURCE_HASH_FAILED;
    }

    private void beginRejection(DocumentAdmission current, DocumentRejection.Code code) {
        DocumentAdmission rejecting;
        try {
            rejecting = admissions.beginRejection(current, code);
        } catch (RuntimeException failure) {
            defer(current, ownershipReason(failure, Failure.REJECTION_OWNERSHIP_UNCONFIRMED), true, IngestDiagnosticCodes.DEAD_LETTER_FAILED);
            return;
        }
        finishRejection(rejecting);
    }

    private boolean finishRejection(DocumentAdmission current) {
        try {
            preparation.rejectedBeforePromotion(current.observationId());
            admissions.finishRejection(current);
        } catch (RuntimeException failure) {
            var durable = admissions.required(current.observationId());
            if (durable.phase() != DocumentAdmissionPhase.TERMINAL) {
                // Disposition gets additional attempts after the preparation budget is spent.
                defer(durable, ownershipReason(failure, Failure.REJECTION_INCOMPLETE),
                        durable.execution().attempts() >= 2L * maxAttempts,
                        IngestDiagnosticCodes.DEAD_LETTER_FAILED);
            }
            return false;
        }
        report(current.observationId().value(), current.rejection().orElseThrow().code().name(),
                IngestDiagnosticCodes.SOURCE_UNREADABLE);
        return true;
    }

    private static String ownershipReason(RuntimeException failure, Failure fallback) {
        return (failure instanceof DocumentOwnershipException conflict ? conflict.failure() : fallback).name();
    }

    private void defer(DocumentAdmission admission, String reason, boolean hold, IngestDiagnosticCodes code) {
        Instant due = hold ? DocumentExecutionState.HELD : clock.instant().plus(backoff);
        admissions.retryExecution(admission.observationId(), due, reason);
        report(admission.observationId().value(), reason, code);
    }

    private void report(String source, RuntimeException failure) {
        report(source, failure.getClass().getSimpleName(), IngestDiagnosticCodes.RECOVERY_FAILED);
    }

    private void report(String source, String reason, IngestDiagnosticCodes code) {
        synchronized (monitor) { if (!failed) { lastFailure = reason; } }
        try { diagnostics.emit(factory.create(code)
                .with("source", source).with("reason", reason).build()); }
        catch (RuntimeException ignored) { /* Diagnostic observers do not own durable execution. */ }
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
            long bytes = admittedBytes(pending);
            long age = pending.stream().map(DocumentAdmission::createdAt)
                    .mapToLong(at -> Math.max(0, Duration.between(at, clock.instant()).toMillis())).max().orElse(0);
            long ready = jobs.values().stream().filter(job -> job.ready && !job.promoting).count();
            long promoting = jobs.values().stream().filter(job -> job.promoting).count();
            long blocked = pending.stream().filter(value -> value.execution().retryAfter().equals(DocumentExecutionState.HELD)).count();
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
        private String claimFailure = Failure.CLAIM_UNCONFIRMED.name();
        private boolean ready;
        private boolean promoting;
        private boolean promotionStarted;
        private Job(DocumentAdmission admission) { this.admission = admission; }
    }
}
