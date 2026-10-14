package com.iocextractor.adapter.out.store.jdbc;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** Counts observed regular-file bytes without following links or rereading racing file attributes. */
final class DocumentWorkspaceDiskUsage extends SimpleFileVisitor<Path> {
    private static final Set<String> SQLITE_SIDECARS = Set.of(
            "native.db-wal", "native.db-shm", "native.db-journal",
            "prepared.db-wal", "prepared.db-shm", "prepared.db-journal");
    private final Path directory;
    private long bytes;

    DocumentWorkspaceDiskUsage(Path directory) { this.directory = directory; }

    static long bytes(Path directory) throws IOException {
        var usage = new DocumentWorkspaceDiskUsage(directory);
        Files.walkFileTree(directory, usage);
        return usage.bytes;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
        if (attributes.isSymbolicLink()) { throw new IOException("Symlink in private document workspace"); }
        if (attributes.isRegularFile()) { bytes = Math.addExact(bytes, attributes.size()); }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
        // SQLite may unlink WAL/SHM/journal files after listing, before the walker reads attributes.
        Path filename = file.getFileName();
        if (failure instanceof NoSuchFileException && !file.equals(directory) && filename != null
                && SQLITE_SIDECARS.contains(filename.toString())) {
            return FileVisitResult.CONTINUE;
        }
        throw failure;
    }
}
