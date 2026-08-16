package com.iocextractor.application.artifact.lifecycle;

import java.util.List;
import java.util.Objects;

/** Prepared rows for one artifact recovered from a complete source receipt. */
public record ConfirmationReceiptArtifact(String artifactName,
                                          List<String> header,
                                          List<CanonicalRecordConfirmation> records) {

    public ConfirmationReceiptArtifact {
        Objects.requireNonNull(artifactName, "artifactName");
        header = List.copyOf(Objects.requireNonNull(header, "header"));
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (artifactName.isBlank() || header.isEmpty()) {
            throw new IllegalArgumentException("Receipt artifact identity and header are required");
        }
    }
}
