package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.out.artifact.RowSource;
import java.util.List;
import java.util.Objects;

/** Prepared rows for one artifact recovered from a complete source receipt. */
public record ConfirmationReceiptArtifact(String artifactName,
                                          List<String> header,
                                          RowSource<CanonicalRecordConfirmation> records) {

    public ConfirmationReceiptArtifact {
        Objects.requireNonNull(artifactName, "artifactName");
        header = List.copyOf(Objects.requireNonNull(header, "header"));
        Objects.requireNonNull(records, "records");
        if (artifactName.isBlank() || header.isEmpty()) {
            throw new IllegalArgumentException("Receipt artifact identity and header are required");
        }
    }
    public ConfirmationReceiptArtifact(String artifactName, List<String> header,
            List<CanonicalRecordConfirmation> records) {
        this(artifactName, header, RowSource.of(records));
    }
}
