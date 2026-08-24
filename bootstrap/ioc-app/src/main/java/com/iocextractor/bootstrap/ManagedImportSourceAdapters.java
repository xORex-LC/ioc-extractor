package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.csv.ImportSnapshotPathResolver;
import com.iocextractor.application.port.out.dataframeimport.ImportChangeSignalSource;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;

import java.util.List;
import java.util.Objects;

/** Immutable composition result for transport-specific ownership adapters. */
record ManagedImportSourceAdapters(
        ManagedImportSourceLifecycle lifecycle,
        ImportSnapshotPathResolver snapshots,
        List<ImportChangeSignalSource> changeSignals) {

    ManagedImportSourceAdapters {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(snapshots, "snapshots");
        changeSignals = List.copyOf(Objects.requireNonNull(changeSignals, "changeSignals"));
    }
}
