package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.SliceDescriptor;

import java.util.List;

/** Filesystem-neutral port treating each verified completed slice as one retention unit. */
public interface SliceRetentionStore {

    /** Lists only integrity-valid completed slices for one profile. */
    List<SliceDescriptor> listCompleted(String profile);

    /** Revalidates and recursively deletes the complete slice directory. */
    void delete(SliceDescriptor slice);
}
