package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;

import java.util.Objects;

/** One SMB managed-import source and its endpoint-relative producer directory. */
public record SmbImportSourceDefinition(
        ImportSourceId sourceId,
        String endpoint,
        String inbox) {

    public SmbImportSourceDefinition {
        Objects.requireNonNull(sourceId, "sourceId");
        endpoint = requireText(endpoint, "endpoint");
        inbox = SmbFileTransport.normalizeRemotePath(requireText(inbox, "inbox"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
