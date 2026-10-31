package com.iocextractor.platform.diagnostics;

import com.iocextractor.platform.diagnostics.result.BoundedDiagnosticCollector;
import com.iocextractor.platform.diagnostics.result.BoundedNotification;
import com.iocextractor.platform.diagnostics.result.FailurePolicy;
import com.iocextractor.platform.diagnostics.result.Notification;
import com.iocextractor.platform.diagnostics.result.Result;
import com.iocextractor.platform.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.platform.diagnostics.sink.ResilientDiagnosticSink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent behavior checks for docs/guides/libraries/diagnostics.md. */
class DiagnosticsLibraryExampleTest {
    private enum ImportCode implements DiagnosticCode {
        BAD_ROW, SUPPRESSED;

        @Override public String id() { return "import/" + name().toLowerCase(java.util.Locale.ROOT); }
        @Override public DiagnosticSeverity defaultSeverity() { return DiagnosticSeverity.WARN; }
        @Override public DiagnosticImpact impact() { return DiagnosticImpact.ELEMENT; }
    }

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    private final DiagnosticFactory factory = new DiagnosticFactory(clock);

    @Test
    void results_and_pure_policy_leave_enforcement_to_the_service() {
        var warning = factory.create(ImportCode.BAD_ROW).with("row", 7).build();
        var result = Result.success("accepted").withDiagnostic(warning).map(String::toUpperCase);
        var notification = new Notification().addAll(result.diagnostics());
        assertThat(result.value()).isEqualTo("ACCEPTED");
        assertThat(FailurePolicy.failFast().evaluate(notification).shouldStop()).isFalse();
        assertThat(Result.success(null).map(value -> { throw new AssertionError("must not map null"); }).value()).isNull();
    }

    @Test
    void stage_and_run_keep_exact_counts_when_detail_is_replaced() {
        var stage = new BoundedDiagnosticCollector(1, ImportCode.SUPPRESSED);
        for (var severity : List.of(DiagnosticSeverity.WARN, DiagnosticSeverity.WARN,
                DiagnosticSeverity.ERROR, DiagnosticSeverity.FATAL)) {
            stage.add(factory.create(ImportCode.BAD_ROW).severity(severity).build());
        }
        var batch = stage.batch();
        assertThat(batch.retained()).extracting(Diagnostic::severity)
                .containsExactly(DiagnosticSeverity.WARN, DiagnosticSeverity.ERROR, DiagnosticSeverity.FATAL);
        assertThat(batch.summary().total()).isEqualTo(4);
        assertThat(batch.summary().suppressed()).isEqualTo(1);

        var delivered = new CollectingDiagnosticSink();
        var run = new BoundedNotification(1, factory, ImportCode.SUPPRESSED);
        run.addAll(batch, delivered::emit);
        assertThat(run.batch().retained()).extracting(Diagnostic::severity).containsExactly(DiagnosticSeverity.FATAL);
        assertThat(run.summary().total()).isEqualTo(4);
        assertThat(run.summary().suppressed()).isEqualTo(3);
        assertThat(delivered.diagnostics()).hasSize(3);
        var marker = run.diagnostics().getLast();
        assertThat(marker.code()).isEqualTo(ImportCode.SUPPRESSED);
        assertThat(marker.severity()).isEqualTo(DiagnosticSeverity.ERROR);
        assertThat(marker.context()).containsEntry("suppressedCount", 3L)
                .containsEntry("suppressedBySeverity", java.util.Map.of("WARN", 2L, "ERROR", 1L));
        assertThat(FailurePolicy.collectAndContinue().evaluate(new Notification().addAll(run.diagnostics()))
                .shouldStop()).isTrue();
    }

    @Test
    void delivery_failure_is_reported_through_the_services_callback() {
        var failures = new ArrayList<RuntimeException>();
        var sink = new ResilientDiagnosticSink(value -> { throw new IllegalStateException("report unavailable"); },
                (value, failure) -> failures.add(failure));
        sink.emit(factory.create(ImportCode.BAD_ROW).build());
        assertThat(failures).singleElement().satisfies(failure -> assertThat(failure).hasMessage("report unavailable"));
    }
}
