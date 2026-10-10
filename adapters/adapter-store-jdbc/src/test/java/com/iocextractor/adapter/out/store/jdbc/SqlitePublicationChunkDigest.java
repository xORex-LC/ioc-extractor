package com.iocextractor.adapter.out.store.jdbc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;

/** Shared test-only bound and NULL-aware framing for typed publication journals. */
final class SqlitePublicationChunkDigest {
    private static final int ROW_LIMIT = 256;
    private static final int BYTE_LIMIT = 1024 * 1024;
    private final MessageDigest digest;
    private int rows;
    private long bytes;

    SqlitePublicationChunkDigest() {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    void row() throws SQLException {
        if (++rows > ROW_LIMIT) { throw new SQLException("Typed snapshot total row bound exceeded"); }
    }

    void field(Object value) throws SQLException {
        String text = value == null ? null : value.toString();
        if (text != null && text.length() > BYTE_LIMIT) {
            throw new SQLException("Typed snapshot byte bound exceeded");
        }
        byte[] encoded = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
        bytes += Integer.BYTES + (encoded == null ? 0 : encoded.length);
        if (bytes > BYTE_LIMIT) { throw new SQLException("Typed snapshot byte bound exceeded"); }
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded == null ? -1 : encoded.length).array());
        if (encoded != null) { digest.update(encoded); }
    }

    String finish() { return HexFormat.of().formatHex(digest.digest()); }
}
