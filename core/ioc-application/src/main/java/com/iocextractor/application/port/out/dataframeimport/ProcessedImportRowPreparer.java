package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.mapping.ImportRowMappingResult;
import com.iocextractor.application.dataframeimport.model.ImportCell;
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

    /** Opens one thread-confined staging attempt; stateless implementations forward by default. */
    default Session openSession(CompiledDataframeImportContract contract) {
        return new com.iocextractor.application.dataframeimport.mapping.UncachedImportPreparationSession(this);
    }

    /** A preparation scope owns no durable state; every row still reaches the workspace. */
    interface Session extends ProcessedImportRowPreparer, AutoCloseable {
        @Override
        default void close() {
            // Stateless forwarding sessions own no resources.
        }
    }

    /** Checks a source-label output against the admitted source cell. */
    default boolean authorizesSourceLabel(CompiledDataframeImportContract contract,
                                          String artifact, String target,
                                          ImportCell admitted, ImportCell prepared) {
        return java.util.Objects.equals(admitted, prepared);
    }
}
