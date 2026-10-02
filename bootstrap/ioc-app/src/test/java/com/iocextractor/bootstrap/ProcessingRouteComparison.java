package com.iocextractor.bootstrap;

import com.iocextractor.IocExtractorApplication;
import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportUseCase;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportUseCase;
import com.iocextractor.application.port.out.dataframeimport.ImportCommitEvidenceStore;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.boot.SpringApplication;

/** Opt-in production-composition workload probe; invoked by the developer comparison script. */
public final class ProcessingRouteComparison {
    private ProcessingRouteComparison() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Expected: document|import root fixture [warmup fixtures]");
        }
        String kind = args[0];
        Path root = Path.of(args[1]).toAbsolutePath();
        Path fixture = Path.of(args[2]).toAbsolutePath();
        System.setProperty("selected.import.root", root.toString());
        System.setProperty("golden.output-dir", root.toString());
        System.setProperty("spring.main.web-application-type", "none");
        System.setProperty("spring.main.banner-mode", "off");
        System.setProperty("server.address", "127.0.0.1");
        System.setProperty("server.port", "0");
        System.setProperty("ioc.ingestion.detect.use-watch-service", "false");
        long startup = System.nanoTime();
        try (var context = SpringApplication.run(IocExtractorApplication.class)) {
            startup = System.nanoTime() - startup;
            if ("import".equals(kind)) {
                context.getBean(ManagedDataframeImportRuntime.class).close();
            }
            for (int index = 3; index < args.length; index++) {
                Path warmup = Path.of(args[index]).toAbsolutePath();
                if ("document".equals(kind)) {
                    System.out.println("ROUTE_OUTCOME " + document(
                            context.getBean(ExtractIocsUseCase.class), warmup).summary());
                } else {
                    System.out.println("ROUTE_OUTCOME " + importCsv(context, warmup, "warmup-" + index).summary());
                }
            }
            ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!threads.isThreadAllocatedMemorySupported()) {
                throw new IllegalStateException("Thread allocation accounting is unavailable");
            }
            threads.setThreadAllocatedMemoryEnabled(true);
            long currentThread = Thread.currentThread().threadId();
            long allocatedBefore = threads.getThreadAllocatedBytes(currentThread);
            long collectionsBefore = gcCount();
            long gcTimeBefore = gcTime();
            PeakSampler sampler = new PeakSampler();
            WorkloadOutcome outcome;
            long collections;
            long collectionTime;
            long elapsed;
            long allocated;
            boolean diagnostic = Boolean.getBoolean("comparison.diagnostics");
            String counters = null;
            try (sampler) {
                if (diagnostic) {
                    ComparisonDiagnostics.begin();
                }
                long start = System.nanoTime();
                outcome = switch (kind) {
                    case "document" -> document(context.getBean(ExtractIocsUseCase.class), fixture);
                    case "import" -> importCsv(context, fixture, "comparison-import");
                    default -> throw new IllegalArgumentException("Unknown workload: " + kind);
                };
                elapsed = System.nanoTime() - start;
                allocated = threads.getThreadAllocatedBytes(currentThread) - allocatedBefore;
                collections = gcCount() - collectionsBefore;
                collectionTime = gcTime() - gcTimeBefore;
                if (diagnostic) {
                    counters = ComparisonDiagnostics.end();
                }
                if (outcome.observations() <= 0 || allocated < 0) {
                    throw new IllegalStateException("Incomplete workload measurement");
                }
            }
            int observations = outcome.observations();
            if (counters != null) {
                System.out.println("ROUTE_DIAGNOSTICS " + counters);
            }
            System.out.println("ROUTE_OUTCOME " + outcome.summary());
            System.out.printf("ROUTE_COMPARISON kind=%s observations=%d elapsed_ms=%.3f "
                            + "throughput_per_s=%.2f allocated_main_bytes=%d "
                            + "sampled_peak_heap_bytes=%d sampled_peak_rss_kib=%d%n",
                    kind, observations, elapsed / 1_000_000.0,
                    observations * 1_000_000_000.0 / elapsed, allocated,
                    sampler.peakHeap.get(), sampler.peakRss.get());
            System.out.printf("ROUTE_ENV startup_ms=%.3f gc_count=%d gc_time_ms=%d "
                            + "sampled_peak_current_rss_kib=%d%n", startup / 1_000_000.0,
                    collections, collectionTime, sampler.peakCurrentRss.get());
        }
    }

    private record WorkloadOutcome(int observations, String summary) { }

    private static WorkloadOutcome document(ExtractIocsUseCase useCase, Path fixture) {
        var result = useCase.extract(new ExtractionCommand(fixture.getFileName().toString(), fixture, false));
        if (result.extracted() <= 0 || result.completionStatus().name().contains("ERROR")) {
            throw new IllegalStateException("Document extraction failed: " + result.completionStatus());
        }
        var codes = new java.util.TreeMap<String, Long>();
        result.diagnostics().forEach(diagnostic -> codes.merge(
                diagnostic.code().id() + ":" + diagnostic.severity(), 1L, Long::sum));
        var severities = new java.util.TreeMap<String, Long>();
        result.diagnosticSummary().bySeverity().forEach((severity, count) ->
                severities.put(severity.name(), count));
        return new WorkloadOutcome(result.extracted(), String.format(
                "extracted=%d retained=%d status=%s diagnostics=%d suppressed=%d severities=%s retained_codes=%s",
                result.extracted(), result.retained(), result.completionStatus(),
                result.diagnosticSummary().total(), result.diagnosticSummary().suppressed(),
                severities, codes));
    }

    private static WorkloadOutcome importCsv(org.springframework.context.ApplicationContext context, Path fixture,
                                 String deliveryId)
            throws Exception {
        Path inbox = Files.createDirectories(Path.of(System.getProperty("selected.import.root"), "inbox"));
        Files.copy(fixture, inbox.resolve("hosts.csv"));
        var source = new ImportSourceId("local-hosts");
        var delivery = new ImportDeliveryId(deliveryId);
        var candidate = context.getBean("managedImportSourceLifecycle", ManagedImportSourceLifecycle.class)
                .detect(source, context.getBean(Clock.class).instant()).getFirst();
        context.getBean(AdmitDataframeImportUseCase.class).admit(new AdmitDataframeImportCommand(
                new ImportClaimReservation(delivery, source, candidate.candidateToken(),
                        context.getBean(Clock.class).instant())));
        var processor = context.getBean("processNextDataframeImportUseCase",
                ProcessNextDataframeImportUseCase.class);
        var ledger = context.getBean(ImportDeliveryLedger.class);
        for (int step = 0; step < 5; step++) {
            if (ledger.find(delivery).orElseThrow().state() == ImportDeliveryState.TERMINAL) {
                var receipt = context.getBean(ImportCommitEvidenceStore.class).find(delivery).orElseThrow();
                if (receipt.rejectedRows() != 0 || receipt.acceptedRows() <= 0) {
                    throw new IllegalStateException("Import receipt rejected or omitted rows");
                }
                try (var rows = Files.lines(fixture)) {
                    return new WorkloadOutcome(Math.toIntExact(rows.count() - 1), String.format(
                            "accepted=%d rejected=%d", receipt.acceptedRows(), receipt.rejectedRows()));
                }
            }
            if (!processor.processNext().workPerformed()) {
                throw new IllegalStateException("Import made no progress");
            }
        }
        throw new IllegalStateException("Import did not reach terminal state");
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> {
            if (bean.getCollectionCount() < 0) {
                throw new IllegalStateException("GC collection accounting is unavailable");
            }
            return bean.getCollectionCount();
        }).sum();
    }

    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> {
            if (bean.getCollectionTime() < 0) {
                throw new IllegalStateException("GC time accounting is unavailable");
            }
            return bean.getCollectionTime();
        }).sum();
    }

    static final class PeakSampler implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicLong peakHeap = new AtomicLong();
        private final AtomicLong peakRss = new AtomicLong();
        private final AtomicLong peakCurrentRss = new AtomicLong();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread worker;

        PeakSampler() {
            this(PeakSampler::readRssHighWater);
        }

        PeakSampler(LongSupplier rssHighWater) {
            worker = Thread.ofPlatform().daemon().name("route-comparison-sampler").start(() -> {
                try {
                    while (running.get()) {
                        peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean()
                                .getHeapMemoryUsage().getUsed(), Math::max);
                        peakRss.accumulateAndGet(rssHighWater.getAsLong(), Math::max);
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("Memory sampler interrupted");
                        }
                        peakCurrentRss.accumulateAndGet(readStatusMetric("VmRSS:"), Math::max);
                        TimeUnit.MILLISECONDS.sleep(10);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.set(interrupted);
                } catch (Throwable samplingFailure) {
                    failure.set(samplingFailure);
                }
            });
        }

        private static long readRssHighWater() {
            return readStatusMetric("VmHWM:");
        }

        private static long readStatusMetric(String name) {
            try (var status = Files.lines(Path.of("/proc/self/status"))) {
                return status.filter(line -> line.startsWith(name))
                        .mapToLong(line -> Long.parseLong(line.replaceAll("[^0-9]", "")))
                        .findFirst().orElseThrow(() -> new IllegalStateException(name + " is missing"));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override public void close() throws InterruptedException {
            running.set(false);
            worker.join(TimeUnit.SECONDS.toMillis(2));
            if (worker.isAlive()) {
                throw new IllegalStateException("Memory sampler did not stop");
            }
            if (failure.get() != null) {
                throw new IllegalStateException("Memory sampler failed", failure.get());
            }
            if (peakHeap.get() <= 0 || peakRss.get() <= 0) {
                throw new IllegalStateException("Memory sampler produced no complete sample");
            }
        }
    }
}
