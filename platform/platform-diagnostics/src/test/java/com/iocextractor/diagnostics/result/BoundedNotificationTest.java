package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedNotificationTest {

    private static final DiagnosticFactory FACTORY = new DiagnosticFactory(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    void caps_retained_diagnostics_and_reports_suppression() {
        var notification = new BoundedNotification(1, FACTORY);

        notification.add(FACTORY.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build());
        notification.add(FACTORY.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                .with("item", "two").with("stage", "test").with("reason", "bad").build());

        assertThat(notification.diagnostics()).extracting(diagnostic -> diagnostic.code().id())
                .containsExactly("PIPELINE.ITEM_SKIPPED", "PIPELINE.DIAGNOSTICS_SUPPRESSED");
        assertThat(notification.summary().suppressed()).isOne();
    }

    @Test
    void first_rejecting_diagnostic_is_never_hidden_by_budget() {
        var notification = new BoundedNotification(1, FACTORY);
        notification.add(FACTORY.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build());
        notification.add(FACTORY.create(PipelineDiagnosticCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "failed").build());

        assertThat(notification.diagnostics().getFirst().code()).isEqualTo(PipelineDiagnosticCodes.STAGE_FAILED);
        assertThat(notification.summary().hasErrors()).isTrue();
    }

    @Test
    void fatal_supersedes_retained_error_for_collect_and_continue_policy() {
        var notification = new BoundedNotification(1, FACTORY);
        notification.add(FACTORY.create(PipelineDiagnosticCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "error").build());
        notification.add(FACTORY.create(PipelineDiagnosticCodes.STAGE_FAILED)
                .severity(DiagnosticSeverity.FATAL)
                .with("stage", "test").with("reason", "fatal").build());

        assertThat(notification.diagnostics().getFirst().severity())
                .isEqualTo(DiagnosticSeverity.FATAL);
    }

    @Test
    void operation_diagnostic_stays_visible_without_consuming_or_being_displaced_by_budget() {
        var notification = new BoundedNotification(1, FACTORY);
        var operationWarning = FACTORY.create(IngestDiagnosticCodes.SOURCE_UNREADABLE)
                .severity(DiagnosticSeverity.WARN)
                .with("source", "source-1")
                .with("reason", "lossy projection")
                .build();
        notification.add(operationWarning);
        notification.add(FACTORY.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                .with("item", "one").with("stage", "test").with("reason", "bad").build());
        notification.add(FACTORY.create(PipelineDiagnosticCodes.STAGE_FAILED)
                .with("stage", "test").with("reason", "failed").build());

        assertThat(notification.diagnostics())
                .extracting(diagnostic -> diagnostic.code().id())
                .containsExactly(
                        "INGEST.SOURCE_UNREADABLE",
                        "PIPELINE.STAGE_FAILED",
                        "PIPELINE.DIAGNOSTICS_SUPPRESSED");
        assertThat(notification.summary().total()).isEqualTo(3);
        assertThat(notification.summary().suppressed()).isOne();
    }
}
