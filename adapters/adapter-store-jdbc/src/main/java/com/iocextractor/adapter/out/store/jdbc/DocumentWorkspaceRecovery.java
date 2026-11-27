package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.DocumentPreparationSeal;
import com.iocextractor.application.artifact.DocumentPreparationSummary;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import com.iocextractor.platform.diagnostics.result.DiagnosticSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.List;
import java.util.stream.Collectors;

/** Fixed-size, versioned checkpoint pin retained independently of workspace leases. */
final class DocumentWorkspaceRecovery {
    private static final String FILE_NAME = "recovery";
    private static final String FORMAT = "ioc-document-recovery-v1";
    private static final int MAXIMUM_BYTES = 512;

    private DocumentWorkspaceRecovery() { }

    static boolean pinned(Path directory) {
        return Files.exists(directory.resolve(FILE_NAME), LinkOption.NOFOLLOW_LINKS);
    }

    static DocumentPreparationSeal pin(Path directory, String runId, String workspaceSeal,
                                       List<String> artifacts, DocumentPreparationSummary summary) throws IOException {
        var manifest = new StringBuilder(header(runId, workspaceSeal, artifacts))
                .append(summary.extracted()).append('\n')
                .append(summary.retained()).append('\n')
                .append(summary.diagnostics().total()).append('\n')
                .append(summary.diagnostics().suppressed()).append('\n');
        for (DiagnosticSeverity severity : DiagnosticSeverity.values()) {
            manifest.append(summary.diagnostics().count(severity)).append('\n');
        }
        String content = manifest.toString();
        if (pinned(directory)) {
            if (!read(directory).equals(content)) {
                throw new IocExtractorException("Pinned document recovery manifest differs");
            }
        } else {
            Path temporary = directory.resolve(FILE_NAME + ".tmp");
            DocumentWorkspaceFiles.write(temporary, content);
            Files.move(temporary, directory.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE);
            DocumentWorkspaceFiles.syncDirectory(directory);
        }
        return new DocumentPreparationSeal(ArtifactIdentityDefinition.sha256(content), artifacts);
    }

    static DocumentPreparationSummary readSummary(Path directory, String runId, String workspaceSeal,
                                                  DocumentPreparationSeal seal) throws IOException {
        String content = verify(directory, seal);
        String header = header(runId, workspaceSeal, seal.artifacts());
        if (!content.startsWith(header)) {
            throw new IocExtractorException("Document recovery manifest belongs to another run or workspace");
        }
        var severityValues = DiagnosticSeverity.values();
        String[] counters = content.substring(header.length()).split("\\n");
        if (counters.length != 4 + severityValues.length) {
            throw new IocExtractorException("Invalid document recovery counts");
        }
        var bySeverity = new EnumMap<DiagnosticSeverity, Long>(DiagnosticSeverity.class);
        for (int index = 0; index < severityValues.length; index++) {
            long count = Long.parseLong(counters[index + 4]);
            if (count != 0) { bySeverity.put(severityValues[index], count); }
        }
        var diagnostics = new DiagnosticSummary(Long.parseLong(counters[2]), Long.parseLong(counters[3]), bySeverity);
        return new DocumentPreparationSummary(Integer.parseInt(counters[0]), Integer.parseInt(counters[1]), diagnostics);
    }

    static String verify(Path directory, DocumentPreparationSeal seal) throws IOException {
        String content = read(directory);
        if (!ArtifactIdentityDefinition.sha256(content).equals(seal.checksum())) {
            throw new IocExtractorException("Document recovery manifest checksum mismatch");
        }
        String[] fields = content.split("\\n", 5);
        if (fields.length != 5 || !FORMAT.equals(fields[0]) || !artifactDigest(seal.artifacts()).equals(fields[3])) {
            throw new IocExtractorException("Document recovery artifact catalog mismatch");
        }
        return content;
    }

    static DocumentPreparationSeal reference(Path directory, List<String> artifacts) throws IOException {
        return new DocumentPreparationSeal(ArtifactIdentityDefinition.sha256(read(directory)), artifacts);
    }

    static void verifyUnlinkedRun(Path directory, String runId) throws IOException {
        if (Files.exists(directory.resolve("promoting"), LinkOption.NOFOLLOW_LINKS)
                || !read(directory).startsWith(runHeader(runId))) {
            throw new IocExtractorException("Unlinked document workspace belongs to another or promoting run");
        }
    }

    private static String header(String runId, String workspaceSeal, List<String> artifacts) {
        return runHeader(runId) + workspaceSeal + "\n" + artifactDigest(artifacts) + "\n";
    }

    private static String runHeader(String runId) {
        return FORMAT + "\n" + ArtifactIdentityDefinition.sha256(runId) + "\n";
    }

    private static String artifactDigest(List<String> artifacts) {
        String catalog = artifacts.stream().map(name -> name.length() + ":" + name).collect(Collectors.joining());
        return ArtifactIdentityDefinition.sha256(catalog);
    }

    private static String read(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAXIMUM_BYTES) {
            throw new IocExtractorException("Document recovery manifest is missing or invalid");
        }
        return Files.readString(file);
    }
}
