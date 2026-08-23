package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.common.IocExtractorException;

/** Expected input-dependent transform rejection without the rejected value. */
public final class ImportValueMappingException extends IocExtractorException {

    /** Creates a safe value-mapping rejection. */
    public ImportValueMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
