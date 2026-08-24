package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Safe bounded report request containing codes and row numbers but no raw values. */
public record PublishImportReportCommand(
        ImportDeliveryId deliveryId,
        ImportSourceId sourceId,
        ImportSnapshotReference snapshotReference,
        Optional<ImportContractPin> contract,
        ImportTerminalOutcome outcome,
        long acceptedRows,
        long rejectedRows,
        long publicMutations,
        Set<String> affectedArtifacts,
        List<String> deliveryCodes,
        List<ImportRowIssue> issues) {

    /** Snapshots report data and counts. */
    public PublishImportReportCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(snapshotReference, "snapshotReference");
        contract = Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(outcome, "outcome");
        affectedArtifacts = Set.copyOf(Objects.requireNonNull(affectedArtifacts, "affectedArtifacts"));
        deliveryCodes = List.copyOf(Objects.requireNonNull(deliveryCodes, "deliveryCodes"));
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (acceptedRows < 0 || rejectedRows < 0 || publicMutations < 0) {
            throw new IllegalArgumentException("Import report counts must not be negative");
        }
        if (deliveryCodes.stream().anyMatch(code -> code == null || code.isBlank())) {
            throw new IllegalArgumentException("Import report delivery codes must not be blank");
        }
    }
}
