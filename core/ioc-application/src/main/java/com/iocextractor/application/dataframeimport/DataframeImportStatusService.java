package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryStatus;
import com.iocextractor.application.port.in.dataframeimport.QueryDataframeImportStatusUseCase;
import com.iocextractor.application.port.out.dataframeimport.ImportStatusReader;

import java.util.Objects;

/** Read-only application facade for bounded import status. */
public final class DataframeImportStatusService implements QueryDataframeImportStatusUseCase {

    private final ImportStatusReader reader;

    /** Creates a safe status facade. */
    public DataframeImportStatusService(ImportStatusReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public ImportDeliveryStatus status() {
        return reader.readStatus();
    }
}
