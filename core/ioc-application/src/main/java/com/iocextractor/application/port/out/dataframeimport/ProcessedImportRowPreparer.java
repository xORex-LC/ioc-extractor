package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.mapping.ImportRowMappingResult;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;

/**
 * Driven strategy that applies the ordinary processing policy to one already
 * declaratively mapped import row without owning staging or persistence.
 */
@FunctionalInterface
public interface ProcessedImportRowPreparer {

    /** Replaces derived cells while retaining operator-owned business cells and policies. */
    ImportRowMappingResult prepare(CompiledDataframeImportContract contract,
                                   ImportDelimitedRecord record,
                                   ImportLogicalRow mapped);
}
