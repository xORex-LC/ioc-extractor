package com.iocextractor.application.sync;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishExecutionResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.port.in.sync.PublishCompletedSliceCommand;
import com.iocextractor.application.port.out.sync.CompletedSliceCatalog;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.port.out.sync.PublishLedger;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.codes.SyncDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Publishes verified local export slices to all configured remote targets through the publish ledger.
 */
public final class ArtifactPublishService implements ArtifactPublishUseCase {

    private static final String SUCCESS_MARKER = "_SUCCESS";
    private static final java.time.Duration IN_PROGRESS_RECOVERY_TIMEOUT = java.time.Duration.ofMinutes(5);

    private final CompletedSliceCatalog sliceCatalog;
    private final PublishLedger ledger;
    private final FileTransport transport;
    private final List<PublishTarget> targets;
    private final Retrier retrier;
    private final DiagnosticSink diagnostics;
    private final Clock clock;
    private final SyncDiagnosticReporter diagnosticReporter;

    /** Creates a framework-free publish saga use case. */
    public ArtifactPublishService(CompletedSliceCatalog sliceCatalog,
                                  PublishLedger ledger,
                                  FileTransport transport,
                                  List<PublishTarget> targets,
                                  Retrier retrier,
                                  DiagnosticSink diagnostics,
                                  Clock clock,
                                  SyncDiagnosticReporter diagnosticReporter) {
        this.sliceCatalog = Objects.requireNonNull(sliceCatalog, "sliceCatalog");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.retrier = Objects.requireNonNull(retrier, "retrier");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnosticReporter = Objects.requireNonNull(diagnosticReporter, "diagnosticReporter");
    }

    @Override
    public ArtifactPublishResult reconcile(ArtifactPublishCommand command) {
        Objects.requireNonNull(command, "command");
        LedgerCounters counters = new LedgerCounters();
        for (String profile : selectedProfiles(command)) {
            List<PublishTarget> profileTargets = targetsForProfile(profile, command);
            if (profileTargets.isEmpty()) {
                throw new IllegalArgumentException(
                        "Selected sync publish target does not belong to profile: " + profile);
            }
            reconcileProfile(profile, profileTargets, command.dryRun(), counters);
        }
        return counters.toResult();
    }

    @Override
    public ArtifactPublishExecutionResult publish(ArtifactPublishCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.dryRun()) {
            return ArtifactPublishExecutionResult.empty();
        }
        ExecutionCounters counters = new ExecutionCounters();
        List<PublishRecord> retryable = selectedRetryableRecords(command);
        for (PublishRecord record : retryable) {
            counters.attempted++;
            findSlice(record, counters).ifPresent(slice -> apply(record, slice, false, true, counters));
        }
        return counters.toResult();
    }

    @Override
    public ArtifactPublishExecutionResult publishCompletedSlice(PublishCompletedSliceCommand command) {
        Objects.requireNonNull(command, "command");
        ExecutionCounters counters = new ExecutionCounters();
        Optional<CompletedSlice> found = sliceCatalog.find(command.profile(), command.sliceName());
        if (found.isEmpty()) {
            return counters.toResult();
        }
        CompletedSlice slice = found.orElseThrow();
        if (!slice.sliceId().equals(command.sliceId())) {
            throw new IllegalStateException("Completed slice id does not match requested event");
        }
        List<PublishTarget> profileTargets = targetsForProfile(command.profile(), command.target(), command.endpoint());
        if (profileTargets.isEmpty()) {
            throw new IllegalArgumentException(
                    "Selected sync publish target does not belong to profile: " + command.profile());
        }
        for (PublishTarget target : profileTargets) {
            publishPair(slice, target, false, counters);
        }
        return counters.toResult();
    }

    private void reconcileProfile(String profile,
                                  List<PublishTarget> profileTargets,
                                  boolean dryRun,
                                  LedgerCounters counters) {
        for (String sliceName : sliceCatalog.listCompletedSliceNames(profile)) {
            List<PublishRecord> existing = ledger.findBySliceName(profile, sliceName);
            for (PublishRecord record : existing) {
                if (profileTargets.stream().anyMatch(target -> matches(record, target))) {
                    countState(record, counters);
                }
            }
            List<PublishTarget> missingTargets = profileTargets.stream()
                    .filter(target -> existing.stream().noneMatch(record -> matches(record, target)))
                    .toList();
            if (missingTargets.isEmpty()) {
                continue;
            }
            Optional<CompletedSlice> slice = findSliceForDiscovery(profile, sliceName, counters);
            if (slice.isEmpty()) {
                continue;
            }
            for (PublishTarget target : missingTargets) {
                reconcilePair(slice.orElseThrow(), target, dryRun, counters);
            }
        }
    }

    private Optional<CompletedSlice> findSliceForDiscovery(String profile,
                                                           String sliceName,
                                                           LedgerCounters counters) {
        try {
            return sliceCatalog.find(profile, sliceName);
        } catch (RuntimeException failure) {
            counters.failed++;
            return Optional.empty();
        }
    }

    private List<PublishRecord> selectedRetryableRecords(ArtifactPublishCommand command) {
        List<String> profiles = selectedProfiles(command);
        List<PublishTarget> selected = selectedTargets(command);
        return ledger.findRetryable(clock.instant().minus(IN_PROGRESS_RECOVERY_TIMEOUT)).stream()
                .filter(record -> profiles.contains(record.profile()))
                .filter(record -> selected.stream().anyMatch(target -> matches(record, target)))
                .toList();
    }

    private boolean matches(PublishRecord record, PublishTarget target) {
        return record.targetId().equals(target.targetId())
                && record.endpoint().equals(target.endpoint())
                && record.profile().equals(target.exportProfile());
    }

    private void reconcilePair(CompletedSlice slice,
                               PublishTarget target,
                               boolean dryRun,
                               LedgerCounters counters) {
        Optional<PublishRecord> record = resolveRecord(slice, target, dryRun);
        if (record.isEmpty()) {
            counters.pending++;
            return;
        }
        countState(record.orElseThrow(), counters);
    }

    private void publishPair(CompletedSlice slice,
                             PublishTarget target,
                             boolean dryRun,
                             ExecutionCounters counters) {
        Optional<PublishRecord> record = resolveRecord(slice, target, dryRun);
        if (record.isEmpty()) {
            return;
        }
        apply(record.orElseThrow(), slice, dryRun, false, counters);
    }

    private Optional<CompletedSlice> findSlice(PublishRecord record, ExecutionCounters counters) {
        Optional<CompletedSlice> found;
        try {
            found = sliceCatalog.find(record.profile(), record.sliceName());
        } catch (RuntimeException failure) {
            PublishRecord inProgress = moveToInProgress(record);
            markFailed(inProgress, failureReason(failure.getMessage()), null, counters);
            return Optional.empty();
        }
        if (found.isEmpty()) {
            failInvalidLocalSlice(record, "local slice is missing", counters);
            return Optional.empty();
        }
        CompletedSlice slice = found.orElseThrow();
        if (!slice.sliceId().equals(record.sliceId())
                || !slice.manifestSha256().equals(record.manifestSha256())) {
            failInvalidLocalSlice(record, "local slice no longer matches publish ledger binding", counters);
            return Optional.empty();
        }
        return Optional.of(slice);
    }

    private List<String> selectedProfiles(ArtifactPublishCommand command) {
        if (command.profile().isPresent()) {
            String selected = command.profile().orElseThrow();
            if (targets.stream().noneMatch(target -> target.exportProfile().equals(selected))) {
                throw new IllegalArgumentException("Unknown sync publish profile: " + selected);
            }
            return List.of(selected);
        }
        return selectedTargets(command).stream()
                        .map(PublishTarget::exportProfile)
                        .distinct()
                        .toList();
    }

    private List<PublishTarget> targetsForProfile(String profile, ArtifactPublishCommand command) {
        return selectedTargets(command.target(), command.endpoint()).stream()
                .filter(target -> target.exportProfile().equals(profile))
                .toList();
    }

    private List<PublishTarget> selectedTargets(ArtifactPublishCommand command) {
        return selectedTargets(command.target(), command.endpoint());
    }

    private List<PublishTarget> targetsForProfile(String profile,
                                                  Optional<String> selectedTarget,
                                                  Optional<String> selectedEndpoint) {
        return selectedTargets(selectedTarget, selectedEndpoint).stream()
                .filter(target -> target.exportProfile().equals(profile))
                .toList();
    }

    private List<PublishTarget> selectedTargets(Optional<String> selectedTarget, Optional<String> selectedEndpoint) {
        if (selectedTarget.isEmpty() && selectedEndpoint.isEmpty()) {
            return targets;
        }
        List<PublishTarget> matches = targets.stream()
                .filter(target -> selectedTarget
                        .map(selected -> target.targetId().equals(selected))
                        .orElse(true))
                .filter(target -> selectedEndpoint
                        .map(endpoint -> target.endpoint().equals(endpoint))
                        .orElse(true))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No sync publish target matches selection");
        }
        return matches;
    }

    private Optional<PublishRecord> resolveRecord(CompletedSlice slice,
                                                  PublishTarget target,
                                                  boolean dryRun) {
        PublishRecord pending = PublishRecord.pending(
                slice.sliceId(),
                target.targetId(),
                slice.profile(),
                slice.sliceName(),
                slice.manifestSha256(),
                target.endpoint(),
                target.sliceRemotePath(slice.sliceName()),
                clock.instant());
        if (dryRun) {
            return ledger.find(slice.sliceId(), target.targetId());
        }
        return Optional.of(ledger.ensurePending(pending));
    }

    private void countState(PublishRecord record, LedgerCounters counters) {
        switch (record.status()) {
            case SUCCEEDED -> counters.succeeded++;
            case ABANDONED -> counters.abandoned++;
            case FAILED -> counters.failed++;
            case PENDING, IN_PROGRESS -> counters.pending++;
        }
    }

    private void apply(PublishRecord record,
                       CompletedSlice slice,
                       boolean dryRun,
                       boolean attemptAlreadyCounted,
                       ExecutionCounters counters) {
        switch (record.status()) {
            case SUCCEEDED, ABANDONED -> { }
            case IN_PROGRESS, PENDING, FAILED -> {
                if (!attemptAlreadyCounted) {
                    counters.attempted++;
                }
                publishRetryable(record, slice, dryRun, counters);
            }
        }
    }

    private void publishRetryable(PublishRecord record,
                                  CompletedSlice slice,
                                  boolean dryRun,
                                  ExecutionCounters counters) {
        if (dryRun) {
            return;
        }
        PublishRecord inProgress = moveToInProgress(record);
        RemoteMarker marker;
        try {
            marker = retrier.execute(() -> readRemoteMarker(inProgress));
        } catch (RemoteTransportException failure) {
            markFailed(inProgress, failure.getMessage(), null, counters);
            diagnosticReporter.report(failure, inProgress.endpoint(), inProgress.remotePath(), "publish-marker-read");
            return;
        } catch (IllegalStateException failure) {
            markFailed(inProgress, failure.getMessage(), null, counters);
            return;
        }
        if (marker.present()) {
            recoverExistingCommit(inProgress, slice, marker, counters);
            return;
        }
        publishNewSlice(inProgress, slice, counters);
    }

    private void recoverExistingCommit(PublishRecord record,
                                       CompletedSlice slice,
                                       RemoteMarker marker,
                                       ExecutionCounters counters) {
        if (marker.matches(slice.manifestSha256())) {
            markSucceeded(record,
                    "remote marker matched existing manifest " + slice.manifestSha256(), counters);
            counters.recovered++;
            return;
        }
        String reason = "remote _SUCCESS mismatch";
        markFailed(record, reason, marker.content(), counters);
        emitPublishVerifyFailed(record, reason);
    }

    private void publishNewSlice(PublishRecord record,
                                 CompletedSlice slice,
                                 ExecutionCounters counters) {
        PublishReceipt receipt;
        try {
            receipt = retrier.execute(() -> transport.publishAtomically(
                    new PublishAtomicallyRequest(
                            record.endpoint(), record.remotePath(), slice.directory(), SUCCESS_MARKER)));
        } catch (RemoteTransportException | IllegalStateException failure) {
            markFailed(record, failure.getMessage(), null, counters);
            if (failure instanceof RemoteTransportException transportFailure) {
                diagnosticReporter.report(
                        transportFailure, record.endpoint(), record.remotePath(), "publish");
            }
            return;
        }
        markSucceeded(record, receipt.verification(), counters);
    }

    private void markSucceeded(PublishRecord record,
                               String remoteVerification,
                               ExecutionCounters counters) {
        ledger.transition(record.sliceId(), record.targetId(),
                PublishStatus.IN_PROGRESS, PublishStatus.SUCCEEDED, null, remoteVerification);
        counters.succeeded++;
    }

    private void markFailed(PublishRecord record,
                            String reason,
                            String remoteVerification,
                            ExecutionCounters counters) {
        ledger.transition(record.sliceId(), record.targetId(),
                PublishStatus.IN_PROGRESS, PublishStatus.FAILED, failureReason(reason), remoteVerification);
        counters.failed++;
    }

    private String failureReason(String reason) {
        return reason == null || reason.isBlank() ? "remote publish failed without detail" : reason;
    }

    private PublishRecord moveToInProgress(PublishRecord record) {
        if (record.status() == PublishStatus.IN_PROGRESS) {
            return record;
        }
        return ledger.transition(record.sliceId(), record.targetId(),
                record.status(), PublishStatus.IN_PROGRESS, null, null);
    }

    private RemoteMarker readRemoteMarker(PublishRecord record) {
        String markerPath = record.remotePath() + "/" + SUCCESS_MARKER;
        if (transport.stat(record.endpoint(), markerPath).isEmpty()) {
            return RemoteMarker.absent();
        }
        try {
            Path temp = Files.createTempFile("ioc-publish-marker-", ".txt");
            try {
                transport.get(record.endpoint(), markerPath, temp);
                return RemoteMarker.present(Files.readString(temp, StandardCharsets.US_ASCII).strip());
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read remote publish marker", e);
        }
    }

    private void emitPublishVerifyFailed(PublishRecord record, String reason) {
        diagnostics.emit(Diagnostic.builder(SyncDiagnosticCodes.PUBLISH_VERIFY_FAILED, clock)
                .with("sliceId", record.sliceId())
                .with("targetId", record.targetId())
                .with("reason", reason)
                .build());
    }

    private void emitLocalSliceInvalid(PublishRecord record, String reason) {
        var builder = Diagnostic.builder(SyncDiagnosticCodes.LOCAL_SLICE_INVALID, clock)
                .with("profile", record.profile())
                .with("sliceName", record.sliceName())
                .with("sliceId", record.sliceId())
                .with("targetId", record.targetId())
                .with("reason", reason);
        diagnostics.emit(builder.build());
    }

    private void failInvalidLocalSlice(PublishRecord record,
                                       String reason,
                                       ExecutionCounters counters) {
        PublishRecord inProgress = moveToInProgress(record);
        markFailed(inProgress, reason, null, counters);
        emitLocalSliceInvalid(inProgress, reason);
    }

    private static final class LedgerCounters {
        private int pending;
        private int succeeded;
        private int failed;
        private int abandoned;

        private ArtifactPublishResult toResult() {
            return new ArtifactPublishResult(pending, succeeded, failed, abandoned);
        }
    }

    private static final class ExecutionCounters {
        private int attempted;
        private int succeeded;
        private int recovered;
        private int failed;

        private ArtifactPublishExecutionResult toResult() {
            return new ArtifactPublishExecutionResult(attempted, succeeded, recovered, failed);
        }
    }

    private record RemoteMarker(boolean present, String content) {
        private static RemoteMarker absent() {
            return new RemoteMarker(false, null);
        }

        private static RemoteMarker present(String content) {
            return new RemoteMarker(true, Objects.requireNonNull(content, "content"));
        }

        private boolean matches(String manifestSha256) {
            return present && content.equals(manifestSha256);
        }
    }
}
