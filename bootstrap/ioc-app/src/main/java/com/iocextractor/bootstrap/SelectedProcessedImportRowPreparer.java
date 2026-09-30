package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.mapping.ImportRowMappingResult;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.port.out.dataframeimport.ProcessedImportRowPreparer;
import java.util.Map;
import java.util.Objects;

/** Selects the pinned contract's preparer for both mapping and authority checks. */
final class SelectedProcessedImportRowPreparer implements ProcessedImportRowPreparer {
    private final ProcessedImportRowPreparer compatible;
    private final Map<String, ProcessedImportRowPreparer> routed;

    SelectedProcessedImportRowPreparer(ProcessedImportRowPreparer compatible,
                                      Map<String, ProcessedImportRowPreparer> routed) {
        this.compatible = Objects.requireNonNull(compatible);
        this.routed = Map.copyOf(routed);
    }

    @Override
    public ImportRowMappingResult prepare(CompiledDataframeImportContract contract,
                                          ImportDelimitedRecord record, ImportLogicalRow mapped) {
        return selected(contract).prepare(contract, record, mapped);
    }

    @Override
    public boolean authorizesSourceLabel(CompiledDataframeImportContract contract,
                                         String artifact, String target,
                                         ImportCell admitted, ImportCell prepared) {
        return selected(contract).authorizesSourceLabel(contract, artifact, target, admitted, prepared);
    }

    private ProcessedImportRowPreparer selected(CompiledDataframeImportContract contract) {
        return routed.getOrDefault(contract.id().value(), compatible);
    }
}
