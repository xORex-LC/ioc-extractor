package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes source content keys for idempotent whole-file ingestion.
 */
public final class FileSourceHasher {

    private static final int BUFFER_SIZE = 8192;

    public SourceKey sha256(Path source) {
        try {
            MessageDigest digest = newDigest();
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = Files.newInputStream(source)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return new SourceKey(HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            throw new IocExtractorException("Failed to hash source file: " + source, e);
        }
    }

    /**
     * Computes an identity without reading source contents: a digest of the
     * absolute path plus size/mtime when they are observable, or the path
     * alone otherwise. A later readable copy receives its regular content key
     * and is processed as a new source.
     */
    public SourceKey fingerprint(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        String descriptor;
        try {
            BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class);
            descriptor = normalized + "|" + attributes.size() + "|" + attributes.lastModifiedTime().toMillis();
        } catch (IOException | RuntimeException unreadableMetadata) {
            descriptor = normalized.toString();
        }
        MessageDigest digest = newDigest();
        return new SourceKey(HexFormat.of().formatHex(
                digest.digest(descriptor.getBytes(StandardCharsets.UTF_8))));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IocExtractorException("SHA-256 is not available", e);
        }
    }
}
