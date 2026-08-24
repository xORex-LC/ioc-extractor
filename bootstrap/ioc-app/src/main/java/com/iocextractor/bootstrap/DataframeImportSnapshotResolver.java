package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.cli.ImportPreviewFileLocator;
import com.iocextractor.adapter.in.csv.ImportSnapshotPathResolver;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves durable adapter snapshots plus ephemeral oneshot preview files. */
final class DataframeImportSnapshotResolver
        implements ImportSnapshotPathResolver, ImportPreviewFileLocator {

    private static final String PREVIEW_PREFIX = "preview-file-v1:";

    private final ImportSnapshotPathResolver durable;
    private final long maximumBytes;
    private final Map<String, Path> previews = new ConcurrentHashMap<>();

    DataframeImportSnapshotResolver(ImportSnapshotPathResolver durable, long maximumBytes) {
        this.durable = Objects.requireNonNull(durable, "durable");
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("Maximum import preview bytes must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    @Override
    public ImportSnapshotReference reference(Path file) {
        Objects.requireNonNull(file, "file");
        Path resolved = file.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(resolved)) {
                throw new IllegalArgumentException("Import preview must be a regular non-symbolic file");
            }
            if (Files.size(resolved) > maximumBytes) {
                throw new IllegalArgumentException("Import preview exceeds the configured byte limit");
            }
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot inspect import preview file", failure);
        }
        String token = UUID.randomUUID().toString();
        previews.put(token, resolved);
        return new ImportSnapshotReference(PREVIEW_PREFIX + token);
    }

    @Override
    public Path resolve(ImportSnapshotReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (!reference.value().startsWith(PREVIEW_PREFIX)) {
            return durable.resolve(reference);
        }
        String token = reference.value().substring(PREVIEW_PREFIX.length());
        Path resolved = previews.get(token);
        if (resolved == null) {
            throw new IllegalArgumentException("Unknown or expired import preview reference");
        }
        return resolved;
    }
}
