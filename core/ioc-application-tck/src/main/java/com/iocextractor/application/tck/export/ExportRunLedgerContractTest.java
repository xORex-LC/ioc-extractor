package com.iocextractor.application.tck.export;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.export.ExportProgress;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import com.iocextractor.application.port.out.export.ExportRunLedger;
import com.iocextractor.diagnostics.DiagnosticException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reusable behavioral contract for durable {@link ExportRunLedger} adapters.
 *
 * <p>The contract covers state-machine CAS semantics, idempotent replay,
 * global single-flight and atomic terminal progress. Infrastructure-specific
 * concurrency and migration behavior remains in each adapter's integration tests.
 */
@ContractTest
public abstract class ExportRunLedgerContractTest {

    protected static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    protected static final String PLAN_HASH = "a".repeat(64);
    protected static final String MANIFEST_HASH = "b".repeat(64);
    protected static final String ARTIFACT_HASH = "c".repeat(64);

    protected abstract LedgerFixture createFixture();

    @Test
    void persists_the_success_path_and_removes_terminal_run_from_incomplete_set() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun started = started("run-success");

        assertThat(ledger.tryStart(started)).contains(started);
        ExportRun staged = ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null);
        ExportRun available = ledger.transition(started.runId(), ExportRunStatus.STAGED,
                ExportRunStatus.AVAILABLE, null, null);
        ExportRun completed = ledger.finish(started.runId(), ExportRunStatus.AVAILABLE,
                ExportRunStatus.COMPLETED, List.of(progress(started, 3)));

        assertThat(staged.status()).isEqualTo(ExportRunStatus.STAGED);
        assertThat(available.status()).isEqualTo(ExportRunStatus.AVAILABLE);
        assertThat(completed.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(completed.manifestSha256()).isEqualTo(MANIFEST_HASH);
        assertThat(ledger.findIncomplete()).isEmpty();
    }

    @Test
    void accepts_same_or_later_checkpoint_as_idempotent_replay() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun started = started("run-replay");
        ledger.tryStart(started);

        ExportRun staged = ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null);
        assertThat(ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null)).isEqualTo(staged);

        ExportRun available = ledger.transition(started.runId(), ExportRunStatus.STAGED,
                ExportRunStatus.AVAILABLE, null, null);
        assertThat(ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null)).isEqualTo(available);

        List<ExportProgress> progress = List.of(progress(started, 2));
        ExportRun completed = ledger.finish(started.runId(), ExportRunStatus.AVAILABLE,
                ExportRunStatus.COMPLETED, progress);
        ExportProgress replayProgress = progress(started, 2, NOW.plusSeconds(30));
        assertThat(ledger.finish(started.runId(), ExportRunStatus.AVAILABLE,
                ExportRunStatus.COMPLETED, List.of(replayProgress))).isEqualTo(completed);
        assertThat(ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null)).isEqualTo(completed);
        assertThat(ledger.transition(started.runId(), ExportRunStatus.STAGED,
                ExportRunStatus.AVAILABLE, null, null)).isEqualTo(completed);
    }

    @Test
    void enforces_global_single_flight_until_active_run_becomes_terminal() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun first = started("run-first");

        assertThat(ledger.tryStart(first)).isPresent();
        assertThat(ledger.tryStart(first)).contains(first);
        assertThat(ledger.tryStart(started("run-blocked"))).isEmpty();
        assertThat(ledger.findIncomplete()).extracting(ExportRun::runId).containsExactly(first.runId());

        ledger.transition(first.runId(), ExportRunStatus.STARTED, ExportRunStatus.FAILED,
                null, "simulated crash recovery failure");

        assertThat(ledger.tryStart(started("run-next"))).isPresent();
    }

    @Test
    void skipped_finish_advances_durable_revision_progress() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun first = started("run-skip-1");
        ledger.tryStart(first);
        ledger.finish(first.runId(), ExportRunStatus.STARTED, ExportRunStatus.SKIPPED,
                List.of(progress(first, 1)));

        ExportRun second = started("run-skip-2");
        ledger.tryStart(second);
        ledger.finish(second.runId(), ExportRunStatus.STARTED, ExportRunStatus.SKIPPED,
                List.of(progress(second, 4)));

        assertThat(fixture.progressStore().findByProfile("reputation"))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.lastRevision()).isEqualTo(4);
                    assertThat(saved.lastSliceId()).isEqualTo(second.runId());
                });
    }

    @Test
    void allows_failure_from_every_active_checkpoint() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();

        ExportRun startedFailure = started("run-fail-started");
        ledger.tryStart(startedFailure);
        assertThat(ledger.transition(startedFailure.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.FAILED, null, "failed before staging").status())
                .isEqualTo(ExportRunStatus.FAILED);

        ExportRun stagedFailure = started("run-fail-staged");
        ledger.tryStart(stagedFailure);
        ledger.transition(stagedFailure.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null);
        assertThat(ledger.transition(stagedFailure.runId(), ExportRunStatus.STAGED,
                ExportRunStatus.FAILED, null, "staged files are corrupt").status())
                .isEqualTo(ExportRunStatus.FAILED);

        ExportRun availableFailure = started("run-fail-available");
        ledger.tryStart(availableFailure);
        ledger.transition(availableFailure.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null);
        ledger.transition(availableFailure.runId(), ExportRunStatus.STAGED,
                ExportRunStatus.AVAILABLE, null, null);
        assertThat(ledger.transition(availableFailure.runId(), ExportRunStatus.AVAILABLE,
                ExportRunStatus.FAILED, null, "bookkeeping failed").status())
                .isEqualTo(ExportRunStatus.FAILED);
    }

    @Test
    void rolls_back_terminal_status_when_progress_cannot_be_applied() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun started = started("run-atomic");
        ledger.tryStart(started);
        ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null);
        ledger.transition(started.runId(), ExportRunStatus.STAGED,
                ExportRunStatus.AVAILABLE, null, null);
        ExportProgress wrongProfile = new ExportProgress(
                "other", "masks", 1, ARTIFACT_HASH, started.runId(), PLAN_HASH, NOW);

        assertThatThrownBy(() -> ledger.finish(started.runId(), ExportRunStatus.AVAILABLE,
                ExportRunStatus.COMPLETED, List.of(wrongProfile)))
                .isInstanceOfSatisfying(IllegalArgumentException.class,
                        failure -> assertThat(failure).hasMessageContaining("profile"));

        assertThat(ledger.find(started.runId())).get()
                .extracting(ExportRun::status)
                .isEqualTo(ExportRunStatus.AVAILABLE);
        assertThat(fixture.progressStore().findByProfile("reputation")).isEmpty();
    }

    @Test
    void rejects_illegal_requested_transition_before_storage_mutation() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun started = started("run-illegal");
        ledger.tryStart(started);

        assertThatThrownBy(() -> ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.COMPLETED, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ledger.find(started.runId())).contains(started);
    }

    @Test
    void reports_incompatible_terminal_or_manifest_replay_as_diagnostic() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun skipped = started("run-terminal-conflict");
        ledger.tryStart(skipped);
        ledger.finish(skipped.runId(), ExportRunStatus.STARTED, ExportRunStatus.SKIPPED,
                List.of(progress(skipped, 1)));

        assertThatThrownBy(() -> ledger.transition(skipped.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null))
                .isInstanceOfSatisfying(DiagnosticException.class,
                        failure -> assertThat(failure)
                                .hasMessageContaining("EXPORT.STATE_TRANSITION_CONFLICT"));

        ExportRun staged = started("run-manifest-conflict");
        ledger.tryStart(staged);
        ledger.transition(staged.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null);
        assertThatThrownBy(() -> ledger.transition(staged.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, "d".repeat(64), null))
                .isInstanceOfSatisfying(DiagnosticException.class,
                        failure -> assertThat(failure)
                                .hasMessageContaining("EXPORT.STATE_TRANSITION_CONFLICT"));
    }

    @Test
    void validates_start_transition_finish_and_progress_arguments_before_mutation() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun started = started("run-validation");
        ExportRun staged = new ExportRun(
                "not-started", started.profile(), ExportRunStatus.STAGED, "not-started-slice",
                started.planHash(), MANIFEST_HASH, NOW, NOW, null);

        assertThatThrownBy(() -> ledger.tryStart(staged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Export ledger accepts only STARTED runs");
        assertThatThrownBy(() -> ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.SKIPPED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Terminal progress transitions must use finish");
        assertThatThrownBy(() -> ledger.transition(started.runId(), ExportRunStatus.AVAILABLE,
                ExportRunStatus.COMPLETED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Terminal progress transitions must use finish");
        assertThatThrownBy(() -> ledger.finish(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, List.of(progress(started, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Export finish requires COMPLETED or SKIPPED");
        assertThatThrownBy(() -> ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("STAGED transition requires manifestSha256");
        assertThatThrownBy(() -> ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, "invalid", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("manifestSha256 must be a lower-case SHA-256 value");
        assertThatThrownBy(() -> ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.FAILED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("FAILED transition requires a reason");
        assertThatThrownBy(() -> ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.FAILED, null, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("FAILED transition requires a reason");
        assertThatThrownBy(() -> ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, "unexpected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only FAILED transition accepts a reason");
        assertThatThrownBy(() -> ledger.find(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("runId must not be blank");

        assertThat(ledger.findIncomplete()).isEmpty();
    }

    @Test
    void rejects_incomplete_or_regressing_terminal_progress_atomically() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun first = started("run-progress-validation");
        ledger.tryStart(first);

        assertThatThrownBy(() -> ledger.finish(first.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.SKIPPED, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("progress");
        assertThatThrownBy(() -> ledger.finish(first.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.SKIPPED, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Terminal export progress must not be empty");
        ExportProgress duplicate = progress(first, 3);
        assertThatThrownBy(() -> ledger.finish(first.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.SKIPPED, List.of(duplicate, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Terminal export progress artifacts must be unique");

        ledger.finish(first.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.SKIPPED, List.of(duplicate));
        ExportRun second = started("run-progress-regression");
        ledger.tryStart(second);

        assertThatThrownBy(() -> ledger.finish(second.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.SKIPPED, List.of(progress(second, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Export progress revision must not move backwards: reputation/masks");
        assertThat(ledger.find(second.runId())).get()
                .extracting(ExportRun::status)
                .isEqualTo(ExportRunStatus.STARTED);
        assertThat(fixture.progressStore().findByProfile(first.profile()))
                .containsExactly(duplicate);
    }

    @Test
    void terminal_replay_requires_exact_persisted_progress_evidence() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun run = started("run-progress-replay");
        ledger.tryStart(run);
        ExportProgress persisted = progress(run, 3);
        ledger.finish(run.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.SKIPPED, List.of(persisted));

        List<ExportProgress> mismatches = List.of(
                new ExportProgress("other", persisted.artifactName(), persisted.lastRevision(),
                        persisted.lastSha256(), persisted.lastSliceId(), persisted.planHash(), NOW),
                new ExportProgress(persisted.profile(), "other", persisted.lastRevision(),
                        persisted.lastSha256(), persisted.lastSliceId(), persisted.planHash(), NOW),
                new ExportProgress(persisted.profile(), persisted.artifactName(), 4,
                        persisted.lastSha256(), persisted.lastSliceId(), persisted.planHash(), NOW),
                new ExportProgress(persisted.profile(), persisted.artifactName(), persisted.lastRevision(),
                        "d".repeat(64), persisted.lastSliceId(), persisted.planHash(), NOW),
                new ExportProgress(persisted.profile(), persisted.artifactName(), persisted.lastRevision(),
                        persisted.lastSha256(), "other-slice", persisted.planHash(), NOW),
                new ExportProgress(persisted.profile(), persisted.artifactName(), persisted.lastRevision(),
                        persisted.lastSha256(), persisted.lastSliceId(), "e".repeat(64), NOW));

        for (ExportProgress mismatch : mismatches) {
            assertThatThrownBy(() -> ledger.finish(run.runId(), ExportRunStatus.STARTED,
                    ExportRunStatus.SKIPPED, List.of(mismatch)))
                    .isInstanceOfSatisfying(DiagnosticException.class,
                            failure -> assertThat(failure)
                                    .hasMessageContaining("EXPORT.STATE_TRANSITION_CONFLICT"));
        }
    }

    @Test
    void duplicate_start_and_failed_replay_require_exact_identity() {
        LedgerFixture fixture = createFixture();
        ExportRunLedger ledger = fixture.ledger();
        ExportRun original = started("run-start-identity");
        ledger.tryStart(original);
        List<ExportRun> mismatches = List.of(
                ExportRun.started(original.runId(), "other", original.sliceName(),
                        original.planHash(), original.startedAt()),
                ExportRun.started(original.runId(), original.profile(), "other-slice",
                        original.planHash(), original.startedAt()),
                ExportRun.started(original.runId(), original.profile(), original.sliceName(),
                        "d".repeat(64), original.startedAt()),
                ExportRun.started(original.runId(), original.profile(), original.sliceName(),
                        original.planHash(), original.startedAt().plusSeconds(1)));

        for (ExportRun mismatch : mismatches) {
            assertThatThrownBy(() -> ledger.tryStart(mismatch))
                    .isInstanceOfSatisfying(DiagnosticException.class,
                            failure -> assertThat(failure)
                                    .hasMessageContaining("EXPORT.STATE_TRANSITION_CONFLICT"));
        }

        ExportRun failed = ledger.transition(original.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.FAILED, null, "first reason");
        assertThat(ledger.transition(original.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.FAILED, MANIFEST_HASH, "first reason")).isEqualTo(failed);
        assertThatThrownBy(() -> ledger.transition(original.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.FAILED, null, "other reason"))
                .isInstanceOfSatisfying(DiagnosticException.class,
                        failure -> assertThat(failure)
                                .hasMessageContaining("EXPORT.STATE_TRANSITION_CONFLICT"));
    }

    protected ExportRun started(String runId) {
        return ExportRun.started(runId, "reputation", "20260628T000000Z__" + runId, PLAN_HASH, NOW);
    }

    protected ExportProgress progress(ExportRun run, long revision) {
        return progress(run, revision, NOW);
    }

    protected ExportProgress progress(ExportRun run, long revision, Instant updatedAt) {
        return new ExportProgress(
                run.profile(), "masks", revision, ARTIFACT_HASH,
                run.runId(), run.planHash(), updatedAt);
    }

    /** Pair of ports backed by the same isolated durable store. */
    public record LedgerFixture(ExportRunLedger ledger, ExportProgressStore progressStore) {
    }
}
