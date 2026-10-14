package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.RowCursor;
import com.iocextractor.application.port.out.artifact.RowSource;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Framework-free cursor composition and explicit finite-command adaptation. */
public final class RowSources {
    private RowSources() { }

    public static <T, R> RowSource<R> map(RowSource<T> source, Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");
        return new RowSource<>() {
            public int size() { return source.size(); }
            public RowCursor<R> open() {
                RowCursor<T> cursor = source.open();
                return new RowCursor<>() {
                    private R current;
                    public boolean next() {
                        current = null;
                        if (!cursor.next()) { return false; }
                        current = Objects.requireNonNull(mapper.apply(cursor.value()), "mapped row");
                        return true;
                    }
                    public R value() { return Objects.requireNonNull(current, "current row"); }
                    public void close() { current = null; cursor.close(); }
                };
            }
        };
    }

    public static <T> RowSource<T> of(List<T> rows) {
        List<T> copy = List.copyOf(Objects.requireNonNull(rows, "rows"));
        return new RowSource<>() {
            public int size() { return copy.size(); }
            public RowCursor<T> open() {
                return new RowCursor<>() {
                    private int index = -1;
                    private boolean closed;
                    public boolean next() {
                        if (closed) { throw new IllegalStateException("Cursor is closed"); }
                        if (index < copy.size()) { index++; }
                        return index < copy.size();
                    }
                    public T value() {
                        if (closed || index < 0 || index >= copy.size()) { throw new IllegalStateException("No current row"); }
                        return copy.get(index);
                    }
                    public void close() { closed = true; }
                };
            }
        };
    }
}
