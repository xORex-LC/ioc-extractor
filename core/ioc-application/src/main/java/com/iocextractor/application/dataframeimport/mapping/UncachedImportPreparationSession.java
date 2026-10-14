package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.port.out.dataframeimport.ProcessedImportRowPreparer;
import java.util.Objects;

/** Stateless adaptation preserving the selected preparer's mapping and source authority. */
public final class UncachedImportPreparationSession implements ProcessedImportRowPreparer.Session {
    private final ProcessedImportRowPreparer delegate;

    public UncachedImportPreparationSession(ProcessedImportRowPreparer delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ImportRowMappingResult prepare(CompiledDataframeImportContract contract,
                                          ImportDelimitedRecord record, ImportLogicalRow mapped) {
        return delegate.prepare(contract, record, mapped);
    }

    @Override
    public boolean authorizesSourceLabel(CompiledDataframeImportContract contract,
                                         String artifact, String target,
                                         ImportCell admitted, ImportCell prepared) {
        return delegate.authorizesSourceLabel(contract, artifact, target, admitted, prepared);
    }
}
