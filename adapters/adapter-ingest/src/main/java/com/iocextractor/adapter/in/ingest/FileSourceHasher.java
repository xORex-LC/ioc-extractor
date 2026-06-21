package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
        } catch (NoSuchAlgorithmException e) {
            throw new IocExtractorException("SHA-256 is not available", e);
        }
    }
}
