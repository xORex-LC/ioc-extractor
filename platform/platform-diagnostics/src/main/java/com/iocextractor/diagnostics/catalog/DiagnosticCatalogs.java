package com.iocextractor.diagnostics.catalog;

import com.iocextractor.diagnostics.IocDiagnosticCode;
import com.iocextractor.diagnostics.codes.ClassificationDiagnosticCodes;
import com.iocextractor.diagnostics.codes.ExtractionDiagnosticCodes;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.codes.ImportDiagnosticCodes;
import com.iocextractor.diagnostics.codes.LifecycleDiagnosticCodes;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.codes.SchemaDiagnosticCodes;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import com.iocextractor.diagnostics.codes.StorageDiagnosticCodes;
import com.iocextractor.diagnostics.codes.SyncDiagnosticCodes;

import java.util.Arrays;
import java.util.List;

/**
 * Central aggregate of all diagnostic code catalogs.
 */
public final class DiagnosticCatalogs {

    private static final List<IocDiagnosticCode> ALL = List.copyOf(concat(
            SourceDiagnosticCodes.values(),
            ExtractionDiagnosticCodes.values(),
            ClassificationDiagnosticCodes.values(),
            SinkDiagnosticCodes.values(),
            IngestDiagnosticCodes.values(),
            ImportDiagnosticCodes.values(),
            StorageDiagnosticCodes.values(),
            SchemaDiagnosticCodes.values(),
            ExportDiagnosticCodes.values(),
            SyncDiagnosticCodes.values(),
            LifecycleDiagnosticCodes.values(),
            PipelineDiagnosticCodes.values()));

    private DiagnosticCatalogs() {
    }

    /**
     * Returns all registered diagnostic codes.
     *
     * @return registered diagnostic codes
     */
    public static List<IocDiagnosticCode> all() {
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

    private static List<IocDiagnosticCode> concat(IocDiagnosticCode[]... groups) {
        return Arrays.stream(groups)
                .flatMap(Arrays::stream)
                .toList();
    }
}
