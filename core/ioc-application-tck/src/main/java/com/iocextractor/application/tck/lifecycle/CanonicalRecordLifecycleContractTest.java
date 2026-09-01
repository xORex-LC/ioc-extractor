package com.iocextractor.application.tck.lifecycle;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.ActiveArtifactRecord;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptContext;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleTimeSource;
import com.iocextractor.application.artifact.lifecycle.LifecycleWriteResult;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.lifecycle.RecordValidityPolicy;
import com.iocextractor.application.port.out.artifact.lifecycle.ActiveArtifactReader;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.application.port.out.artifact.lifecycle.ExpiredArtifactStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable behavioral contract for canonical record lifecycle storage adapters.
 *
 * <p>The contract covers half-open active reads, confirmation renewal,
 * observation idempotency, due-record replacement, revision separation,
 * bounded expiry and identity non-reuse. Adapter-specific SQL, migration and
 * concurrency evidence remains in the implementing adapter module.
 */
@ContractTest
public abstract class CanonicalRecordLifecycleContractTest {

    protected static final String ARTIFACT = "masks";
    protected static final Instant START = Instant.parse("2026-08-16T00:00:00Z");
    protected static final Duration TTL = Duration.ofHours(1);

    /**
     * Creates one isolated fixture.
     *
     * <p>The fixture must configure a public id slot for {@value #ARTIFACT}; its
     * starting value and direction are implementation choices, but allocated
     * values must never be reused.
     *
     * @param timeSource mutable test time source sampled by canonical write transactions
     * @param policy validity strategy invoked after write ownership is acquired
     * @return ports backed by the same isolated store
     */
    protected abstract LifecycleFixture createFixture(LifecycleTimeSource timeSource,
                                                       RecordValidityPolicy policy);

    @Test
    void creates_then_renews_one_active_lifecycle_without_advancing_public_revision() {
        var timeSource = new AdjustableTimeSource(START);
        LifecycleFixture fixture = fixture(timeSource);

        LifecycleWriteResult created = fixture.writer().confirm(
                command("observation-create", row("row-a", "example.test")));
        ActiveArtifactRecord original = onlyRecord(fixture, START.plusSeconds(1));
        String originalPublicId = publicId(original);

        timeSource.set(START.plus(Duration.ofMinutes(30)));
        LifecycleWriteResult renewed = fixture.writer().confirm(
                command("observation-renew", row("row-a", "example.test")));
        ActiveArtifactRecord current = onlyRecord(fixture, START.plus(Duration.ofMinutes(30)));

        assertThat(created.created()).isEqualTo(1);
        assertThat(created.renewed()).isZero();
        assertThat(created.restarted()).isZero();
        assertThat(created.artifactRevision()).isPositive();
        assertThat(renewed.created()).isZero();
        assertThat(renewed.renewed()).isEqualTo(1);
        assertThat(renewed.restarted()).isZero();
        assertThat(renewed.artifactRevision()).isEqualTo(created.artifactRevision());
        assertThat(renewed.requiredProjectionGeneration())
                .isEqualTo(created.requiredProjectionGeneration());
        assertThat(current.lifecycle().id()).isEqualTo(original.lifecycle().id());
        assertThat(current.lifecycle().firstConfirmedAt()).isEqualTo(original.lifecycle().firstConfirmedAt());
        assertThat(current.lifecycle().lastConfirmedAt().value())
                .isEqualTo(START.plus(Duration.ofMinutes(30)));
        assertThat(current.lifecycle().deadline().validUntil())
                .isEqualTo(START.plus(Duration.ofMinutes(90)));
        assertThat(publicId(current)).isEqualTo(originalPublicId);
    }

    @Test
    void alternate_supplied_row_key_with_same_match_alias_renews_instead_of_duplicating() {
        var timeSource = new AdjustableTimeSource(START);
        LifecycleFixture fixture = fixture(timeSource);
        fixture.writer().confirm(command("observation-alias-first", row("producer-key-a", "shared.example")));

        timeSource.set(START.plus(Duration.ofMinutes(10)));
        LifecycleWriteResult result = fixture.writer().confirm(
                command("observation-alias-second", row("producer-key-b", "shared.example")));

        assertThat(result.created()).isZero();
        assertThat(result.renewed()).isOne();
        assertThat(result.restarted()).isZero();
        assertThat(fixture.reader().loadActive(
                ARTIFACT, EffectiveTime.at(START.plus(Duration.ofMinutes(10)))).records())
                .hasSize(1);
    }

    @Test
    void exact_deadline_is_inactive_and_reconfirmation_creates_new_identities() {
        var timeSource = new AdjustableTimeSource(START);
        LifecycleFixture fixture = fixture(timeSource);

        LifecycleWriteResult first = fixture.writer().confirm(
                command("observation-first", row("row-a", "example.test")));
        ActiveArtifactRecord oldRecord = onlyRecord(fixture, START);
        Instant deadline = oldRecord.lifecycle().deadline().validUntil();

        assertThat(fixture.reader().loadActive(ARTIFACT, EffectiveTime.at(deadline)).records())
                .isEmpty();

        timeSource.set(deadline);
        LifecycleWriteResult restarted = fixture.writer().confirm(
                command("observation-after-deadline", row("row-a", "example.test")));
        ActiveArtifactRecord newRecord = onlyRecord(fixture, deadline);

        assertThat(restarted.created()).isZero();
        assertThat(restarted.renewed()).isZero();
        assertThat(restarted.restarted()).isEqualTo(1);
        assertThat(restarted.publicRowsInserted()).isEqualTo(1);
        assertThat(restarted.artifactRevision()).isEqualTo(first.artifactRevision() + 1);
        assertThat(newRecord.lifecycle().id()).isNotEqualTo(oldRecord.lifecycle().id());
        assertThat(publicId(newRecord)).isNotEqualTo(publicId(oldRecord));
    }

    @Test
    void replaying_one_observation_does_not_confirm_the_record_twice() {
        var timeSource = new AdjustableTimeSource(START);
        LifecycleFixture fixture = fixture(timeSource);
        CanonicalArtifactConfirmation confirmation =
                command("observation-replay", row("row-a", "example.test"));

        LifecycleWriteResult committed = fixture.writer().confirm(confirmation);
        ActiveArtifactRecord original = onlyRecord(fixture, START);
        timeSource.set(START.plus(Duration.ofMinutes(45)));

        LifecycleWriteResult replayed = fixture.writer().confirm(confirmation);
        ActiveArtifactRecord current = onlyRecord(fixture, START.plus(Duration.ofMinutes(45)));

        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.effectiveTime()).isEqualTo(committed.effectiveTime());
        assertThat(replayed.artifactRevision()).isEqualTo(committed.artifactRevision());
        assertThat(timeSource.samples()).isEqualTo(1);
        assertThat(current.lifecycle()).isEqualTo(original.lifecycle());
        assertThat(publicId(current)).isEqualTo(publicId(original));
    }

    @Test
    void one_multi_row_transaction_samples_effective_time_once() {
        var timeSource = new AdjustableTimeSource(START);
        LifecycleFixture fixture = fixture(timeSource);

        LifecycleWriteResult result = fixture.writer().confirm(command(
                "observation-one-time",
                row("row-a", "a.example"),
                row("row-b", "b.example")));

        assertThat(result.effectiveTime()).isEqualTo(EffectiveTime.at(START));
        assertThat(result.confirmedRecords()).isEqualTo(2);
        assertThat(timeSource.samples()).isEqualTo(1);
    }

    @Test
    void expiry_is_bounded_advances_projection_work_and_preserves_revision() {
        var timeSource = new AdjustableTimeSource(START);
        LifecycleFixture fixture = fixture(timeSource);

        LifecycleWriteResult written = fixture.writer().confirm(command(
                "observation-batch",
                row("row-a", "a.example"),
                row("row-b", "b.example")));
        Instant deadline = START.plus(TTL);

        var firstBatch = fixture.expiredStore().expireDue(
                ARTIFACT, EffectiveTime.at(deadline), 1);
        var secondBatch = fixture.expiredStore().expireDue(
                ARTIFACT, EffectiveTime.at(deadline), 1);

        assertThat(firstBatch.expired()).isEqualTo(1);
        assertThat(firstBatch.moreDue()).isTrue();
        assertThat(firstBatch.artifactRevision()).isEqualTo(written.artifactRevision());
        assertThat(firstBatch.requiredProjectionGeneration())
                .isGreaterThan(written.requiredProjectionGeneration());
        assertThat(secondBatch.expired()).isEqualTo(1);
        assertThat(secondBatch.moreDue()).isFalse();
        assertThat(secondBatch.artifactRevision()).isEqualTo(written.artifactRevision());
        assertThat(fixture.reader().loadActive(ARTIFACT, EffectiveTime.at(deadline)).records())
                .isEmpty();
        assertThat(fixture.expiredStore().nearestDeadline()).isEmpty();
    }

    @Test
    void physically_deleted_public_id_is_never_reused() {
        var timeSource = new AdjustableTimeSource(START);
        LifecycleFixture fixture = fixture(timeSource);

        fixture.writer().confirm(command("observation-old", row("row-old", "old.example")));
        String oldPublicId = publicId(onlyRecord(fixture, START));
        Instant deadline = START.plus(TTL);
        fixture.expiredStore().expireDue(ARTIFACT, EffectiveTime.at(deadline), 10);

        timeSource.set(deadline);
        fixture.writer().confirm(command("observation-new", row("row-new", "new.example")));
        String newPublicId = publicId(onlyRecord(fixture, deadline));

        assertThat(newPublicId).isNotEqualTo(oldPublicId);
    }

    private LifecycleFixture fixture(AdjustableTimeSource timeSource) {
        return createFixture(timeSource, new FixedRecordValidityPolicy(TTL));
    }

    private ActiveArtifactRecord onlyRecord(LifecycleFixture fixture, Instant asOf) {
        return fixture.reader().loadActive(ARTIFACT, EffectiveTime.at(asOf)).records().getFirst();
    }

    private String publicId(ActiveArtifactRecord record) {
        return record.row().value("id");
    }

    private CanonicalArtifactConfirmation command(String observation, RowSpec... rows) {
        List<CanonicalRecordConfirmation> confirmations = java.util.Arrays.stream(rows)
                .map(this::confirmation)
                .toList();
        return new CanonicalArtifactConfirmation(
                new ObservationId(observation),
                "test-source",
                new ConfirmationReceiptContext(
                        new ConfirmationReceiptId("receipt-" + observation),
                        "test-policy-v1",
                        1,
                        Duration.ofDays(30)),
                ARTIFACT,
                List.of("id", "value", "source"),
                confirmations);
    }

    private CanonicalRecordConfirmation confirmation(RowSpec spec) {
        var values = new LinkedHashMap<String, String>();
        values.put("id", null);
        values.put("value", spec.value());
        values.put("source", "test-feed");
        var prepared = new PreparedArtifactRow(
                ArtifactRow.ordered(values), Optional.of("id"));
        return new CanonicalRecordConfirmation(new ArtifactRowKey(spec.key()), prepared);
    }

    private RowSpec row(String key, String value) {
        return new RowSpec(key, value);
    }

    /** Ports backed by the same isolated lifecycle store. */
    public record LifecycleFixture(CanonicalArtifactWriter writer,
                                   ActiveArtifactReader reader,
                                   ExpiredArtifactStore expiredStore) {

        /** Validates the fixture. */
        public LifecycleFixture {
            Objects.requireNonNull(writer, "writer");
            Objects.requireNonNull(reader, "reader");
            Objects.requireNonNull(expiredStore, "expiredStore");
        }
    }

    private record RowSpec(String key, String value) {
    }

    private static final class AdjustableTimeSource implements LifecycleTimeSource {

        private volatile Instant instant;
        private int samples;

        private AdjustableTimeSource(Instant instant) {
            this.instant = Objects.requireNonNull(instant, "instant");
        }

        private void set(Instant instant) {
            this.instant = Objects.requireNonNull(instant, "instant");
        }

        private int samples() {
            return samples;
        }

        @Override
        public EffectiveTime now() {
            samples++;
            return EffectiveTime.at(instant);
        }
    }
}
