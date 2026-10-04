package com.iocextractor.bootstrap;

import com.iocextractor.IocExtractorApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.application.pipeline.stage.AttributeSourceStage;
import com.iocextractor.application.pipeline.stage.ExtractIndicatorsStage;
import com.iocextractor.application.pipeline.stage.ReadSourceStage;
import com.iocextractor.application.pipeline.stage.RefangStage;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.EnvelopeMeta;
import com.iocextractor.platform.etl.Pipeline;
import com.iocextractor.platform.etl.PipelineObserver;
import com.iocextractor.platform.etl.PipelineRunner;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;

/** Separates actual Spring/Tika/extraction input graphs from the G4 preparation budget. */
public final class DocumentUpstreamCapacity {
    private DocumentUpstreamCapacity() { }

    public static void main(String[] arguments) throws Exception {
        int count = Integer.parseInt(arguments[0]);
        Path root = Path.of(arguments[1]).toAbsolutePath();
        Files.createDirectories(root);
        Path source = root.resolve("source.html");
        try (var output = Files.newBufferedWriter(source)) {
            output.write("<!doctype html><html><head><meta charset=\"utf-8\"></head><body><h2>БИБ-0001</h2>");
            for (int index = 0; index < count; index++) {
                output.write("<p>host-" + index + "[.]example[.]test</p>\n");
            }
            output.write("</body></html>");
        }
        Path config = root.resolve("application.yml");
        try (var input = DocumentUpstreamCapacity.class.getResourceAsStream("/application.yml")) {
            String yaml = new String(java.util.Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(config, yaml.replace("./dataframe/", root + "/"));
        }
        var settings = new java.util.ArrayList<String>(java.util.List.of(
                "--spring.config.location=" + config, "--spring.config.import=",
                "--ioc.runtime.mode=oneshot", "--ioc.lifecycle.validity.mode=disabled",
                "--ioc.dataframe-import.enabled=false", "--ioc.sync.enabled=false",
                "--ioc.storage.service.url=jdbc:sqlite:" + root.resolve("service.db"),
                "--ioc.storage.dataframe.url=jdbc:sqlite:" + root.resolve("canonical.db"),
                "--ioc.processing.workspace.directory=" + root.resolve("workspace"),
                "--ioc.export.root=" + root.resolve("export"),
                "--logging.file.path=" + root.resolve("logs")));
        try (var context = SpringApplication.run(IocExtractorApplication.class, settings.toArray(String[]::new))) {
            var metrics = new LinkedHashMap<String, Object>();
            metrics.put("input_occurrences", count);
            metrics.put("input_bytes", Files.size(source));
            metrics.put("baseline", sample());
            var observer = new PipelineObserver() {
                public AutoCloseable openStage(EnvelopeMeta meta) { return () -> { }; }
                public void stageStarted(EnvelopeMeta meta) { metrics.put("before_" + meta.stage().value(), sample()); }
                public void stageCompleted(EnvelopeMeta meta, long nanos) { metrics.put(meta.stage().value() + "_nanos", nanos); }
                public void stageFailed(EnvelopeMeta meta, long nanos, RuntimeException failure) { throw failure; }
            };
            var clock = Clock.systemUTC();
            var diagnostics = new DiagnosticFactory(clock);
            var tracer = NoopPipelineDecisionTracer.INSTANCE;
            var pipeline = Pipeline.<ExtractionCommand>start()
                    .then(new ReadSourceStage(context.getBean(SourceReader.class), diagnostics))
                    .then(new RefangStage(context.getBean(Refanger.class), tracer))
                    .then(new ExtractIndicatorsStage(context.getBean(IndicatorExtractor.class), diagnostics, tracer, 128))
                    .then(new AttributeSourceStage(context.getBean(SourceAttributor.class), clock, tracer));
            var sampler = new ProcessingRouteComparison.PeakSampler();
            try (sampler) {
                sampler.awaitFirstSample();
                var result = new PipelineRunner(FailurePolicy.failFast(), observer).runWithOutcome(
                        Envelope.of(new ExtractionCommand("upstream", source, true),
                                EnvelopeMeta.initial("upstream", source.toString(), clock)), pipeline);
                if (result.envelope().payload().indicators().size() != count) {
                    throw new IllegalStateException("Upstream extraction count differs");
                }
                metrics.put("attributed", sample());
                Reference.reachabilityFence(result);
            }
            metrics.put("peak_heap_bytes", sampler.peakHeapBytes());
            metrics.put("peak_rss_kib", sampler.peakCurrentRssKiB());
            System.out.println("CAPACITY_JSON=" + new ObjectMapper().writeValueAsString(metrics));
        }
    }

    private static Map<String, Long> sample() {
        System.gc();
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Map.of("live_heap_bytes", heap, "rss_kib", Long.parseLong(line.split("\\s+")[1]));
                }
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot sample upstream RSS", failure);
        }
        throw new IllegalStateException("Upstream RSS is unavailable");
    }
}
