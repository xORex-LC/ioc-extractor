package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.ArchivedSourceUnit;
import com.iocextractor.application.ingest.ClaimedSource;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Filesystem implementation of source ownership:
 * {@code inbox -> processing -> done|failed}.
 */
public final class FileSystemSourceLifecycle implements SourceLifecycle {

    private final Path processingDir;
    private final Path doneDir;
    private final Path failedDir;
    private final StrictAtomicFileOwnership ownership;

    public FileSystemSourceLifecycle(Path processingDir, Path doneDir, Path failedDir) {
        this(processingDir, doneDir, failedDir, new StrictAtomicFileOwnership());
    }

    FileSystemSourceLifecycle(Path processingDir,
                              Path doneDir,
                              Path failedDir,
                              StrictAtomicFileOwnership ownership) {
        this.processingDir = requireDirectory(processingDir, "processingDir");
        this.doneDir = requireDirectory(doneDir, "doneDir");
        this.failedDir = requireDirectory(failedDir, "failedDir");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    @Override
    public SourceUnit claim(Path source,
                            ObservationId observationId,
                            SourceKey key,
                            Instant detectedAt) {
        Path target = processingDir.resolve(fileName(observationId, key, source));
        move(source, target);
        return new SourceUnit(observationId, key, source, target, detectedAt);
    }

    @Override
    public ClaimedSource claimBeforeHash(Path source,
                                         ObservationId observationId,
                                         Instant detectedAt) {
        Path target = prehashClaimPath(source, observationId);
        move(source, target);
        return new ClaimedSource(observationId, source, target, detectedAt);
    }

    @Override
    public Path prehashClaimPath(Path source, ObservationId observationId) {
        return processingDir.resolve(prehashFileName(observationId, source));
    }

    @Override
    public ClaimedSource sealClaim(ClaimedSource claimed) {
        Objects.requireNonNull(claimed, "claimed");
        Path source = claimed.processingPath();
        Path fileName = Objects.requireNonNull(source.getFileName(), "claim path requires a file name");
        Path parent = Objects.requireNonNull(source.getParent(), "claim path requires a parent");
        Path sealed = parent.resolve(fileName + ".sealed");
        Path temporary = parent.resolve(fileName + ".sealing");
        try {
            if (Files.isRegularFile(sealed)) {
                Files.deleteIfExists(source);
                Files.deleteIfExists(temporary);
                forceDirectory(parent);
                return sealedClaim(claimed, sealed);
            }
            if (!Files.isRegularFile(source)) {
                throw new IocExtractorException("Claimed ingest source is missing: " + source);
            }
            Files.deleteIfExists(temporary);
            Files.copy(source, temporary, StandardCopyOption.COPY_ATTRIBUTES);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, sealed, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(parent);
            Files.delete(source);
            forceDirectory(parent);
            return sealedClaim(claimed, sealed);
        } catch (AtomicMoveNotSupportedException failure) {
            deleteTemporary(temporary, failure);
            throw new IocExtractorException("Atomic claim sealing is not supported", failure);
        } catch (IocExtractorException failure) {
            throw failure;
        } catch (IOException failure) {
            deleteTemporary(temporary, failure);
            throw new IocExtractorException("Failed to seal claimed ingest source", failure);
        }
    }

    @Override
    public SourceUnit adoptClaim(ClaimedSource claimed, SourceKey key) {
        if (!Files.isRegularFile(claimed.processingPath())) {
            throw new IocExtractorException(
                    "Claimed ingest source is missing: " + claimed.processingPath());
        }
        return SourceLifecycle.super.adoptClaim(claimed, key);
    }

    @Override
    public Path archive(SourceUnit unit) {
        Path target = doneDir.resolve(fileName(unit.observationId(), unit.key(), unit.processingPath()));
        move(unit.processingPath(), target);
        return target;
    }

    @Override
    public Path archive(ArchivedSourceUnit source) {
        Path target = doneDir.resolve(fileName(
                source.observationId(), source.key(), source.processingPath()));
        move(source.processingPath(), target);
        return target;
    }

    @Override
    public Path archiveDuplicate(Path source, SourceKey key) {
        Path target = doneDir.resolve(fileName(ObservationId.legacy(key.value()), key, source));
        move(source, target);
        return target;
    }

    @Override
    public Path fail(SourceUnit unit, String reason) {
        Path target = failedDir.resolve(fileName(
                unit.observationId(), unit.key(), unit.processingPath()));
        move(unit.processingPath(), target);
        writeErrorSidecar(target, reason);
        return target;
    }

    @Override
    public Path fail(ArchivedSourceUnit source, String reason) {
        Path target = failedDir.resolve(fileName(
                source.observationId(), source.key(), source.processingPath()));
        move(source.processingPath(), target);
        writeErrorSidecar(target, reason);
        return target;
    }

    @Override
    public List<ArchivedSourceUnit> findProcessingSources() {
        if (!Files.exists(processingDir)) {
            return List.of();
        }
        try (var files = Files.list(processingDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(this::toArchivedSource)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new IocExtractorException("Failed to scan processing sources: " + processingDir, e);
        }
    }

    private String fileName(ObservationId observationId, SourceKey key, Path source) {
        Path sourceFileName = source.getFileName();
        String original = sourceFileName == null ? "source" : sourceFileName.toString();
        String prefix = observationId.value().equals("legacy:" + key.value())
                ? key.value() + "-"
                : observationToken(observationId) + "__" + key.value() + "-";
        return original.startsWith(prefix) ? original : prefix + original;
    }

    private String prehashFileName(ObservationId observationId, Path source) {
        Path sourceFileName = source.getFileName();
        String original = sourceFileName == null ? "source" : sourceFileName.toString();
        return observationToken(observationId) + "__pending__" + original;
    }

    private void move(Path source, Path target) {
        ownership.claim(source, target);
    }

    private void writeErrorSidecar(Path failedSource, String reason) {
        Path sidecar = failedSource.resolveSibling(failedSource.getFileName() + ".error");
        try {
            Files.writeString(sidecar, reason == null ? "" : reason);
        } catch (IOException e) {
            throw new IocExtractorException("Failed to write ingest error sidecar: " + sidecar, e);
        }
    }

    private ClaimedSource sealedClaim(ClaimedSource claimed, Path sealed) {
        return new ClaimedSource(claimed.observationId(), claimed.originalPath(), sealed,
                claimed.detectedAt());
    }

    private void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private void deleteTemporary(Path temporary, Exception failure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private ArchivedSourceUnit toArchivedSource(Path source) {
        String filename = source.getFileName().toString();
        int observationSeparator = filename.indexOf("__");
        if (observationSeparator > 0) {
            int contentSeparator = filename.indexOf('-', observationSeparator + 2);
            if (contentSeparator <= observationSeparator + 2) {
                return null;
            }
            try {
                var observationId = observationId(filename.substring(0, observationSeparator));
                var key = new SourceKey(filename.substring(observationSeparator + 2, contentSeparator));
                return new ArchivedSourceUnit(
                        observationId, key, source, Files.getLastModifiedTime(source).toInstant());
            } catch (IllegalArgumentException | IOException invalidP5Name) {
                return null;
            }
        }
        int separator = filename.indexOf('-');
        if (separator <= 0) {
            return null;
        }
        try {
            var detectedAt = Files.getLastModifiedTime(source).toInstant();
            SourceKey key = new SourceKey(filename.substring(0, separator));
            return new ArchivedSourceUnit(ObservationId.legacy(key.value()), key, source, detectedAt);
        } catch (IOException e) {
            throw new IocExtractorException("Failed to inspect processing source: " + source, e);
        }
    }

    private String observationToken(ObservationId observationId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                observationId.value().getBytes(StandardCharsets.UTF_8));
    }

    private ObservationId observationId(String token) {
        return new ObservationId(new String(
                Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
    }

    private static Path requireDirectory(Path directory, String name) {
        Objects.requireNonNull(directory, name);
        if (directory.toString().isBlank()) {
            throw new IllegalArgumentException(name + " must not be an empty path");
        }
        return directory;
    }
}
