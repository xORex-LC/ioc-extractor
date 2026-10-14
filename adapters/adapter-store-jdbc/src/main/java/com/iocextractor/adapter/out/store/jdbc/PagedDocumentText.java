package com.iocextractor.adapter.out.store.jdbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Fixed two-page UTF-16 cache. Slices share the owner and do not copy source text. */
final class PagedDocumentText implements CharSequence, AutoCloseable {
    private static final int PAGE_CHARACTERS = 4096;
    private static final int PAGE_COUNT = 2;
    private final FileChannel channel;
    private final int length;
    private final int maximumSlice;
    private final ByteBuffer[] pages = new ByteBuffer[PAGE_COUNT];
    private final int[] pageNumbers = new int[PAGE_COUNT];
    private final long[] ages = new long[PAGE_COUNT];
    private long accesses;
    private int lastSlot;
    private boolean closed;

    PagedDocumentText(Path path, int maximumSlice) throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long bytes = channel.size();
            if (bytes % 2 != 0 || bytes / 2 > Integer.MAX_VALUE) {
                throw new IOException("Source spool has invalid UTF-16 length");
            }
            length = (int) (bytes / 2);
            this.maximumSlice = maximumSlice;
            java.util.Arrays.fill(pageNumbers, -1);
            for (int index = 0; index < PAGE_COUNT; index++) { pages[index] = ByteBuffer.allocate(PAGE_CHARACTERS * 2); }
        } catch (IOException | RuntimeException | Error failure) {
            try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public int length() { requireOpen(); return length; }
    public char charAt(int index) {
        requireOpen();
        Objects.checkIndex(index, length);
        int number = index / PAGE_CHARACTERS;
        int slot = findPage(number);
        if (slot < 0) { slot = oldestPage(); loadPage(slot, number); }
        ages[slot] = ++accesses;
        lastSlot = slot;
        return pages[slot].getChar(index % PAGE_CHARACTERS * 2);
    }
    private int findPage(int number) {
        int slot = pageNumbers[lastSlot] == number ? lastSlot : -1;
        if (slot < 0) {
            for (int candidate = 0; candidate < PAGE_COUNT; candidate++) {
                if (pageNumbers[candidate] == number) { slot = candidate; break; }
            }
        }
        return slot;
    }
    private int oldestPage() {
        int slot = 0;
        for (int candidate = 1; candidate < PAGE_COUNT; candidate++) {
            if (ages[candidate] < ages[slot]) { slot = candidate; }
        }
        return slot;
    }
    private void loadPage(int slot, int number) {
        var page = pages[slot];
        page.clear();
        long position = (long) number * PAGE_CHARACTERS * 2;
        page.limit((int) Math.min(page.capacity(), (long) length * 2 - position));
        try {
            while (page.hasRemaining()) {
                int read = channel.read(page, position + page.position());
                if (read < 0) { throw new IOException("Source spool ended before its pinned length"); }
            }
        } catch (IOException failure) { throw new UncheckedIOException("Cannot read source text page", failure); }
        pageNumbers[slot] = number;
    }
    public CharSequence subSequence(int start, int end) {
        Objects.checkFromToIndex(start, end, length());
        return new Slice(this, start, end - start);
    }
    public String toString() { return sliceString(0, length()); }
    private String sliceString(int start, int size) {
        requireOpen();
        if (size > maximumSlice) { throw new IllegalStateException("Whole-text materialization exceeds admitted field limit"); }
        char[] chars = new char[size];
        for (int index = 0; index < size; index++) { chars[index] = charAt(start + index); }
        return new String(chars);
    }
    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Source text is closed"); }
    }
    public void close() throws IOException { if (!closed) { closed = true; channel.close(); } }
    private record Slice(PagedDocumentText owner, int offset, int length) implements CharSequence {
        public char charAt(int index) { Objects.checkIndex(index, length); return owner.charAt(offset + index); }
        public CharSequence subSequence(int start, int end) {
            Objects.checkFromToIndex(start, end, length);
            return new Slice(owner, offset + start, end - start);
        }
        public String toString() { return owner.sliceString(offset, length); }
    }
}
