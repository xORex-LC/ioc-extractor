package com.iocextractor.application.port.in.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Advisory validation summary that cannot guarantee a later live import result.
 *
 * @param valid whether the file passed structural/row planning validation
 * @param contractFingerprint exact-one contract when recognized
 * @param sourceRows parsed source row count
 * @param acceptedRows advisory accepted logical row count
 * @param rejectedRows advisory rejected logical row count
 * @param diagnosticCodes bounded safe diagnostic codes
 */
public record ValidateDataframeImportResult(
        boolean valid,
        Optional<ImportContractFingerprint> contractFingerprint,
        long sourceRows,
        long acceptedRows,
        long rejectedRows,
        List<String> diagnosticCodes) {

    /** Snapshots safe result fields. */
    public ValidateDataframeImportResult {
        contractFingerprint = Objects.requireNonNull(contractFingerprint, "contractFingerprint");
        diagnosticCodes = List.copyOf(Objects.requireNonNull(diagnosticCodes, "diagnosticCodes"));
        if (sourceRows < 0 || acceptedRows < 0 || rejectedRows < 0) {
            throw new IllegalArgumentException("Import validation counts must not be negative");
        }
    }
}
