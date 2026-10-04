package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.application.pipeline.stage.ExtractIndicatorsStage;
import com.iocextractor.application.pipeline.stage.PrepareRoutedArtifactsStage;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.result.Result;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.attribute.AttributionOutcome;
import com.iocextractor.domain.attribute.MarkerSourceAttributor;
import com.iocextractor.domain.extract.ExtractionDecision;
import com.iocextractor.domain.extract.ExtractionDecisionStatus;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.Span;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.refang.RefangOutcome;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.EnvelopeMeta;
import com.iocextractor.platform.etl.NoopPipelineObserver;
import com.iocextractor.platform.etl.Pipeline;
import com.iocextractor.platform.etl.PipelineRunResult;
import com.iocextractor.platform.etl.PipelineRunner;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Focused CAP-3 mechanism probe; no database, Spring context or remote I/O. */
public final class ProcessingStageCapacity {
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private static final DiagnosticFactory DIAGNOSTICS = new DiagnosticFactory(CLOCK);
    private static final int LIMIT = 128;

    private ProcessingStageCapacity() { }

    public static void main(String[] args) throws Exception {
        String profile = args[0];
        int count = Integer.parseInt(args[1]);
        if (count < 250 || count % 250 != 0) {
            throw new IllegalArgumentException("count must be a positive multiple of 250");
        }
        Supplier<Outcome> workload = profile.startsWith("attribution")
                ? attribution(profile, count) : diagnostics(profile, count);
        workload.get();
        System.gc();
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!threads.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Allocation accounting unavailable");
        }
        threads.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        var sampler = new ProcessingRouteComparison.PeakSampler();
        Outcome outcome;
        long elapsed;
        long allocated;
        try (sampler) {
            sampler.awaitFirstSample();
            long before = threads.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            outcome = workload.get();
            elapsed = System.nanoTime() - start;
            allocated = threads.getThreadAllocatedBytes(thread) - before;
        }
        String signature = signature(outcome, count);
        System.gc();
        long retainedHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        // Keep both input and output live through the post-GC retention sample.
        java.lang.ref.Reference.reachabilityFence(workload);
        java.lang.ref.Reference.reachabilityFence(outcome);
        System.out.printf(java.util.Locale.ROOT,
                "STAGE_CAPACITY {\"profile\":\"%s\",\"count\":%d,\"elapsed_ms\":%.3f,"
                        + "\"allocated_bytes\":%d,\"sampled_peak_heap_bytes\":%d,"
                        + "\"sampled_current_rss_kib\":%d,\"retained_heap_bytes\":%d,"
                        + "\"stage_retained_diagnostics\":%d,\"signature\":\"%s\"}%n",
                profile, count, elapsed / 1_000_000.0, allocated, sampler.peakHeapBytes(),
                sampler.peakCurrentRssKiB(), retainedHeap, outcome.stageDiagnostics(), signature);
    }

    private static Supplier<Outcome> attribution(String profile, int count) {
        int sections = Math.max(1, count / 250);
        var text = new StringBuilder();
        var input = new ArrayList<RawIndicator>();
        for (int section = 0; section < sections; section++) {
            int position = text.length();
            text.append("SECTION-").append(section).append('\n');
            var raw = new RawIndicator("example.test", IndicatorType.DOMAIN, position);
            for (int index = 0; index < 250 && input.size() < count; index++) {
                input.add(raw);
            }
        }
        if (profile.equals("attribution-unordered")) {
            Collections.shuffle(input, new Random(302_031L));
        }
        String source = text.toString();
        var attributor = new MarkerSourceAttributor(new Re2jPatternEngine(), List.of("SECTION-\\d+"));
        return () -> new Outcome(attributor.attribute(source, input), 0);
    }

    private static Supplier<Outcome> diagnostics(String profile, int count) throws Exception {
        if (profile.equals("extraction")) {
            var decision = new ExtractionDecision(IndicatorType.DOMAIN, "domain", new Span(0, 11, "example.com"),
                    ExtractionDecisionStatus.DROPPED_OVERLAP);
            var extracted = new ExtractionOutcome(List.of(), Collections.nCopies(count, decision));
            com.iocextractor.domain.extract.IndicatorExtractor extractor = text -> extracted;
            ExtractIndicatorsStage stage;
            try {
                stage = ExtractIndicatorsStage.class.getConstructor(
                        com.iocextractor.domain.extract.IndicatorExtractor.class, DiagnosticFactory.class,
                        com.iocextractor.application.port.out.observability.PipelineDecisionTracer.class, int.class)
                        .newInstance(extractor, DIAGNOSTICS, NoopPipelineDecisionTracer.INSTANCE, LIMIT);
            } catch (NoSuchMethodException baseline) {
                stage = new ExtractIndicatorsStage(extractor, DIAGNOSTICS, NoopPipelineDecisionTracer.INSTANCE);
            }
            return diagnosticWorkload(stage, Envelope.of(new RefangedText(new RefangOutcome("example.com", List.of())),
                    EnvelopeMeta.initial("probe", "probe", CLOCK)));
        }
        if (!profile.equals("preparation")) {
            throw new IllegalArgumentException("Unknown profile: " + profile);
        }
        var decision = new AttributionDecision(new RawIndicator("example.com", IndicatorType.DOMAIN, 0), Optional.empty());
        var input = new AttributedIndicators(new AttributionOutcome(List.of(), Collections.nCopies(count, decision)));
        DocumentProcessingPlan plan = occurrence -> Result.of(List.of(), List.of(
                DIAGNOSTICS.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                        .severity(occurrence.tieOrdinal() == count - 1 ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARN)
                        .with("item", occurrence.tieOrdinal()).with("stage", "mapping").with("reason", "probe").build()));
        com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver identity = (artifact, row) -> Optional.empty();
        PrepareRoutedArtifactsStage stage;
        try {
            stage = PrepareRoutedArtifactsStage.class.getConstructor(DocumentProcessingPlan.class, List.class, boolean.class, DiagnosticFactory.class, int.class)
                    .newInstance(plan, List.of(), false, DIAGNOSTICS, LIMIT);
        } catch (NoSuchMethodException current) {
            try {
                stage = PrepareRoutedArtifactsStage.class.getConstructor(DocumentProcessingPlan.class, List.class,
                        com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver.class, Map.class,
                        boolean.class, DiagnosticFactory.class, int.class)
                        .newInstance(plan, List.of(), identity, Map.of(), false, DIAGNOSTICS, LIMIT);
            } catch (NoSuchMethodException baseline) {
                stage = PrepareRoutedArtifactsStage.class.getConstructor(DocumentProcessingPlan.class, List.class,
                        com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver.class, Map.class,
                        boolean.class, DiagnosticFactory.class)
                        .newInstance(plan, List.of(), identity, Map.of(), false, DIAGNOSTICS);
            }
        }
        // This diagnostic workload produces no rows. It deliberately isolates diagnostic retention,
        // independently of the disk reducer measured by DocumentWorkspaceCapacity.
        var workspace = new com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspace() {
            public java.nio.file.Path source() { return java.nio.file.Path.of("unused"); }
            public void discard() { }
            public void beginPromotion() { }
            public boolean promotionStarted() { return false; }
            public boolean firstOriginal(String key) { return true; }
            public void append(com.iocextractor.application.artifact.RoutedArtifactCandidate candidate,
                    boolean eligible, boolean retained) { throw new AssertionError("Unexpected prepared row"); }
            public List<com.iocextractor.application.artifact.ArtifactWritePlan> seal(
                    List<com.iocextractor.application.artifact.ArtifactWritePlan> descriptors,
                    com.iocextractor.application.artifact.DocumentPreparationSummary summary) { return descriptors; }
            public void close() { }
        };
        return diagnosticWorkload(stage, Envelope.of(input, EnvelopeMeta.initial("probe", "probe", CLOCK))
                .withMetaAttribute("ioc.document.workspace", workspace));
    }

    private static <I, O> Supplier<Outcome> diagnosticWorkload(Stage<I, O> stage, Envelope<I> input) {
        var retained = new java.util.concurrent.atomic.AtomicInteger();
        var observed = new Stage<I, O>() {
            @Override public StageId name() { return stage.name(); }
            @Override public Envelope<O> process(Envelope<I> envelope) {
                var output = stage.process(envelope);
                retained.set(output.diagnostics().size());
                return output;
            }
        };
        var pipeline = Pipeline.<I>start().then(observed);
        var runner = new PipelineRunner(FailurePolicy.collectAndContinue(), new NoopPipelineObserver(),
                NoopDiagnosticSink.INSTANCE, DIAGNOSTICS, LIMIT);
        return () -> new Outcome(runner.runWithOutcome(input, pipeline), retained.get());
    }

    private static String signature(Outcome outcome, int count) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        if (outcome.value() instanceof AttributionOutcome attributed) {
            if (attributed.decisions().size() != count) {
                throw new IllegalStateException("Incomplete attribution");
            }
            for (var decision : attributed.decisions()) {
                var marker = decision.marker().orElseThrow();
                if (marker.position() != decision.rawIndicator().position()) {
                    throw new IllegalStateException("Wrong inclusive source");
                }
                hash.update((decision.rawIndicator().position() + ":" + marker.label() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        } else if (outcome.value() instanceof PipelineRunResult<?> result) {
            if (result.diagnosticSummary().total() != count || result.envelope().diagnostics().size() != LIMIT + 1) {
                throw new IllegalStateException("Incomplete diagnostic outcome");
            }
            hash.update((result.diagnosticSummary().total() + ":" + result.diagnosticSummary().suppressed()
                    + ":" + new TreeMap<>(result.diagnosticSummary().bySeverity())).getBytes(StandardCharsets.UTF_8));
            for (var diagnostic : result.envelope().diagnostics()) {
                hash.update((diagnostic.code().id() + ":" + diagnostic.severity() + ":"
                        + new TreeMap<>(diagnostic.context()) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } else {
            throw new IllegalStateException("Unknown outcome");
        }
        return java.util.HexFormat.of().formatHex(hash.digest());
    }

    private record Outcome(Object value, int stageDiagnostics) { }
}
