package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.out.dataframeimport.ImportChangeSignalSource;
import com.iocextractor.common.IocExtractorException;

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
        } catch (IOException failure) {
            running.set(false);
            closeWatchService();
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
                // Reconcile remains authoritative; a bad hint must not terminate the process.
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
        if (watchService == null) {
            return;
        }
        try {
            watchService.close();
        } catch (IOException ignored) {
            // Best-effort shutdown of a latency-only source.
        } finally {
            watchService = null;
        }
    }
}
