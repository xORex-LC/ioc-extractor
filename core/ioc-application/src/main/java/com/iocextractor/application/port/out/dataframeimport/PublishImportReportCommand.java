package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;

import java.util.List;
import java.util.Objects;

/** Safe bounded report request containing codes and row numbers but no raw values. */
public record PublishImportReportCommand(
        ImportDeliveryId deliveryId,
        ImportTerminalOutcome outcome,
        long acceptedRows,
        long rejectedRows,
        List<ImportRowIssue> issues) {

    /** Snapshots report data and counts. */
    public PublishImportReportCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(outcome, "outcome");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (acceptedRows < 0 || rejectedRows < 0) {
            throw new IllegalArgumentException("Import report counts must not be negative");
        }
    }
}
