package com.iocextractor.bootstrap;

import com.iocextractor.IocExtractorApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.application.pipeline.stage.AttributeSourceStreamStage;
import com.iocextractor.application.pipeline.stage.ExtractSourceStreamStage;
import com.iocextractor.application.pipeline.stage.ReadSourceStreamStage;
import com.iocextractor.application.pipeline.stage.RefangSourceStreamStage;
import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspaceFactory;
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

/** Actual admitted source path with a bounded occurrence oracle; excludes Router/canonical writes. */
public final class DocumentUpstreamCapacity {
    private DocumentUpstreamCapacity() { }

    public static void main(String[] arguments) throws Exception {
        int count = Integer.parseInt(arguments[0]);
        Path root = Path.of(arguments[1]).toAbsolutePath();
        Files.createDirectories(root);
        String format = arguments.length > 2 ? arguments[2] : "html";
        Path source = root.resolve("source." + format);
        writeSource(source, count, format);
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
            metrics.put("format", format);
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
                    .then(new ReadSourceStreamStage(context.getBean(SourceReader.class), diagnostics))
                    .then(new RefangSourceStreamStage(context.getBean(Refanger.class), tracer))
                    .then(new ExtractSourceStreamStage(context.getBean(IndicatorExtractor.class), diagnostics, tracer, 128))
                    .then(new AttributeSourceStreamStage(context.getBean(SourceAttributor.class), clock, tracer));
            var sampler = new ProcessingRouteComparison.PeakSampler();
            var command = new ExtractionCommand("upstream", source, true);
            try (sampler; var workspace = context.getBean(DocumentPreparationWorkspaceFactory.class).open(command, Map.of())) {
                sampler.awaitFirstSample();
                long started = System.nanoTime();
                var result = new PipelineRunner(FailurePolicy.failFast(), observer).runWithOutcome(
                        Envelope.of(new ExtractionCommand("upstream", workspace.source(), true),
                                EnvelopeMeta.initial("upstream", source.toString(), clock)
                                        .withAttribute(PipelineMetaAttributes.DOCUMENT_PREPARATION_WORKSPACE, workspace)), pipeline);
                metrics.put("upstream_nanos", System.nanoTime() - started);
                int found = 0;
                int previous = -1;
                try (var rows = result.envelope().payload().decisions().open()) {
                    while (rows.next()) {
                        var decision = rows.value();
                        var raw = decision.rawIndicator();
                        if (!raw.value().equals("host-" + found + ".example.test")
                                || raw.type() != com.iocextractor.domain.model.IndicatorType.DOMAIN
                                || !decision.marker().orElseThrow().label().equals("БИБ-0001")
                                || raw.position() <= previous) {
                            throw new IllegalStateException("Independent ordered value/type/source oracle failed at " + found);
                        }
                        previous = raw.position(); found++;
                    }
                }
                if (found != count) { throw new IllegalStateException("Upstream extraction count differs"); }
                metrics.put("ordered_occurrence_oracle", "PASS");
                metrics.put("attributed", sample());
                System.gc();
                metrics.put("diagnostic_post_gc", sample());
                workspace.discard();
                Reference.reachabilityFence(result);
            }
            metrics.put("peak_heap_bytes", sampler.peakHeapBytes());
            metrics.put("peak_rss_kib", sampler.peakCurrentRssKiB());
            System.out.println("CAPACITY_JSON=" + new ObjectMapper().writeValueAsString(metrics));
        }
    }

    private static Map<String, Long> sample() {
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

    private static void writeSource(Path source, int count, String format) throws java.io.IOException {
        if (format.equals("html")) {
            try (var output = Files.newBufferedWriter(source)) {
                output.write("<!doctype html><html><head><meta charset=\"utf-8\"></head><body><h2>БИБ-0001</h2>");
                for (int index = 0; index < count; index++) { output.write("<p>host-" + index + "[.]example[.]test</p>\n"); }
                output.write("</body></html>");
            }
        } else if (format.equals("docx")) {
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(source))) {
                zipEntry(zip, "[Content_Types].xml", """
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                        </Types>
                        """);
                zipEntry(zip, "_rels/.rels", """
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                        </Relationships>
                        """);
                zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
                var output = new java.io.OutputStreamWriter(zip, java.nio.charset.StandardCharsets.UTF_8);
                output.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>БИБ-0001</w:t></w:r></w:p>");
                for (int index = 0; index < count; index++) {
                    output.write("<w:p><w:r><w:t>host-" + index + "[.]example[.]test</w:t></w:r></w:p>");
                }
                output.write("</w:body></w:document>"); output.flush(); zip.closeEntry();
            }
        } else { throw new IllegalArgumentException("Unknown source format"); }
    }
    private static void zipEntry(java.util.zip.ZipOutputStream zip, String name, String text) throws java.io.IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
