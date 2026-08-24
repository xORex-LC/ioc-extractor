package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;

import java.nio.file.Path;
import java.util.Objects;

/** One configured local managed-import trust boundary. */
public record LocalImportSourceDefinition(ImportSourceId sourceId, Path inbox) {

    /** Requires a source identity and non-empty inbox path. */
    public LocalImportSourceDefinition {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(inbox, "inbox");
        if (inbox.toString().isBlank()) {
            throw new IllegalArgumentException("Local import inbox must not be empty");
        }
    }
}
