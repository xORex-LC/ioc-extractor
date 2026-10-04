package com.iocextractor.adapter.out.store.jdbc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Durable private metadata and a bounded source copy before a seal can become visible. */
final class DocumentWorkspaceFiles {
    private DocumentWorkspaceFiles() { }

    static void write(Path path, String value) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) { channel.write(buffer); }
            channel.force(true);
        }
        syncDirectory(java.util.Objects.requireNonNull(path.toAbsolutePath().getParent(), "parent directory"));
    }

    static void copy(Path source, Path target, long maximumBytes) throws IOException {
        try (var input = FileChannel.open(source, StandardOpenOption.READ);
             var output = FileChannel.open(target, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.allocate(16384);
            long copied = 0;
            while (input.read(buffer) != -1) {
                if (Thread.currentThread().isInterrupted()) { throw new IOException("Source pinning interrupted"); }
                buffer.flip();
                copied = Math.addExact(copied, buffer.remaining());
                if (copied > maximumBytes) { throw new IOException("Source snapshot exceeds quota"); }
                while (buffer.hasRemaining()) { output.write(buffer); }
                buffer.clear();
            }
            output.force(true);
        }
        syncDirectory(java.util.Objects.requireNonNull(target.toAbsolutePath().getParent(), "parent directory"));
    }

    static void syncDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
}
