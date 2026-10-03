package com.iocextractor.bootstrap;

import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.session.IndicatorProcessingSession;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in retention calibration, separate from production pipeline timing evidence. */
public final class IndicatorSessionBudgetProbe {
    private IndicatorSessionBudgetProbe() { }

    public static void main(String[] args) throws Exception {
        for (String profile : List.of("duplicate", "unique", "long", "concurrent")) {
            measure(profile);
        }
    }

    private static void measure(String profile) throws Exception {
        int callers = profile.equals("concurrent") ? 4 : 1;
        var calls = new AtomicInteger();
        var classifier = new IndicatorClassifier(indicator -> {
            calls.incrementAndGet();
            return new ClassificationDecision(new IndicatorFeatures(indicator.value(), indicator.value(),
                    false, false, false, HostKind.REGISTRABLE), 0, List.of("fixture"),
                    new MaskMatch("u:hAS", "h:dAS"));
        });
        var sessions = new ArrayList<IndicatorProcessingSession>();
        try {
            for (int index = 0; index < callers; index++) {
                sessions.add(new IndicatorProcessingSession(classifier));
            }
            long start = System.nanoTime();
            try (var workers = Executors.newFixedThreadPool(callers)) {
                var futures = new ArrayList<Future<?>>();
                for (var session : sessions) {
                    futures.add(workers.submit(() -> populate(session, profile)));
                }
                for (var future : futures) {
                    future.get(60, TimeUnit.SECONDS);
                }
                workers.shutdown();
                if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                    throw new IllegalStateException("Session calibration workers did not stop");
                }
            }
            long elapsed = System.nanoTime() - start;
            long bytes = 0;
            int entries = 0;
            for (var session : sessions) {
                Retention retained = retention(session);
                bytes += retained.bytes();
                entries += retained.entries();
            }
            // This diagnostic snapshot deliberately excludes transient input allocation.
            System.gc();
            Thread.sleep(100);
            long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            String rss = Files.readAllLines(Path.of("/proc/self/status")).stream()
                    .filter(line -> line.startsWith("VmRSS:")).findFirst().orElseThrow();
            System.out.printf("profile=%s sessions=%d policy_calls=%d retained_entries=%d "
                            + "charged_bytes=%d live_heap=%d %s elapsed_ms=%.1f%n",
                    profile, callers, calls.get(), entries, bytes, heap, rss, elapsed / 1e6);
        } finally {
            sessions.forEach(IndicatorProcessingSession::close);
        }
    }

    private static void populate(IndicatorProcessingSession session, String profile) {
        for (int index = 0; index < 8000; index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Session calibration interrupted");
            }
            int key = profile.equals("duplicate") ? index % 20 : index;
            String suffix = "x".repeat(profile.equals("long") ? 40_000 : 2000);
            var indicator = new Indicator("host" + key + ".example/" + suffix, IndicatorType.URL,
                    new SourceContext("source", null));
            session.classify(indicator);
            session.deriveHost(indicator);
        }
    }

    /** Diagnostic-only observer; failure to inspect a revised layout must abort calibration. */
    private static Retention retention(IndicatorProcessingSession session) throws ReflectiveOperationException {
        var bytes = IndicatorProcessingSession.class.getDeclaredField("retainedBytes");
        var classifications = IndicatorProcessingSession.class.getDeclaredField("classifications");
        var hosts = IndicatorProcessingSession.class.getDeclaredField("hosts");
        bytes.setAccessible(true);
        classifications.setAccessible(true);
        hosts.setAccessible(true);
        return new Retention(bytes.getLong(session), ((Map<?, ?>) classifications.get(session)).size()
                + ((Map<?, ?>) hosts.get(session)).size());
    }

    private record Retention(long bytes, int entries) { }
}
