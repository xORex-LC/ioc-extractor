package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.csv.ImportSnapshotPathResolver;
import com.iocextractor.application.port.out.dataframeimport.ImportChangeSignalSource;
import com.iocextractor.application.port.out.dataframeimport.ImportSnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.ImportSourceCapability;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;

import java.util.List;
import java.util.Objects;

/** Immutable composition result for transport-specific ownership adapters. */
record ManagedImportSourceAdapters(
        ManagedImportSourceLifecycle lifecycle,
        ImportSnapshotPathResolver snapshots,
        ImportSnapshotStore snapshotStore,
        ImportSourceCapability capability,
        List<ImportChangeSignalSource> changeSignals) {

    ManagedImportSourceAdapters {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(snapshotStore, "snapshotStore");
        Objects.requireNonNull(capability, "capability");
        changeSignals = List.copyOf(Objects.requireNonNull(changeSignals, "changeSignals"));
    }
}
