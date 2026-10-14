package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.mapping.ImportRowMappingResult;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.port.out.dataframeimport.ProcessedImportRowPreparer;
import java.util.Map;

/** Selects the pinned contract's preparer for both mapping and authority checks. */
final class SelectedProcessedImportRowPreparer implements ProcessedImportRowPreparer {
    private final Map<String, ProcessedImportRowPreparer> routed;

    SelectedProcessedImportRowPreparer(Map<String, ProcessedImportRowPreparer> routed) {
        this.routed = Map.copyOf(routed);
    }

    @Override
    public ImportRowMappingResult prepare(CompiledDataframeImportContract contract,
                                          ImportDelimitedRecord record, ImportLogicalRow mapped) {
        return selected(contract).prepare(contract, record, mapped);
    }

    @Override
    public Session openSession(CompiledDataframeImportContract contract) {
        return selected(contract).openSession(contract);
    }

    @Override
    public boolean authorizesSourceLabel(CompiledDataframeImportContract contract,
                                         String artifact, String target,
                                         ImportCell admitted, ImportCell prepared) {
        return selected(contract).authorizesSourceLabel(contract, artifact, target, admitted, prepared);
    }

    private ProcessedImportRowPreparer selected(CompiledDataframeImportContract contract) {
        var selected = routed.get(contract.id().value());
        if (selected == null) {
            throw new IllegalStateException("Processed contract has no admitted Router binding: "
                    + contract.id().value());
        }
        return selected;
    }
}
