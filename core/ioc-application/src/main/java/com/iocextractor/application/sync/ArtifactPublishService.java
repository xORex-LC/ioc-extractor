package com.iocextractor.application.sync;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
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

    private final CompletedSliceCatalog sliceCatalog;
    private final PublishLedger ledger;
    private final FileTransport transport;
    private final List<PublishTarget> targets;
    private final Retrier retrier;
    private final DiagnosticSink diagnostics;
    private final Clock clock;

    /** Creates a framework-free publish saga use case. */
    public ArtifactPublishService(CompletedSliceCatalog sliceCatalog,
                                  PublishLedger ledger,
                                  FileTransport transport,
                                  List<PublishTarget> targets,
                                  Retrier retrier,
                                  DiagnosticSink diagnostics,
                                  Clock clock) {
        this.sliceCatalog = Objects.requireNonNull(sliceCatalog, "sliceCatalog");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.retrier = Objects.requireNonNull(retrier, "retrier");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ArtifactPublishResult reconcile(ArtifactPublishCommand command) {
        return forEachSelectedPair(command,
                (slice, target, counters) -> reconcilePair(slice, target, command.dryRun(), counters));
    }

    @Override
    public ArtifactPublishResult publish(ArtifactPublishCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.dryRun()) {
            return reconcile(command);
        }
        PublishCounters counters = new PublishCounters();
        countNonRetryableRecords(command, counters);
        for (PublishRecord record : selectedRetryableRecords(command)) {
            findSlice(record, counters).ifPresent(slice -> apply(record, slice, false, counters));
        }
        return counters.toResult();
    }

    @Override
    public ArtifactPublishResult publishCompletedSlice(PublishCompletedSliceCommand command) {
        Objects.requireNonNull(command, "command");
        PublishCounters counters = new PublishCounters();
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

    private ArtifactPublishResult forEachSelectedPair(ArtifactPublishCommand command, PairAction action) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(action, "action");
        PublishCounters counters = new PublishCounters();
        for (String profile : selectedProfiles(command)) {
            List<PublishTarget> profileTargets = targetsForProfile(profile, command);
            if (profileTargets.isEmpty()) {
                throw new IllegalArgumentException(
                        "Selected sync publish target does not belong to profile: " + profile);
            }
            for (CompletedSlice slice : sliceCatalog.listCompleted(profile)) {
                for (PublishTarget target : profileTargets) {
                    action.accept(slice, target, counters);
                }
            }
        }
        return counters.toResult();
    }

    private List<PublishRecord> selectedRetryableRecords(ArtifactPublishCommand command) {
        List<String> profiles = selectedProfiles(command);
        List<PublishTarget> selected = selectedTargets(command);
        return ledger.findRetryable().stream()
                .filter(record -> profiles.contains(record.profile()))
                .filter(record -> selected.stream().anyMatch(target -> matches(record, target)))
                .toList();
    }

    private void countNonRetryableRecords(ArtifactPublishCommand command, PublishCounters counters) {
        List<String> profiles = selectedProfiles(command);
        List<PublishTarget> selected = selectedTargets(command);
        ledger.findAll().stream()
                .filter(record -> record.status() == PublishStatus.SUCCEEDED
                        || record.status() == PublishStatus.ABANDONED
                        || record.status() == PublishStatus.IN_PROGRESS)
                .filter(record -> profiles.contains(record.profile()))
                .filter(record -> selected.stream().anyMatch(target -> matches(record, target)))
                .forEach(record -> countState(record, counters));
    }

    private boolean matches(PublishRecord record, PublishTarget target) {
        return record.targetId().equals(target.targetId())
                && record.endpoint().equals(target.endpoint())
                && record.profile().equals(target.exportProfile());
    }

    private void reconcilePair(CompletedSlice slice,
                               PublishTarget target,
                               boolean dryRun,
                               PublishCounters counters) {
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
                             PublishCounters counters) {
        Optional<PublishRecord> record = resolveRecord(slice, target, dryRun);
        if (record.isEmpty()) {
            counters.pending++;
            return;
        }
        apply(record.orElseThrow(), slice, dryRun, counters);
    }

    private Optional<CompletedSlice> findSlice(PublishRecord record, PublishCounters counters) {
        Optional<CompletedSlice> found;
        try {
            found = sliceCatalog.find(record.profile(), record.sliceName());
        } catch (RuntimeException failure) {
            emitLocalSliceInvalid(record, failureReason(failure.getMessage()), failure);
            counters.failed++;
            return Optional.empty();
        }
        if (found.isEmpty()) {
            emitLocalSliceInvalid(record, "local slice is missing", null);
            counters.failed++;
            return Optional.empty();
        }
        CompletedSlice slice = found.orElseThrow();
        if (!slice.sliceId().equals(record.sliceId())
                || !slice.manifestSha256().equals(record.manifestSha256())) {
            emitLocalSliceInvalid(record, "local slice no longer matches publish ledger binding", null);
            counters.failed++;
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

    private void countState(PublishRecord record, PublishCounters counters) {
        switch (record.status()) {
            case SUCCEEDED -> counters.succeeded++;
            case ABANDONED -> counters.abandoned++;
            case FAILED -> counters.failed++;
            case PENDING, IN_PROGRESS -> counters.pending++;
        }
    }

    private void apply(PublishRecord record, CompletedSlice slice, boolean dryRun, PublishCounters counters) {
        switch (record.status()) {
            case SUCCEEDED -> counters.succeeded++;
            case ABANDONED -> counters.abandoned++;
            case IN_PROGRESS, PENDING, FAILED -> publishRetryable(record, slice, dryRun, counters);
        }
    }

    private void publishRetryable(PublishRecord record,
                                  CompletedSlice slice,
                                  boolean dryRun,
                                  PublishCounters counters) {
        if (dryRun) {
            counters.pending++;
            return;
        }
        PublishRecord inProgress = moveToInProgress(record);
        RemoteMarker marker;
        try {
            marker = readRemoteMarker(inProgress);
        } catch (RemoteTransportException | IllegalStateException failure) {
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
                                       PublishCounters counters) {
        if (marker.matches(slice.manifestSha256())) {
            markSucceeded(record,
                    "remote marker matched existing manifest " + slice.manifestSha256(), counters);
            return;
        }
        String reason = "remote _SUCCESS mismatch";
        emitPublishVerifyFailed(record, reason);
        markFailed(record, reason, marker.content(), counters);
    }

    private void publishNewSlice(PublishRecord record,
                                 CompletedSlice slice,
                                 PublishCounters counters) {
        PublishReceipt receipt;
        try {
            receipt = retrier.execute(() -> transport.publishAtomically(
                    new PublishAtomicallyRequest(
                            record.endpoint(), record.remotePath(), slice.directory(), SUCCESS_MARKER)));
        } catch (RemoteTransportException | IllegalStateException failure) {
            markFailed(record, failure.getMessage(), null, counters);
            return;
        }
        markSucceeded(record, receipt.verification(), counters);
    }

    private void markSucceeded(PublishRecord record,
                               String remoteVerification,
                               PublishCounters counters) {
        ledger.transition(record.sliceId(), record.targetId(),
                PublishStatus.IN_PROGRESS, PublishStatus.SUCCEEDED, null, remoteVerification);
        counters.succeeded++;
    }

    private void markFailed(PublishRecord record,
                            String reason,
                            String remoteVerification,
                            PublishCounters counters) {
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

    private void emitLocalSliceInvalid(PublishRecord record, String reason, RuntimeException failure) {
        var builder = Diagnostic.builder(SyncDiagnosticCodes.LOCAL_SLICE_INVALID, clock)
                .with("profile", record.profile())
                .with("sliceName", record.sliceName())
                .with("sliceId", record.sliceId())
                .with("targetId", record.targetId())
                .with("reason", reason);
        if (failure != null) {
            builder.cause(failure);
        }
        diagnostics.emit(builder.build());
    }

    private static final class PublishCounters {
        private int pending;
        private int succeeded;
        private int failed;
        private int abandoned;

        private ArtifactPublishResult toResult() {
            return new ArtifactPublishResult(pending, succeeded, failed, abandoned);
        }
    }

    @FunctionalInterface
    private interface PairAction {
        void accept(CompletedSlice slice, PublishTarget target, PublishCounters counters);
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
