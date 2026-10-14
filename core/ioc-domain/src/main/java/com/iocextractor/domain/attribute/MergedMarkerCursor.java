package com.iocextractor.domain.attribute;

import com.iocextractor.domain.extract.MatchCursor;
import com.iocextractor.domain.extract.PatternEngine;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Merges bounded matcher heads using the same precedence as batch attribution. */
final class MergedMarkerCursor implements MarkerCursor {
    private static final Comparator<Head> ORDER = Comparator.comparingInt((Head head) -> head.cursor.start())
            .thenComparing(Comparator.comparingInt((Head head) -> head.cursor.end() - head.cursor.start()).reversed())
            .thenComparingInt(head -> head.pattern).thenComparingInt(head -> head.view);
    private final PriorityQueue<Head> heads = new PriorityQueue<>(ORDER);
    private final int limit;
    private int previousEnd = -1;
    private SourceMarker current;

    MergedMarkerCursor(List<PatternEngine.Compiled> patterns, CharSequence text, int limit) {
        this.limit = limit;
        CharSequence normalized = new NbspText(text, 0, text.length());
        boolean hasNbsp = false;
        for (int offset = 0; offset < text.length(); offset++) {
            if (text.charAt(offset) == '\u00a0') { hasNbsp = true; break; }
        }
        for (int pattern = 0; pattern < patterns.size(); pattern++) {
            add(new Head(patterns.get(pattern).matches(text), pattern, 0));
            if (hasNbsp) { add(new Head(patterns.get(pattern).matches(normalized), pattern, 1)); }
        }
    }

    private void add(Head head) {
        if (head.cursor.next()) { heads.add(head); }
    }

    @Override
    public boolean next() {
        current = null;
        while (!heads.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Document attribution interrupted");
            }
            var head = heads.remove();
            int start = head.cursor.start();
            int end = head.cursor.end();
            if (end - start > limit) { throw new IllegalArgumentException("Source marker exceeds admitted field limit"); }
            if (start >= previousEnd) {
                String label = head.cursor.value().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
                current = new SourceMarker(start, label);
                previousEnd = end;
            }
            add(head);
            if (current != null) { return true; }
        }
        return false;
    }

    @Override
    public SourceMarker value() {
        if (current == null) { throw new IllegalStateException("Marker cursor has no current value"); }
        return current;
    }

    private record Head(MatchCursor cursor, int pattern, int view) { }

    private record NbspText(CharSequence source, int offset, int length) implements CharSequence {
        public char charAt(int index) {
            java.util.Objects.checkIndex(index, length);
            char value = source.charAt(offset + index);
            return value == '\u00a0' ? ' ' : value;
        }
        public CharSequence subSequence(int start, int end) {
            java.util.Objects.checkFromToIndex(start, end, length);
            return new NbspText(source, offset + start, end - start);
        }
        public String toString() {
            var chars = new char[length];
            for (int index = 0; index < length; index++) { chars[index] = charAt(index); }
            return new String(chars);
        }
    }
}
