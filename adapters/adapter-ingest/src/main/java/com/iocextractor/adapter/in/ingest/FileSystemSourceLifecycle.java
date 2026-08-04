package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.ArchivedSourceUnit;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
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

    public FileSystemSourceLifecycle(Path processingDir, Path doneDir, Path failedDir) {
        this.processingDir = requireDirectory(processingDir, "processingDir");
        this.doneDir = requireDirectory(doneDir, "doneDir");
        this.failedDir = requireDirectory(failedDir, "failedDir");
    }

    @Override
    public SourceUnit claim(Path source, SourceKey key, Instant detectedAt) {
        Path target = processingDir.resolve(fileName(key, source));
        move(source, target);
        return new SourceUnit(key, source, target, detectedAt);
    }

    @Override
    public Path archive(SourceUnit unit) {
        Path target = doneDir.resolve(fileName(unit.key(), unit.processingPath()));
        move(unit.processingPath(), target);
        return target;
    }

    @Override
    public Path archive(ArchivedSourceUnit source) {
        Path target = doneDir.resolve(fileName(source.key(), source.processingPath()));
        move(source.processingPath(), target);
        return target;
    }

    @Override
    public Path archiveDuplicate(Path source, SourceKey key) {
        Path target = doneDir.resolve(fileName(key, source));
        move(source, target);
        return target;
    }

    @Override
    public Path fail(SourceUnit unit, String reason) {
        Path target = failedDir.resolve(fileName(unit.key(), unit.processingPath()));
        move(unit.processingPath(), target);
        writeErrorSidecar(target, reason);
        return target;
    }

    @Override
    public Path fail(ArchivedSourceUnit source, String reason) {
        Path target = failedDir.resolve(fileName(source.key(), source.processingPath()));
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

    private String fileName(SourceKey key, Path source) {
        Path sourceFileName = source.getFileName();
        String original = sourceFileName == null ? "source" : sourceFileName.toString();
        String prefix = key.value() + "-";
        return original.startsWith(prefix) ? original : prefix + original;
    }

    private void move(Path source, Path target) {
        Path targetParent = target.getParent();
        if (targetParent == null) {
            throw new IocExtractorException(
                    "Failed to move source file from " + source + " to parentless target " + target);
        }
        try {
            Files.createDirectories(targetParent);
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IocExtractorException("Failed to move source file from " + source + " to " + target, e);
        }
    }

    private void writeErrorSidecar(Path failedSource, String reason) {
        Path sidecar = failedSource.resolveSibling(failedSource.getFileName() + ".error");
        try {
            Files.writeString(sidecar, reason == null ? "" : reason);
        } catch (IOException e) {
            throw new IocExtractorException("Failed to write ingest error sidecar: " + sidecar, e);
        }
    }

    private ArchivedSourceUnit toArchivedSource(Path source) {
        String filename = source.getFileName().toString();
        int separator = filename.indexOf('-');
        if (separator <= 0) {
            return null;
        }
        try {
            var detectedAt = Files.getLastModifiedTime(source).toInstant();
            return new ArchivedSourceUnit(new SourceKey(filename.substring(0, separator)), source, detectedAt);
        } catch (IOException e) {
            throw new IocExtractorException("Failed to inspect processing source: " + source, e);
        }
    }

    private static Path requireDirectory(Path directory, String name) {
        Objects.requireNonNull(directory, name);
        if (directory.toString().isBlank()) {
            throw new IllegalArgumentException(name + " must not be an empty path");
        }
        return directory;
    }
}
