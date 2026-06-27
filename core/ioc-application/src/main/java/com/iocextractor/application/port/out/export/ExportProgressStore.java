package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.ExportProgress;

import java.util.List;

/** Read side of durable export progress used by revision/hash change detection. */
public interface ExportProgressStore {

    List<ExportProgress> findByProfile(String profile);
}
