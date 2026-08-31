package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.out.dataframeimport.ImportChangeSignalSource;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** NIO WatchService doorbell; event filenames are deliberately discarded. */
public final class LocalImportChangeSignalSource implements ImportChangeSignalSource {

    private static final Logger log = LoggerFactory.getLogger(LocalImportChangeSignalSource.class);

    private final Map<Path, ImportSourceId> sources;
    private final AtomicBoolean running = new AtomicBoolean();
    private WatchService watchService;
    private Thread thread;

    /** Creates a latency hint source for the configured local inbox roots. */
    public LocalImportChangeSignalSource(List<LocalImportSourceDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<Path, ImportSourceId> mapped = new HashMap<>();
        for (LocalImportSourceDefinition definition : definitions) {
            Path path = definition.inbox().toAbsolutePath().normalize();
            if (mapped.putIfAbsent(path, definition.sourceId()) != null) {
                throw new IllegalArgumentException("Duplicate local import watch path");
            }
        }
        this.sources = Map.copyOf(mapped);
    }

    @Override
    public synchronized void start(Consumer<ImportSourceId> signalConsumer) {
        Objects.requireNonNull(signalConsumer, "signalConsumer");
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            WatchService activeWatchService = FileSystems.getDefault().newWatchService();
            watchService = activeWatchService;
            Map<WatchKey, ImportSourceId> keys = new HashMap<>();
            for (Map.Entry<Path, ImportSourceId> source : sources.entrySet()) {
                WatchKey key = source.getKey().register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.OVERFLOW);
                keys.put(key, source.getValue());
            }
            thread = Thread.ofPlatform().daemon().name("dataframe-import-watch").start(
                    () -> watch(activeWatchService, keys, signalConsumer));
        } catch (IOException | RuntimeException failure) {
            running.set(false);
            closeWatchService(failure);
            throw new IocExtractorException("Failed to start local import watch hints", failure);
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        closeWatchService();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void watch(WatchService activeWatchService,
                       Map<WatchKey, ImportSourceId> keys,
                       Consumer<ImportSourceId> signalConsumer) {
        while (running.get()) {
            try {
                WatchKey key = activeWatchService.take();
                ImportSourceId sourceId = keys.get(key);
                key.pollEvents();
                if (sourceId != null) {
                    safeSignal(signalConsumer, sourceId);
                }
                if (!key.reset()) {
                    keys.remove(key);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                if (!running.get()) {
                    return;
                }
                running.set(false);
                LogEvents.warn(log)
                        .action(EventAction.IMPORT_CHANGE_SIGNAL)
                        .outcome(EventOutcome.FAILURE)
                        .field(LogField.ERROR_TYPE, failure.getClass().getName())
                        .message("local managed import change notification stopped; "
                                + "periodic reconcile remains active")
                        .log(failure);
                return;
            }
        }
    }

    private void safeSignal(Consumer<ImportSourceId> signalConsumer, ImportSourceId sourceId) {
        try {
            signalConsumer.accept(sourceId);
        } catch (RuntimeException ignored) {
            // Polling performs the same complete-listing call and recovers the hint.
        }
    }

    private void closeWatchService() {
        closeWatchService(null);
    }

    private void closeWatchService(Throwable primaryFailure) {
        if (watchService == null) {
            return;
        }
        try {
            watchService.close();
        } catch (IOException closeFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(closeFailure);
            }
            // Normal lifecycle shutdown remains best-effort for this latency-only source.
        } finally {
            watchService = null;
        }
    }
}
