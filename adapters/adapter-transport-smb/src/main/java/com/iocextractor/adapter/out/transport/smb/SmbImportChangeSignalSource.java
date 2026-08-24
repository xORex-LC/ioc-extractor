package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.out.dataframeimport.ImportChangeSignalSource;
import com.iocextractor.application.port.out.sync.RemoteChangeSignalHandler;
import com.iocextractor.application.port.out.sync.RemoteChangeSignalSource;
import com.iocextractor.application.port.out.sync.RemoteChangeWatch;
import com.iocextractor.application.sync.RemoteWatchTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Transport-neutral dataframe-import doorbell backed by SMB CHANGE_NOTIFY. */
public final class SmbImportChangeSignalSource implements ImportChangeSignalSource {

    private final List<SmbImportSourceDefinition> sources;
    private final RemoteChangeSignalSource watcher;
    private final List<RemoteChangeWatch> watches = new ArrayList<>();

    public SmbImportChangeSignalSource(
            List<SmbImportSourceDefinition> sources,
            RemoteChangeSignalSource watcher) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.watcher = Objects.requireNonNull(watcher, "watcher");
    }

    @Override
    public synchronized void start(Consumer<ImportSourceId> signalConsumer) {
        Objects.requireNonNull(signalConsumer, "signalConsumer");
        if (!watches.isEmpty()) {
            return;
        }
        try {
            for (SmbImportSourceDefinition source : sources) {
                watches.add(watcher.watch(new RemoteWatchTarget(
                        source.sourceId().value(), source.endpoint(), source.inbox()),
                        handler(source.sourceId(), signalConsumer)));
            }
        } catch (RuntimeException failure) {
            closeAfterFailedStart(failure);
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        for (RemoteChangeWatch watch : watches.reversed()) {
            try {
                watch.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        watches.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private void closeAfterFailedStart(RuntimeException primaryFailure) {
        try {
            close();
        } catch (RuntimeException closeFailure) {
            primaryFailure.addSuppressed(closeFailure);
        }
    }

    private RemoteChangeSignalHandler handler(
            ImportSourceId sourceId,
            Consumer<ImportSourceId> consumer) {
        return new RemoteChangeSignalHandler() {
            @Override
            public void signal() {
                consumer.accept(sourceId);
            }

            @Override
            public void established() {
                // Reconcile remains the correctness authority.
            }

            @Override
            public void failed(RuntimeException failure) {
                // The watcher reconnects; periodic complete listing remains active.
            }
        };
    }
}
