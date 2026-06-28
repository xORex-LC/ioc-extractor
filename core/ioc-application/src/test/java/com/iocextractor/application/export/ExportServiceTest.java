package com.iocextractor.application.export;

import com.iocextractor.application.port.in.export.ExportArtifactsCommand;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static com.iocextractor.application.export.ExportFixtures.CONTENT;
import static com.iocextractor.application.export.ExportFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportServiceTest {

    private final ExportPlan plan = ExportFixtures.plan();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void cheapGateReturnsSkippedWithoutCreatingRunOrReadingSnapshot() {
        var ledger = new ExportFixtures.FakeLedger();
        var writer = new ExportFixtures.FakeWriter();
        var snapshot = new ExportFixtures.CountingSnapshotReader();
        ExportProgress prior = ExportFixtures.progress(3, CONTENT, "slice-old", plan.planHash());
        ExportService service = service(ledger, writer, snapshot,
                List.of(new ArtifactRevision("masks", 3, NOW)), List.of(prior),
                new ExportFixtures.RecordingObserver());

        var result = service.export(new ExportArtifactsCommand("reputation"));

        assertThat(result.status()).isEqualTo(ExportRunStatus.SKIPPED);
        assertThat(result.runId()).isNull();
        assertThat(ledger.starts).isZero();
        assertThat(snapshot.calls).hasValue(0);
        assertThat(writer.stages).isZero();
    }

    @Test
    void completesDurableFormationSagaInCheckpointOrder() {
        var ledger = new ExportFixtures.FakeLedger();
        var writer = new ExportFixtures.FakeWriter();
        var snapshot = new ExportFixtures.CountingSnapshotReader();
        var observer = new ExportFixtures.RecordingObserver();
        ExportService service = service(ledger, writer, snapshot,
                List.of(new ArtifactRevision("masks", 1, NOW)), List.of(), observer);

        var result = service.export(new ExportArtifactsCommand("reputation"));

        assertThat(result.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(result.runId()).isEqualTo("run-new");
        assertThat(result.sliceName()).isEqualTo("20260628T000000Z__run-new");
        assertThat(ledger.runs.get("run-new").status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(ledger.progress).singleElement().satisfies(progress -> {
            assertThat(progress.lastRevision()).isEqualTo(1);
            assertThat(progress.lastSliceId()).isEqualTo("run-new");
        });
        assertThat(writer.publications).isEqualTo(1);
        assertThat(snapshot.calls).hasValue(1);
        assertThat(observer.events).containsExactly(
                "started:STARTED", "written:run-new", "completed:COMPLETED");
    }

    @Test
    void identicalCandidateIsDiscardedButAdvancesSnapshotRevision() {
        var ledger = new ExportFixtures.FakeLedger();
        var writer = new ExportFixtures.FakeWriter();
        writer.revision = 7;
        var snapshot = new ExportFixtures.CountingSnapshotReader();
        ExportProgress prior = ExportFixtures.progress(4, CONTENT, "slice-old", plan.planHash());
        ExportService service = service(ledger, writer, snapshot,
                List.of(new ArtifactRevision("masks", 7, NOW)), List.of(prior),
                new ExportFixtures.RecordingObserver());

        var result = service.export(new ExportArtifactsCommand("reputation"));

        assertThat(result.status()).isEqualTo(ExportRunStatus.SKIPPED);
        assertThat(writer.discards).isEqualTo(1);
        assertThat(writer.publications).isZero();
        assertThat(ledger.progress).singleElement().satisfies(progress -> {
            assertThat(progress.lastRevision()).isEqualTo(7);
            assertThat(progress.lastSliceId()).isEqualTo("slice-old");
            assertThat(progress.lastSha256()).isEqualTo(CONTENT);
        });
    }

    @Test
    void rejectsUnknownProfileAndGlobalSingleFlightBeforeSnapshotIo() {
        var ledger = new ExportFixtures.FakeLedger();
        ledger.allowStart = false;
        var snapshot = new ExportFixtures.CountingSnapshotReader();
        ExportService service = service(ledger, new ExportFixtures.FakeWriter(), snapshot,
                List.of(new ArtifactRevision("masks", 1, NOW)), List.of(),
                new ExportFixtures.RecordingObserver());

        assertThatThrownBy(() -> service.export(new ExportArtifactsCommand("unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown export profile");
        assertThatThrownBy(() -> service.export(new ExportArtifactsCommand("reputation")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already active");
        assertThat(snapshot.calls).hasValue(0);
    }

    private ExportService service(ExportFixtures.FakeLedger ledger,
                                  ExportFixtures.FakeWriter writer,
                                  ExportFixtures.CountingSnapshotReader snapshot,
                                  List<ArtifactRevision> revisions,
                                  List<ExportProgress> progress,
                                  ExportFixtures.RecordingObserver observer) {
        return new ExportService(List.of(plan), artifacts -> revisions, profile -> progress,
                ledger, snapshot, writer, new ExportChangeDetector(), observer, clock, () -> "run-new");
    }
}
