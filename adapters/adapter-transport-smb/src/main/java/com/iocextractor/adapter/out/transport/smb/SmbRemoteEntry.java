package com.iocextractor.adapter.out.transport.smb;

import java.time.Instant;
import java.util.Objects;

record SmbRemoteEntry(String path,
                      long size,
                      Instant modifiedAt,
                      boolean directory,
                      boolean reparsePoint,
                      long fileId) {

    SmbRemoteEntry(String path,
                   long size,
                   Instant modifiedAt,
                   boolean directory,
                   long fileId) {
        this(path, size, modifiedAt, directory, false, fileId);
    }

    SmbRemoteEntry {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        modifiedAt = Objects.requireNonNull(modifiedAt, "modifiedAt");
    }

    boolean regularFile() {
        return !directory && !reparsePoint;
    }
}
