package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.application.dataframeimport.mapping.ImportHeaderPlan;
import com.iocextractor.application.dataframeimport.model.DelimitedInputLimits;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.out.dataframeimport.DelimitedHeaderReadCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordReader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic exact-one structural recognition over a source allowlist. */
public final class DataframeImportRecognizer {

    private final DataframeImportCatalog catalog;
    private final DelimitedRecordReader reader;

    /** Creates a recognizer over one immutable activated catalog. */
    public DataframeImportRecognizer(DataframeImportCatalog catalog, DelimitedRecordReader reader) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /**
     * Tests every allowlisted charset/dialect/header signature and requires one match.
     * Candidate order and file name never resolve ambiguity.
     */
    public CompiledDataframeImportContract recognize(ImportSourceId sourceId,
                                                      ImportSnapshotReference snapshot,
                                                      DelimitedInputLimits limits) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(limits, "limits");
        DataframeImportCatalogDraft.Source source = catalog.sources().get(sourceId);
        if (source == null) {
            throw new ImportRecognitionException(
                    ImportRecognitionException.Reason.SOURCE_NOT_CONFIGURED,
                    "Import source is not present in the activated catalog");
        }
        List<CompiledDataframeImportContract> matches = new ArrayList<>();
        source.contracts().stream()
                .map(ImportContractId::new)
                .map(catalog.contracts()::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(contract -> contract.id().value()))
                .filter(contract -> matches(snapshot, limits, contract))
                .forEach(matches::add);
        if (matches.isEmpty()) {
            throw new ImportRecognitionException(
                    ImportRecognitionException.Reason.CONTRACT_NOT_RECOGNIZED,
                    "Import delivery matches no allowlisted contract");
        }
        if (matches.size() > 1) {
            throw new ImportRecognitionException(
                    ImportRecognitionException.Reason.CONTRACT_AMBIGUOUS,
                    "Import delivery matches more than one allowlisted contract");
        }
        return matches.getFirst();
    }

    private boolean matches(ImportSnapshotReference snapshot,
                            DelimitedInputLimits limits,
                            CompiledDataframeImportContract contract) {
        try {
            List<String> headers = reader.readHeader(new DelimitedHeaderReadCommand(
                    snapshot, contract.definition().charset(), contract.dialect(), limits));
            ImportHeaderPlan.compile(headers, contract.definition().recognition());
            return true;
        } catch (DelimitedInputReadException | IllegalArgumentException mismatch) {
            return false;
        }
    }
}
