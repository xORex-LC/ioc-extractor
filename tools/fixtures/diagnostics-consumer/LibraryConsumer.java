import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticCode;
import com.iocextractor.platform.diagnostics.DiagnosticFactory;
import com.iocextractor.platform.diagnostics.DiagnosticImpact;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import com.iocextractor.platform.diagnostics.result.BoundedDiagnosticCollector;
import com.iocextractor.platform.diagnostics.result.BoundedNotification;
import com.iocextractor.platform.diagnostics.result.FailurePolicy;
import com.iocextractor.platform.diagnostics.result.Notification;
import com.iocextractor.platform.diagnostics.result.Result;
import com.iocextractor.platform.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.platform.diagnostics.sink.ResilientDiagnosticSink;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Runs the consumer-guide examples with service-owned codes and no IOC runtime. */
public final class LibraryConsumer {
    private enum ImportCode implements DiagnosticCode {
        BAD_ROW, SUPPRESSED;

        @Override public String id() { return "import/" + name().toLowerCase(Locale.ROOT); }
        @Override public DiagnosticSeverity defaultSeverity() { return DiagnosticSeverity.WARN; }
        @Override public DiagnosticImpact impact() { return DiagnosticImpact.ELEMENT; }
    }

    private LibraryConsumer() { }

    /**
     * Checks results, bounded aggregation and delivery isolation from the guide.
     * @param args unused
     */
    public static void main(String[] args) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
        var factory = new DiagnosticFactory(clock);
        var warning = factory.create(ImportCode.BAD_ROW).with("row", 7).build();
        results(warning);
        boundedCounts(factory);
        delivery(warning);
        System.out.println("Diagnostics consumer: all three guide examples passed");
    }

    private static void results(Diagnostic warning) {
        var result = Result.success("accepted").withDiagnostic(warning).map(String::toUpperCase);
        var notification = new Notification().addAll(result.diagnostics());
        require("ACCEPTED".equals(result.value()), "mapped result");
        require(result.diagnostics().equals(List.of(warning)), "diagnostic travels with result");
        require(!FailurePolicy.failFast().evaluate(notification).shouldStop(), "warning permits continuation");
        require(Result.success(null).map(value -> {
            throw new AssertionError("null result must skip the mapper");
        }).value() == null, "null result");
    }

    private static void boundedCounts(DiagnosticFactory factory) {
        var stage = new BoundedDiagnosticCollector(1, ImportCode.SUPPRESSED);
        for (var severity : List.of(DiagnosticSeverity.WARN, DiagnosticSeverity.WARN,
                DiagnosticSeverity.ERROR, DiagnosticSeverity.FATAL)) {
            stage.add(factory.create(ImportCode.BAD_ROW).severity(severity).build());
        }
        var batch = stage.batch();
        require(batch.retained().stream().map(Diagnostic::severity).toList().equals(List.of(
                DiagnosticSeverity.WARN, DiagnosticSeverity.ERROR, DiagnosticSeverity.FATAL)), "stage samples");
        require(batch.summary().total() == 4 && batch.summary().suppressed() == 1, "stage counts");

        var delivered = new CollectingDiagnosticSink();
        var run = new BoundedNotification(1, factory, ImportCode.SUPPRESSED);
        run.addAll(batch, delivered::emit);
        require(run.batch().retained().stream().map(Diagnostic::severity).toList()
                .equals(List.of(DiagnosticSeverity.FATAL)), "run sample");
        require(run.summary().total() == 4 && run.summary().suppressed() == 3, "run counts");
        require(delivered.diagnostics().equals(batch.retained()), "three delivered occurrences");
        var marker = run.diagnostics().getLast();
        require(marker.code() == ImportCode.SUPPRESSED, "consumer-owned suppression code");
        require(marker.severity() == DiagnosticSeverity.ERROR, "highest omitted severity");
        require(marker.context().equals(Map.of("limit", 1, "suppressedCount", 3L,
                "suppressedBySeverity", Map.of("WARN", 2L, "ERROR", 1L))), "suppression details");
        var decision = FailurePolicy.collectAndContinue().evaluate(new Notification().addAll(run.diagnostics()));
        require(decision.shouldStop() && decision.diagnostic().orElseThrow().severity() == DiagnosticSeverity.FATAL,
                "fatal survives compaction and rejects at the service checkpoint");
    }

    private static void delivery(Diagnostic warning) {
        var failures = new ArrayList<RuntimeException>();
        var sink = new ResilientDiagnosticSink(
                value -> { throw new IllegalStateException("report unavailable"); },
                (value, failure) -> {
                    require(value == warning, "original occurrence reaches fallback");
                    failures.add(failure);
                });
        sink.emit(warning);
        require(failures.size() == 1 && "report unavailable".equals(failures.getFirst().getMessage()),
                "delivery failure reaches the service callback once");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
