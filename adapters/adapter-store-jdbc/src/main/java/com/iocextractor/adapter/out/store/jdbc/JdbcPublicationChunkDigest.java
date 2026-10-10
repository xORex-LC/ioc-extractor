package com.iocextractor.adapter.out.store.jdbc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;

/** Typed payload bound and NULL-aware framing for typed publication journals. */
final class JdbcPublicationChunkDigest {
    private static final int ROW_LIMIT = 256;
    private static final int BYTE_LIMIT = 1024 * 1024;
    private final MessageDigest digest;
    private int rows;
    private long bytes;

    JdbcPublicationChunkDigest() {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    void row() throws SQLException {
        if (++rows > ROW_LIMIT) { throw new SQLException("Typed snapshot total row bound exceeded"); }
    }

    void field(Object value) throws SQLException {
        byte[] encoded = encoded(value);
        bytes += 1L + Integer.BYTES + (encoded == null ? 0 : encoded.length);
        if (bytes > BYTE_LIMIT) { throw new SQLException("Typed snapshot byte bound exceeded"); }
        digest.update(type(value));
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded == null ? -1 : encoded.length).array());
        if (encoded != null) { digest.update(encoded); }
    }

    String finish() { return HexFormat.of().formatHex(digest.digest()); }

    static long framedBytes(Object value) throws SQLException {
        byte[] bytes = encoded(value);
        return 5L + (bytes == null ? 0 : bytes.length);
    }

    private static byte type(Object value) throws SQLException {
        if (value == null) { return 0; }
        if (value instanceof String) { return 1; }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) { return 2; }
        if (value instanceof Float || value instanceof Double) { return 3; }
        if (value instanceof byte[]) { return 4; }
        if (value instanceof Boolean) { return 5; }
        throw new SQLException("Unsupported typed publication value");
    }

    private static byte[] encoded(Object value) throws SQLException {
        type(value);
        if (value == null) { return null; }
        if (value instanceof byte[] bytes) {
            if (bytes.length > BYTE_LIMIT) { throw new SQLException("Typed snapshot byte bound exceeded"); }
            return bytes;
        }
        String text = value.toString();
        if (text.length() > BYTE_LIMIT) { throw new SQLException("Typed snapshot byte bound exceeded"); }
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
