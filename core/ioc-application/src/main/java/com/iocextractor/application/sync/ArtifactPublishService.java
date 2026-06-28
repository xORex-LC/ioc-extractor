package com.iocextractor.application.sync;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
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
    public ArtifactPublishResult publish(ArtifactPublishCommand command) {
        Objects.requireNonNull(command, "command");
        PublishCounters counters = new PublishCounters();
        for (String profile : selectedProfiles(command)) {
            List<PublishTarget> profileTargets = targetsForProfile(profile);
            for (CompletedSlice slice : sliceCatalog.listCompleted(profile)) {
                for (PublishTarget target : profileTargets) {
                    PublishRecord record = reconcile(slice, target);
                    apply(record, slice, command.dryRun(), counters);
                }
            }
        }
        return counters.toResult();
    }

    private List<String> selectedProfiles(ArtifactPublishCommand command) {
        return command.profile()
                .map(List::of)
                .orElseGet(() -> targets.stream()
                        .map(PublishTarget::exportProfile)
                        .distinct()
                        .toList());
    }

    private List<PublishTarget> targetsForProfile(String profile) {
        return targets.stream()
                .filter(target -> target.exportProfile().equals(profile))
                .toList();
    }

    private PublishRecord reconcile(CompletedSlice slice, PublishTarget target) {
        return ledger.ensurePending(PublishRecord.pending(
                slice.sliceId(),
                target.targetId(),
                slice.profile(),
                slice.sliceName(),
                slice.manifestSha256(),
                target.endpoint(),
                target.sliceRemotePath(slice.sliceName()),
                clock.instant()));
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
        try {
            RemoteMarker marker = readRemoteMarker(inProgress);
            if (marker.present()) {
                if (marker.matches(slice.manifestSha256())) {
                    ledger.transition(inProgress.sliceId(), inProgress.targetId(),
                            PublishStatus.IN_PROGRESS, PublishStatus.SUCCEEDED, null,
                            "remote marker matched existing manifest " + slice.manifestSha256());
                    counters.succeeded++;
                    return;
                }
                String reason = "remote _SUCCESS mismatch";
                emitPublishVerifyFailed(inProgress, reason);
                ledger.transition(inProgress.sliceId(), inProgress.targetId(),
                        PublishStatus.IN_PROGRESS, PublishStatus.FAILED, reason, marker.content());
                counters.failed++;
                return;
            }
            PublishReceipt receipt = retrier.execute(() -> transport.publishAtomically(new PublishAtomicallyRequest(
                    inProgress.endpoint(),
                    inProgress.remotePath(),
                    slice.directory(),
                    SUCCESS_MARKER)));
            ledger.transition(inProgress.sliceId(), inProgress.targetId(),
                    PublishStatus.IN_PROGRESS, PublishStatus.SUCCEEDED, null, receipt.verification());
            counters.succeeded++;
        } catch (RemoteTransportException failure) {
            ledger.transition(inProgress.sliceId(), inProgress.targetId(),
                    PublishStatus.IN_PROGRESS, PublishStatus.FAILED, failure.getMessage(), null);
            counters.failed++;
        } catch (RuntimeException failure) {
            ledger.transition(inProgress.sliceId(), inProgress.targetId(),
                    PublishStatus.IN_PROGRESS, PublishStatus.FAILED, failure.getMessage(), null);
            counters.failed++;
        }
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

    private static final class PublishCounters {
        private int pending;
        private int succeeded;
        private int failed;
        private int abandoned;

        private ArtifactPublishResult toResult() {
            return new ArtifactPublishResult(pending, succeeded, failed, abandoned);
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
