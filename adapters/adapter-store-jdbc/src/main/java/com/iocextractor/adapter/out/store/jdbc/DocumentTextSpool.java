package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.domain.refang.RefangRule;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Ordered literal passes over quota-controlled UTF-16 files, including split tokens. */
final class DocumentTextSpool implements AutoCloseable {
    private final Path textPath;
    private final Path replacementPath;
    private final DocumentPreparationLimits limits;
    private final Runnable diskCheck;
    private PagedDocumentText text;
    private SpoolWriter writer;
    private boolean closed;

    DocumentTextSpool(Path directory, DocumentPreparationLimits limits, Runnable diskCheck) {
        textPath = directory.resolve("source-text.utf16");
        replacementPath = directory.resolve("source-refang.utf16");
        this.limits = limits;
        this.diskCheck = diskCheck;
    }
    Writer writer() {
        requireOpen();
        if (writer != null) { throw new IllegalStateException("Source text writer is already supplied"); }
        try { writer = new SpoolWriter(textPath); return writer; }
        catch (IOException failure) { throw new UncheckedIOException("Cannot create source text spool", failure); }
    }
    PagedDocumentText text() {
        requireOpen();
        if (writer == null || !writer.finished) { throw new IllegalStateException("Source text writer is not closed"); }
        if (text == null) {
            try { text = new PagedDocumentText(textPath, limits.maximumFieldBytes()); }
            catch (IOException failure) { throw new UncheckedIOException("Cannot open source text spool", failure); }
        }
        return text;
    }
    int replace(RefangRule rule) {
        requireOpen();
        checkInterrupted();
        if (rule.from().length() > limits.maximumFieldBytes() || rule.to().length() > limits.maximumFieldBytes()) {
            throw new IllegalArgumentException("Refang literal exceeds admitted field limit");
        }
        var input = text();
        int count = 0;
        try (var output = new SpoolWriter(replacementPath)) {
            for (int offset = 0; offset < input.length();) {
                checkInterrupted();
                if (matches(input, offset, rule.from())) {
                    output.write(rule.to());
                    count = Math.incrementExact(count);
                    if (rule.from().isEmpty()) { output.write(input.charAt(offset++)); }
                    else { offset += rule.from().length(); }
                } else { output.write(input.charAt(offset++)); }
            }
            if (rule.from().isEmpty()) { output.write(rule.to()); count = Math.incrementExact(count); }
        } catch (IOException failure) { throw new UncheckedIOException("Cannot rewrite source text spool", failure); }
        try {
            input.close(); text = null;
            Files.move(replacementPath, textPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) { throw new UncheckedIOException("Cannot replace refanged text spool", failure); }
        return count;
    }
    private static boolean matches(CharSequence text, int offset, String token) {
        if (token.length() > text.length() - offset) { return false; }
        for (int index = 0; index < token.length(); index++) {
            if (text.charAt(offset + index) != token.charAt(index)) { return false; }
        }
        return true;
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) { throw new IllegalStateException("Source text processing interrupted"); }
    }
    private void requireOpen() { if (closed) { throw new IllegalStateException("Source spool is closed"); } }
    public void close() throws IOException {
        if (closed) { return; }
        closed = true;
        Throwable failure = null;
        try { if (writer != null) { writer.close(); } }
        catch (IOException | RuntimeException | Error caught) { failure = caught; }
        try { if (text != null) { text.close(); } }
        catch (IOException | RuntimeException | Error caught) { failure = DocumentWorkspaceFailures.accumulate(failure, caught); }
        for (Path path : new Path[]{textPath, replacementPath}) {
            try { Files.deleteIfExists(path); }
            catch (IOException | RuntimeException | Error caught) { failure = DocumentWorkspaceFailures.accumulate(failure, caught); }
        }
        if (failure instanceof Error fatal) { throw fatal; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure != null) { throw (IOException) failure; }
    }
    private final class SpoolWriter extends Writer {
        private final DataOutputStream output;
        private long characters;
        private long checkedBytes;
        private boolean finished;
        private SpoolWriter(Path path) throws IOException {
            output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 16_384));
        }
        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, chars.length);
            for (int index = 0; index < length; index++) { write(chars[offset + index]); }
        }
        @Override
        public void write(int value) throws IOException {
            if (finished) { throw new IOException("Source spool writer is closed"); }
            if (characters >= Integer.MAX_VALUE || characters * 2 >= limits.workspaceBytes() / 4) {
                throw new IOException("Decoded source text exceeds admitted disk or UTF-16 offset limit");
            }
            output.writeChar(value); characters++;
            if (characters * 2 - checkedBytes >= 65_536) { flush(); checkedBytes = characters * 2; }
        }
        public void flush() throws IOException {
            checkInterrupted(); output.flush(); diskCheck.run();
        }
        public void close() throws IOException {
            if (finished) { return; }
            finished = true;
            output.close(); diskCheck.run();
        }
    }
}
