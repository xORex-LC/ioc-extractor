package com.iocextractor.adapter.out.sink.csv;

import java.io.IOException;
import java.io.Writer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * Writer decorator that counts write chunks containing characters the target
 * charset cannot represent, while still delegating to a {@code REPLACE} encoder
 * (so the write never fails). This turns the otherwise <em>silent</em> lossy
 * replacement (e.g. an emoji or CJK domain written to {@code windows-1251}) into
 * an observable signal: callers read {@link #unmappable()} after closing and warn.
 *
 * <p>The count is approximate (chunks, not exact characters) — enough to flag
 * that output for an artifact lost characters, not to pinpoint each one.
 */
final class CountingCharsetWriter extends Writer {

    private final Writer delegate;
    private final CharsetEncoder probe;
    private long unmappable;

    CountingCharsetWriter(Writer delegate, Charset charset) {
        this.delegate = delegate;
        this.probe = charset.newEncoder();
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        if (len > 0 && !probe.canEncode(CharBuffer.wrap(cbuf, off, len))) {
            unmappable++;
        }
        delegate.write(cbuf, off, len);
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        if (len > 0 && !probe.canEncode(str.substring(off, off + len))) {
            unmappable++;
        }
        delegate.write(str, off, len);
    }

    @Override
    public void write(int c) throws IOException {
        if (!probe.canEncode((char) c)) {
            unmappable++;
        }
        delegate.write(c);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    /** Number of write chunks that contained at least one unrepresentable character. */
    long unmappable() {
        return unmappable;
    }
}
