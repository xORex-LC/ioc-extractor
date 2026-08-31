package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.model.ImportSha256;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared byte-level digest algorithm for adapter-owned import files. */
final class ImportFileDigests {

    private ImportFileDigests() {
    }

    static ImportSha256 sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path);
             DigestInputStream hashing = new DigestInputStream(input, digest)) {
            hashing.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return new ImportSha256(HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }
}
