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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cross-process profile formation/recovery exclusion backed by local NIO file locks. */
public final class NioExportOperationGuard implements ExportOperationGuard {

    private final Path root;
    private final Set<String> activeProfiles = new HashSet<>();

    public NioExportOperationGuard(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public synchronized Lease acquire(String profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile.isBlank()) {
            throw new IllegalArgumentException("Export profile must not be blank");
        }
        if (!activeProfiles.add(profile)) {
            throw busy(null);
        }
        FileChannel channel = null;
        try {
            Files.createDirectories(root);
            channel = FileChannel.open(root.resolve(".formation-" + lockName(profile) + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                NioExportOperationGuard.close(channel, null);
                activeProfiles.remove(profile);
                throw busy(null);
            }
            return new FileLease(channel, lock, () -> released(profile));
        } catch (OverlappingFileLockException conflict) {
            close(channel, conflict);
            activeProfiles.remove(profile);
            throw busy(conflict);
        } catch (RuntimeException failure) {
            close(channel, failure);
            activeProfiles.remove(profile);
            throw failure;
        } catch (IOException failure) {
            close(channel, failure);
            activeProfiles.remove(profile);
            throw new IocExtractorException("Cannot acquire export operation lock at " + root, failure);
        }
    }

    private synchronized void released(String profile) {
        activeProfiles.remove(profile);
    }

    private static String lockName(String profile) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(profile.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", impossible);
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
        private final Runnable releaseLocal;
        private final AtomicBoolean closed = new AtomicBoolean();

        private FileLease(FileChannel channel, FileLock lock, Runnable releaseLocal) {
            this.channel = channel;
            this.lock = lock;
            this.releaseLocal = releaseLocal;
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
                releaseLocal.run();
            }
        }
    }
}
