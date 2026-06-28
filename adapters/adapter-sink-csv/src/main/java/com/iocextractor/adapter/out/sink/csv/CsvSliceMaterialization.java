package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.export.SnapshotArtifactMetadata;
import com.iocextractor.application.export.SnapshotMetadata;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.application.port.out.export.SnapshotRowConsumer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One synchronous callback session that turns snapshot rows into durable staging bytes. */
final class CsvSliceMaterialization implements SnapshotRowConsumer, AutoCloseable {

    private final ExportRun run;
    private final ExportPlan plan;
    private final Path staging;
    private final SliceManifestCodec codec;
    private final SliceFileOperations fileOperations;
    private final Thread owner = Thread.currentThread();
    private final List<SliceArtifactManifest> artifacts = new ArrayList<>();

    private SnapshotMetadata metadata;
    private SnapshotArtifactMetadata currentMetadata;
    private CSVPrinter printer;
    private MessageDigest currentDigest;
    private long currentRows;
    private int artifactIndex;
    private boolean ended;

    CsvSliceMaterialization(ExportRun run,
                            ExportPlan plan,
                            Path staging,
                            SliceManifestCodec codec,
                            SliceFileOperations fileOperations) {
        this.run = Objects.requireNonNull(run, "run");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.fileOperations = Objects.requireNonNull(fileOperations, "fileOperations");
    }

    @Override
    public void begin(SnapshotMetadata value) {
        requireOwner();
        if (metadata != null || value == null) {
            throw state("snapshot begin callback is duplicated or null");
        }
        if (!value.profile().equals(run.profile()) || !value.profile().equals(plan.profile().name())) {
            throw state("snapshot profile differs from export plan/run");
        }
        if (!value.planHash().equals(run.planHash()) || !value.planHash().equals(plan.planHash())) {
            throw state("snapshot plan hash differs from export plan/run");
        }
        if (value.artifacts().size() != plan.artifacts().size()) {
            throw state("snapshot artifact count differs from export plan");
        }
        metadata = value;
    }

    @Override
    public void beginArtifact(SnapshotArtifactMetadata artifact) {
        requireOwner();
        if (metadata == null || currentMetadata != null || ended || artifactIndex >= plan.artifacts().size()) {
            throw state("artifact begin callback is out of sequence");
        }
        ExportArtifactSpec expected = plan.artifacts().get(artifactIndex);
        SnapshotArtifactMetadata snapshotExpected = metadata.artifacts().get(artifactIndex);
        if (!artifact.equals(snapshotExpected)
                || !artifact.artifactName().equals(expected.artifactName())
                || !artifact.fileName().equals(expected.fileName())
                || !artifact.columns().equals(expected.columns())
                || artifact.identityEpoch() != expected.identityEpoch()
                || !artifact.identityHash().equals(expected.identityHash())
                || !artifact.schemaHash().equals(expected.schemaHash())) {
            throw state("snapshot artifact metadata differs from ordered export plan");
        }
        OutputStream stream = null;
        CSVPrinter openedPrinter = null;
        try {
            CSVFormat format = csvFormat(plan.format());
            var encoder = Charset.forName(plan.format().charset()).newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            Path output = staging.resolve(expected.fileName());
            MessageDigest digest = SliceHashes.sha256Digest();
            stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            var digestStream = new DigestOutputStream(stream, digest);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(digestStream, encoder));
            openedPrinter = new CSVPrinter(writer, format);
            stream = null; // ownership moved to the printer/writer chain
            openedPrinter.printRecord(expected.columns());
            printer = openedPrinter;
            openedPrinter = null;
            currentDigest = digest;
            currentMetadata = artifact;
            currentRows = 0;
        } catch (IOException | RuntimeException e) {
            closeOnFailure(openedPrinter != null ? openedPrinter : stream, e);
            closeQuietly();
            throw write("cannot open artifact " + expected.fileName(), e);
        }
    }

    @Override
    public void row(ArtifactRow row) {
        requireOwner();
        if (currentMetadata == null || printer == null || row == null) {
            throw state("row callback is out of sequence or null");
        }
        try {
            List<String> values = currentMetadata.columns().stream().map(row::value).toList();
            printer.printRecord(values);
            currentRows++;
        } catch (IOException e) {
            throw write("cannot write artifact " + currentMetadata.fileName(), e);
        }
    }

    @Override
    public void endArtifact() {
        requireOwner();
        if (currentMetadata == null || printer == null) {
            throw state("artifact end callback is out of sequence");
        }
        SnapshotArtifactMetadata finished = currentMetadata;
        Path file = staging.resolve(finished.fileName());
        try {
            printer.close();
            printer = null;
            fileOperations.forceFile(file);
            artifacts.add(new SliceArtifactManifest(
                    finished.artifactName(), finished.fileName(), currentRows, finished.coverage(),
                    finished.identityEpoch(), finished.identityHash(), finished.schemaHash(),
                    SliceHashes.hex(currentDigest)));
            currentMetadata = null;
            currentDigest = null;
            currentRows = 0;
            artifactIndex++;
        } catch (IOException | RuntimeException e) {
            closeQuietly();
            throw write("cannot finish artifact " + finished.fileName(), e);
        }
    }

    @Override
    public void end() {
        requireOwner();
        if (metadata == null || currentMetadata != null || ended || artifactIndex != plan.artifacts().size()) {
            throw state("snapshot end callback is out of sequence");
        }
        try {
            SliceManifest manifest = new SliceManifest(
                    plan.manifestVersion(), run.runId(), run.runId(), run.profile(), metadata.capturedAt(),
                    plan.profile().mode(), run.planHash(), plan.format(), artifacts);
            byte[] manifestBytes = codec.encode(manifest);
            Path manifestPath = staging.resolve(SliceTreeVerifier.MANIFEST_FILE);
            Files.write(manifestPath, manifestBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            fileOperations.forceFile(manifestPath);

            String manifestHash = SliceHashes.sha256(manifestBytes);
            Path success = staging.resolve(SliceTreeVerifier.SUCCESS_FILE);
            Files.writeString(success, manifestHash + "\n", java.nio.charset.StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            fileOperations.forceFile(success);
            fileOperations.forceDirectory(staging);
            fileOperations.forceDirectory(staging.getParent());
            ended = true;
        } catch (IOException | RuntimeException e) {
            throw write("cannot finish slice", e);
        }
    }

    boolean ended() {
        return ended;
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private CSVFormat csvFormat(ExportFormat format) {
        if (!format.type().equalsIgnoreCase("csv")) {
            throw state("unsupported export format: " + format.type());
        }
        if (format.delimiter().length() != 1 || format.quote().length() != 1) {
            throw state("CSV delimiter and quote must each be one character");
        }
        return CSVFormat.Builder.create()
                .setDelimiter(format.delimiter().charAt(0))
                .setQuote(format.quote().charAt(0))
                .setQuoteMode(QuoteMode.ALL_NON_NULL)
                .setNullString(format.nullLiteral())
                .setRecordSeparator("\r\n")
                .build();
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw state("snapshot callbacks must remain synchronous on the stage thread");
        }
    }

    private IllegalStateException state(String message) {
        return new IllegalStateException(message);
    }

    private SliceWriteException write(String message, Throwable cause) {
        return new SliceWriteException(message, cause);
    }

    private void closeQuietly() {
        if (printer != null) {
            try {
                printer.close();
            } catch (IOException ignored) {
                // Original callback failure remains authoritative.
            } finally {
                printer = null;
            }
        }
    }

    private void closeOnFailure(AutoCloseable resource, Throwable original) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }
}
