package com.iocextractor.diagnostics.catalog;

import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.codes.ClassificationDiagnosticCodes;
import com.iocextractor.diagnostics.codes.ConfigDiagnosticCodes;
import com.iocextractor.diagnostics.codes.ExtractionDiagnosticCodes;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;

import java.util.Arrays;
import java.util.List;

/**
 * Central aggregate of all diagnostic code catalogs.
 */
public final class DiagnosticCatalogs {

    private static final List<DiagnosticCode> ALL = List.copyOf(concat(
            ConfigDiagnosticCodes.values(),
            SourceDiagnosticCodes.values(),
            ExtractionDiagnosticCodes.values(),
            ClassificationDiagnosticCodes.values(),
            SinkDiagnosticCodes.values(),
            PipelineDiagnosticCodes.values()));

    private DiagnosticCatalogs() {
    }

    /**
     * Returns all registered diagnostic codes.
     *
     * @return registered diagnostic codes
     */
    public static List<DiagnosticCode> all() {
        return ALL;
    }

    /**
     * Returns flattened entries for all registered diagnostic codes.
     *
     * @return catalog entries
     */
    public static List<DiagnosticCatalogEntry> entries() {
        return ALL.stream()
                .map(DiagnosticCatalogEntry::from)
                .toList();
    }

    private static List<DiagnosticCode> concat(DiagnosticCode[]... groups) {
        return Arrays.stream(groups)
                .flatMap(Arrays::stream)
                .toList();
    }
}
