package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.port.out.export.ExportOperationGuard;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Cross-process formation/recovery exclusion backed by a local NIO file lock. */
public final class NioExportOperationGuard implements ExportOperationGuard {

    private static final String LOCK_FILE = ".formation.lock";

    private final Path root;
    private final ReentrantLock local = new ReentrantLock();

    public NioExportOperationGuard(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public Lease acquire() {
        if (!local.tryLock()) {
            throw busy(null);
        }
        FileChannel channel = null;
        try {
            Files.createDirectories(root);
            channel = FileChannel.open(root.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                NioExportOperationGuard.close(channel, null);
                local.unlock();
                throw busy(null);
            }
            return new FileLease(channel, lock, local);
        } catch (OverlappingFileLockException conflict) {
            close(channel, conflict);
            local.unlock();
            throw busy(conflict);
        } catch (IOException failure) {
            close(channel, failure);
            local.unlock();
            throw new IocExtractorException("Cannot acquire export operation lock at " + root, failure);
        }
    }

    private IllegalStateException busy(Throwable cause) {
        return new IllegalStateException("Another export formation or recovery operation is active", cause);
    }

    private static void close(FileChannel channel, Throwable original) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            if (original != null) {
                original.addSuppressed(closeFailure);
            }
        }
    }

    private static final class FileLease implements Lease {
        private final FileChannel channel;
        private final FileLock lock;
        private final ReentrantLock local;
        private final AtomicBoolean closed = new AtomicBoolean();

        private FileLease(FileChannel channel, FileLock lock, ReentrantLock local) {
            this.channel = channel;
            this.lock = lock;
            this.local = local;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                lock.release();
            } catch (IOException failure) {
                throw new IocExtractorException("Cannot release export operation lock", failure);
            } finally {
                NioExportOperationGuard.close(channel, null);
                local.unlock();
            }
        }
    }
}
