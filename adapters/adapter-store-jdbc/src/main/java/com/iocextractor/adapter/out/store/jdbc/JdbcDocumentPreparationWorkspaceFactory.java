package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspace;
import com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspaceFactory;
import com.iocextractor.common.IocExtractorException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Owns private pins and aggregate memory/disk admission; cleanup never touches a live lease. */
public final class JdbcDocumentPreparationWorkspaceFactory implements DocumentPreparationWorkspaceFactory {
    private static final int MAXIMUM_PINS = 128;
    private final Path root;
    private final DocumentPreparationLimits limits;
    private final ArtifactIdentityResolver identities;
    private final String fingerprint;
    private final Duration retention;
    private final Semaphore leases;
    private final Semaphore canonicalControls = new Semaphore(1, true);
    private boolean canonicalHeadroom;
    private Semaphore documentLeases;
    private final Set<Path> active = new HashSet<>();
    private final int leaseLimit;
    private int documentLeaseLimit;
    private boolean intakeStarted;
    private volatile long lastCheckedDiskBytes;
    private volatile int activeWorkspaceCount;

    public JdbcDocumentPreparationWorkspaceFactory(Path root, DocumentPreparationLimits limits,
            ArtifactIdentityResolver identities, String fingerprint) {
        this(root, limits, identities, fingerprint, Duration.ofDays(1));
    }

    public JdbcDocumentPreparationWorkspaceFactory(Path root, DocumentPreparationLimits limits,
            ArtifactIdentityResolver identities, String fingerprint, Duration retention) {
        this.root = root.toAbsolutePath().normalize();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero()) { throw new IllegalArgumentException("Pin retention must be positive"); }
        leaseLimit = Math.toIntExact(Math.min(MAXIMUM_PINS, limits.memoryBytes() / limits.leaseBytes()));
        documentLeaseLimit = leaseLimit;
        this.leases = new Semaphore(leaseLimit, true);
        documentLeases = new Semaphore(leaseLimit, true);
    }

    @Override
    public synchronized int maximumConcurrentWorkspaces() { return documentLeaseLimit; }

    /** Shares the existing memory/disk admission with a native canonical preparation attempt. */
    CanonicalLease openCanonicalState(String identity, String pins) {
        return openCanonicalState(identity, pins, false);
    }

    CanonicalLease openCanonicalState(String identity, String pins, boolean promotion) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(pins, "pins");
        int permits = canonicalLeasePermits();
        boolean controlReserved = reserveCanonicalAdmission(permits, promotion);
        Path directory = root.resolve(ArtifactIdentityDefinition.sha256("canonical:" + identity));
        Lease lease = null;
        boolean admitted = false;
        try {
            synchronized (this) {
                initializeRoot();
                try (var ignored = new Lease(root.resolve(".admission"), true)) {
                    pruneExpired();
                    if (active.contains(directory)) { throw new IocExtractorException("Canonical workspace is already leased"); }
                    reserveDisk(directory);
                    privateDirectory(directory);
                    lease = new Lease(directory.resolve("lease"), false);
                    active.add(directory);
                    activeWorkspaceCount = active.size();
                    admitted = true;
                    Path pin = directory.resolve("identity");
                    String value = "canonical-publication-v1\n" + identity + "\n" + fingerprint + "\n" + pins;
                    if (value.length() > 32768) { throw new IOException("Canonical identity pin exceeds limit"); }
                    if (Files.exists(pin) && !Files.readString(pin).equals(value)) {
                        throw new IOException("Canonical workspace pins changed");
                    }
                    DocumentWorkspaceFiles.write(pin, value);
                    checkDisk(directory);
                    Lease owner = lease;
                    return new CanonicalLease(directory, JdbcCanonicalWorkspaceLimits.privateLimits(limits), () -> checkDisk(directory), () -> {
                        try { release(directory, owner); }
                        finally { leases.release(permits - 1); releaseCanonicalControl(controlReserved); }
                    }, () -> {
                        try { discardCanonical(directory, owner, permits); }
                        finally { releaseCanonicalControl(controlReserved); }
                    });
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            closeFailedLease(directory, lease, failure);
            if (admitted) { synchronized (this) { active.remove(directory); activeWorkspaceCount = active.size(); } }
            leases.release(permits);
            releaseCanonicalControl(controlReserved);
            if (failure instanceof Error fatal) { throw fatal; }
            throw new IocExtractorException("Cannot open canonical preparation workspace", failure);
        }
    }

    private boolean reserveCanonicalAdmission(int permits, boolean promotion) {
        boolean controlReserved;
        synchronized (this) { controlReserved = canonicalHeadroom && !promotion; }
        // Two control/export workspaces must not borrow the working set reserved for promotion.
        if (controlReserved && !canonicalControls.tryAcquire()) {
            throw new IocExtractorException("Canonical control workspace admission exhausted");
        }
        // A document may already own a lease: nested blocking acquisition can deadlock a full pool.
        if (!leases.tryAcquire(permits)) {
            releaseCanonicalControl(controlReserved);
            throw new IocExtractorException("Canonical workspace admission exhausted");
        }
        return controlReserved;
    }

    private void releaseCanonicalControl(boolean reserved) {
        if (reserved) { canonicalControls.release(); }
    }

    int canonicalReadCacheKiB() { return JdbcCanonicalWorkspaceLimits.privateLimits(limits).cacheKiB(); }

    /** Accounts byte-bounded row/chunk bindings and six base/private/stage caches. */
    private int canonicalLeasePermits() {
        long bytes = JdbcCanonicalWorkspaceLimits.memoryBytes(JdbcCanonicalWorkspaceLimits.privateLimits(limits));
        return Math.toIntExact(Math.ceilDiv(bytes, limits.leaseBytes()));
    }

    synchronized void requireCanonicalCapacity() {
        int available = leaseLimit - 2 * canonicalLeasePermits();
        if (available < 1) {
            throw new IllegalArgumentException("Staged canonical capacity requires promotion, control and a separate document lease");
        }
        if (documentLeaseLimit == available) { return; }
        if (intakeStarted || !active.isEmpty()) { throw new IllegalStateException("Canonical headroom must be configured before intake"); }
        documentLeaseLimit = available;
        documentLeases = new Semaphore(available, true);
        canonicalHeadroom = true;
    }

    static final class CanonicalLease implements AutoCloseable {
        private final Path directory;
        private final DocumentPreparationLimits limits;
        private final Runnable checkQuota;
        private final Runnable release;
        private final Runnable discard;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        CanonicalLease(Path directory, DocumentPreparationLimits limits, Runnable checkQuota, Runnable release, Runnable discard) {
            this.directory = directory; this.limits = limits; this.checkQuota = checkQuota;
            this.release = release; this.discard = discard;
        }

        Path directory() { return directory; }
        DocumentPreparationLimits limits() { return limits; }
        void checkQuota() { checkQuota.run(); }

        /** Disposes finished scratch state; durable publication facts live in the dataframe database. */
        void discard() { finish(discard); }

        @Override public void close() { finish(release); }

        void requireOwner() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("Canonical workspace belongs to another thread"); }
        }

        private void finish(Runnable action) {
            requireOwner();
            if (!closed) { closed = true; action.run(); }
        }
    }

    @Override
    public DocumentPreparationWorkspace open(ExtractionCommand command, Map<String, ArtifactWritePolicy> policies) {
        Semaphore documents;
        synchronized (this) { intakeStarted = true; documents = documentLeases; }
        boolean documentAdmitted = false;
        try { documents.acquire(); documentAdmitted = true; leases.acquire(); }
        catch (InterruptedException failure) {
            if (documentAdmitted) { documents.release(); }
            Thread.currentThread().interrupt();
            throw new IocExtractorException("Document workspace admission interrupted", failure);
        }
        String observation = command.lifecycleWriteContext() != null
                ? command.lifecycleWriteContext().observationId().value()
                : command.registration() != null ? command.registration().observationId().value() : command.runId();
        Path directory = root.resolve(ArtifactIdentityDefinition.sha256(observation));
        Lease lease = null;
        boolean admitted = false;
        try {
            synchronized (this) {
                initializeRoot();
                try (var ignored = new Lease(root.resolve(".admission"), true)) {
                    pruneExpired();
                    if (active.contains(directory)) { throw new IocExtractorException("Document workspace is already leased"); }
                    reserveDisk(directory);
                    privateDirectory(directory);
                    lease = new Lease(directory.resolve("lease"), false);
                    active.add(directory);
                    activeWorkspaceCount = active.size();
                    admitted = true;
                    Path snapshot = pinSource(command, directory, observation);
                    checkDisk(directory);
                    Lease owner = lease;
                    return new JdbcDocumentPreparationWorkspace(directory, snapshot, limits, identities, policies,
                            () -> checkDisk(directory), () -> {
                                try { release(directory, owner); }
                                finally { documents.release(); }
                            });
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            closeFailedLease(directory, lease, failure);
            if (admitted) { synchronized (this) { active.remove(directory); activeWorkspaceCount = active.size(); } }
            leases.release();
            documents.release();
            if (failure instanceof Error fatal) { throw fatal; }
            throw new IocExtractorException("Cannot open private document workspace", failure);
        }
    }

    private static void closeFailedLease(Path directory, Lease lease, Throwable failure) {
        if (lease == null) { return; }
        if (!Files.exists(directory.resolve("seal"))) {
            try { deletePrivateDirectory(directory); } catch (IOException error) { failure.addSuppressed(error); }
        }
        try { lease.close(); } catch (IOException error) { failure.addSuppressed(error); }
    }

    private Path pinSource(ExtractionCommand command, Path directory, String observation) throws IOException {
        String filename = Objects.requireNonNull(command.source().getFileName(), "source filename").toString();
        Path snapshot = directory.resolve("source" + extension(filename));
        if (Files.size(command.source()) > limits.workspaceBytes() / 4) {
            throw new IocExtractorException("Document source snapshot exceeds workspace quota");
        }
        String sourceHash = JdbcDocumentPreparationWorkspace.hash(command.source());
        String identity = "document-workspace-v1\n" + observation + "\n" + fingerprint + "\n"
                + sourceHash + "\n" + command.registration() + "\n" + command.lifecycleWriteContext();
        if (identity.length() > 32768) { throw new IocExtractorException("Document pin identity exceeds limit"); }
        Path pin = directory.resolve("identity");
        if (Files.exists(pin, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(pin) || Files.size(pin) > 131072 || !Files.readString(pin).equals(identity)
                    || !sourceHash.equals(JdbcDocumentPreparationWorkspace.hash(snapshot))) {
                throw new IocExtractorException("Document preparation pin identity mismatch");
            }
        } else {
            DocumentWorkspaceFiles.copy(command.source(), snapshot, limits.workspaceBytes() / 4);
            if (!sourceHash.equals(JdbcDocumentPreparationWorkspace.hash(snapshot))) {
                throw new IocExtractorException("Document source changed while pinning");
            }
            DocumentWorkspaceFiles.write(pin, identity);
        }
        return snapshot;
    }

    private void initializeRoot() throws IOException {
        if (Files.isSymbolicLink(root)) { throw new IOException("Private workspace is a symlink"); }
        Files.createDirectories(root);
        Path marker = root.resolve(".owner");
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            try (var contents = Files.list(root)) {
                if (contents.findAny().isPresent()) { throw new IOException("Document workspace root must be empty or owned"); }
            }
            DocumentWorkspaceFiles.write(marker, "ioc-document-workspace-v1\n");
        } else if (Files.isSymbolicLink(marker) || Files.size(marker) != 26
                || !"ioc-document-workspace-v1\n".equals(Files.readString(marker))) {
            throw new IOException("Invalid document workspace ownership marker");
        }
        privateDirectory(root);
    }

    private void reserveDisk(Path directory) throws IOException {
        long reserved = limits.workspaceBytes();
        int pins = 0;
        try (var paths = Files.list(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { continue; }
                pins++;
                if (!path.equals(directory)) {
                    reserved = Math.addExact(reserved, leased(path) ? limits.workspaceBytes() : bytes(path));
                }
            }
        }
        if (pins >= MAXIMUM_PINS && !Files.exists(directory) || reserved > limits.totalDiskBytes()) {
            throw new IocExtractorException("Document preparation disk admission exhausted");
        }
    }

    private boolean leased(Path directory) throws IOException {
        if (active.contains(directory)) { return true; }
        try (var ignored = new Lease(directory.resolve("lease"), false)) { return false; }
        catch (OverlappingFileLockException | BusyLeaseException busy) { return true; }
    }

    private void pruneExpired() throws IOException {
        Instant cutoff = Instant.now().minus(retention);
        try (var paths = Files.list(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (!Objects.requireNonNull(path.getFileName(), "pin filename").toString().matches("[a-f0-9]{64}") || active.contains(path)
                        || Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { continue; }
                Path age = Files.exists(path.resolve("seal")) ? path.resolve("seal") : path;
                if (Files.getLastModifiedTime(age).toInstant().isBefore(cutoff)) {
                    try (var ignored = new Lease(path.resolve("lease"), false)) { deletePrivateDirectory(path); }
                    catch (OverlappingFileLockException | BusyLeaseException busy) { continue; }
                }
            }
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot);
        if (extension.length() > 32) { throw new IocExtractorException("Document extension exceeds limit"); }
        return extension;
    }

    private synchronized void release(Path directory, Lease lease) {
        try { lease.close(); }
        catch (IOException failure) { throw new IocExtractorException("Cannot release document workspace lease", failure); }
        finally { active.remove(directory); activeWorkspaceCount = active.size(); leases.release(); }
    }

    private synchronized void discardCanonical(Path directory, Lease lease, int permits) {
        // Keep admission and the workspace lease until unlinking is complete: a new opener
        // must never lock a replacement lease file while the former owner is retiring it.
        try (var ignored = new Lease(root.resolve(".admission"), true)) {
            deletePrivateDirectory(directory);
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot discard private canonical workspace", failure);
        } finally {
            try { release(directory, lease); }
            finally { leases.release(permits - 1); }
        }
    }

    private synchronized void checkDisk(Path directory) {
        try {
            long workspaceBytes = bytes(directory);
            lastCheckedDiskBytes = bytes(root);
            if (workspaceBytes > limits.workspaceBytes() || lastCheckedDiskBytes > limits.totalDiskBytes()) {
                throw new IocExtractorException("Document preparation disk quota exhausted");
            }
        } catch (IOException failure) { throw new IocExtractorException("Cannot account document workspace disk", failure); }
    }

    private static long bytes(Path directory) throws IOException {
        return DocumentWorkspaceDiskUsage.bytes(directory);
    }

    /** Cached pressure from quota checks; health never scans or locks an active source copy. */
    public Pressure pressure() {
        return new Pressure(activeWorkspaceCount, leaseLimit,
                lastCheckedDiskBytes, limits.totalDiskBytes(), limits.memoryBytes());
    }

    public record Pressure(int leasedWorkspaces, int leaseLimit, long lastCheckedDiskBytes,
                           long diskLimitBytes, long memoryLimitBytes) { }

    static void deletePrivateDirectory(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) { Files.delete(iterator.next()); }
        }
        Files.delete(directory);
    }

    private static void privateDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) { throw new IOException("Private workspace is a symlink"); }
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private Lease(Path path, boolean blocking) throws IOException {
            if (Files.isSymbolicLink(path)) { throw new IOException("Document lease is a symlink"); }
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = blocking ? channel.lock() : channel.tryLock();
                if (lock == null) { throw new BusyLeaseException(); }
            } catch (IOException | RuntimeException failure) {
                try { channel.close(); } catch (IOException error) { failure.addSuppressed(error); }
                throw failure;
            }
        }
        public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }
    private static final class BusyLeaseException extends IOException { }
}
