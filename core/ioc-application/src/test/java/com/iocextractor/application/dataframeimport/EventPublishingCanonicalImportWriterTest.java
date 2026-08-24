package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.artifact.CanonicalArtifactsChanged;
import com.iocextractor.application.artifact.lifecycle.CanonicalDeadlineScheduleChanged;
import com.iocextractor.application.artifact.lifecycle.MutableArtifactProjectionRequired;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportStageReference;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.platform.events.ControlEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventPublishingCanonicalImportWriterTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    @Test
    void publishesDeadlineProjectionAndArtifactHintsOnlyAfterNewCommit() {
        List<ControlEvent> events = new ArrayList<>();
        CanonicalImportResult committed = result(ImportPromotionOutcome.COMMITTED);
        var writer = new EventPublishingCanonicalImportWriter(
                ignored -> committed, events::add);

        assertThat(writer.promote(command())).isEqualTo(committed);

        assertThat(events).filteredOn(CanonicalDeadlineScheduleChanged.class::isInstance)
                .hasSize(2);
        assertThat(events).filteredOn(MutableArtifactProjectionRequired.class::isInstance)
                .hasSize(1);
        assertThat(events).filteredOn(CanonicalArtifactsChanged.class::isInstance)
                .hasSize(1);
    }

    @Test
    void replayAndPublisherFailureCannotChangeDurableWriterOutcome() {
        CanonicalImportResult replay = result(ImportPromotionOutcome.ALREADY_COMMITTED);
        var replaying = new EventPublishingCanonicalImportWriter(
                ignored -> replay, ignored -> {
                    throw new AssertionError("Replay must not publish");
                });
        assertThat(replaying.promote(command())).isEqualTo(replay);

        CanonicalImportResult committed = result(ImportPromotionOutcome.COMMITTED);
        var failingPublisher = new EventPublishingCanonicalImportWriter(
                ignored -> committed, ignored -> {
                    throw new IllegalStateException("lossy hint rejected");
                });
        assertThat(failingPublisher.promote(command())).isEqualTo(committed);
    }

    private CanonicalImportResult result(ImportPromotionOutcome outcome) {
        return new CanonicalImportResult(
                outcome, 1, 0, 1,
                Set.of("masks"), Set.of("masks", "hashes"),
                Map.of("masks", 7L), NOW);
    }

    private CanonicalImportCommand command() {
        ImportSha256 digest = new ImportSha256("a".repeat(64));
        return new CanonicalImportCommand(
                new ImportDeliveryId("delivery-events"),
                new ImportDeliverySequence(1),
                new ImportSourceId("source-a"),
                new ImportSnapshot(new ImportSnapshotReference("snapshot:a"), digest, 1),
                new ImportContractPin(
                        new ImportContractId("contract-a"), 1,
                        new ImportContractFingerprint("b".repeat(64))),
                new ImportStage(
                        new ImportStageReference("stage:a"), digest, 1, 1, 0));
    }
}
