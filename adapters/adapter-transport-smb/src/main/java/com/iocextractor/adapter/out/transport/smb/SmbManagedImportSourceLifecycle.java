package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceResult;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** SMB ownership adapter: server rename first, then durable private local materialization. */
public final class SmbManagedImportSourceLifecycle implements ManagedImportSourceLifecycle {

    private static final String REFERENCE_PREFIX = "smb-snapshot-v1:";
    private static final String PRIVATE_NAMESPACE = ".ioc-managed-import";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ);

    private final Map<ImportSourceId, SmbImportSourceDefinition> sources;
    private final SmbSessionPool sessions;
    private final Path snapshotRoot;
    private final Duration quietPeriod;
    private final long maximumSnapshotBytes;
    private final Map<CandidateIdentity, StabilitySample> samples = new HashMap<>();
    private final Map<ImportSourceId, Object> sourceLocks;

    /** Creates an SMB source lifecycle over the application-shared session pool. */
    public SmbManagedImportSourceLifecycle(
            List<SmbImportSourceDefinition> definitions,
            SmbSessionPool sessions,
            Path snapshotRoot,
            Duration quietPeriod,
            long maximumSnapshotBytes) {
        Objects.requireNonNull(definitions, "definitions");
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("At least one SMB import source is required");
        }
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.snapshotRoot = prepare(snapshotRoot);
        this.quietPeriod = Objects.requireNonNull(quietPeriod, "quietPeriod");
        if (quietPeriod.isNegative()) {
            throw new IllegalArgumentException("SMB import quiet period must not be negative");
        }
        if (maximumSnapshotBytes < 1) {
            throw new IllegalArgumentException("Maximum SMB import snapshot bytes must be positive");
        }
        this.maximumSnapshotBytes = maximumSnapshotBytes;
        Map<ImportSourceId, SmbImportSourceDefinition> indexed = new HashMap<>();
        Map<ImportSourceId, Object> locks = new HashMap<>();
        for (SmbImportSourceDefinition definition : definitions) {
            if (!sessions.contains(definition.endpoint())) {
                throw new IllegalArgumentException("Unknown SMB import endpoint");
            }
            if (indexed.putIfAbsent(definition.sourceId(), definition) != null) {
                throw new IllegalArgumentException("Duplicate SMB import source ID");
            }
            locks.put(definition.sourceId(), new Object());
        }
        this.sources = Map.copyOf(indexed);
        this.sourceLocks = Map.copyOf(locks);
    }

    @Override
    public List<ImportSourceCandidate> detect(
            ImportSourceId sourceId, Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        SmbImportSourceDefinition source = source(sourceId);
        synchronized (sourceLocks.get(sourceId)) {
            return detect(sourceId, source, observedAt);
        }
    }

    private List<ImportSourceCandidate> detect(
            ImportSourceId sourceId,
            SmbImportSourceDefinition source,
            Instant observedAt) {
        List<SmbRemoteEntry> entries = sessions.withClient(source.endpoint(), "import-list",
                client -> client.list(source.inbox()));
        List<SmbRemoteEntry> files = entries.stream()
                .filter(entry -> !entry.directory())
                .sorted(Comparator.comparing(SmbRemoteEntry::path))
                .toList();
        List<ImportSourceCandidate> stable = new ArrayList<>();
        Set<CandidateIdentity> observed = new java.util.HashSet<>();
        for (SmbRemoteEntry entry : files) {
            String leaf = leaf(entry.path());
            CandidateIdentity identity = new CandidateIdentity(sourceId, leaf);
            observed.add(identity);
            CandidateEvidence evidence = new CandidateEvidence(
                    leaf, entry.size(), entry.modifiedAt(), entry.fileId());
            StabilitySample previous = samples.get(identity);
            Instant unchangedSince = previous != null && previous.evidence().equals(evidence)
                    ? previous.unchangedSince() : observedAt;
            samples.put(identity, new StabilitySample(evidence, unchangedSince));
            if (!observedAt.isBefore(unchangedSince.plus(quietPeriod))) {
                stable.add(new ImportSourceCandidate(sourceId, evidence.token(), observedAt));
            }
        }
        samples.keySet().removeIf(identity -> identity.sourceId().equals(sourceId)
                && !observed.contains(identity));
        return List.copyOf(stable);
    }

    @Override
    public ClaimImportSourceResult claim(ClaimImportSourceCommand command) {
        Objects.requireNonNull(command, "command");
        SmbImportSourceDefinition source = source(command.sourceId());
        CandidateEvidence candidate = CandidateEvidence.parse(command.candidateToken());
        Path published = published(command.deliveryId());
        if (Files.isRegularFile(published, LinkOption.NOFOLLOW_LINKS)) {
            return new ClaimImportSourceResult(inspect(command.deliveryId(), published));
        }

        String producerPath = SmbFileTransport.join(source.inbox(), candidate.leaf());
        String processingRoot = managed(source, "processing");
        String claimedPath = SmbFileTransport.join(processingRoot, deliveryToken(command.deliveryId()) + ".csv");
        sessions.withClient(source.endpoint(), "import-claim", client -> {
            client.createDirectories(processingRoot);
            boolean producerExists = client.fileExists(producerPath);
            boolean claimedExists = client.fileExists(claimedPath);
            if (producerExists && claimedExists) {
                throw new IocExtractorException("SMB import ownership destination collision");
            }
            if (!claimedExists) {
                SmbRemoteEntry current = client.stat(producerPath)
                        .filter(entry -> !entry.directory())
                        .orElseThrow(() -> new IocExtractorException(
                                "SMB import candidate disappeared before claim"));
                if (!candidate.matches(current)) {
                    throw new IocExtractorException("SMB import candidate changed before claim");
                }
                client.rename(producerPath, claimedPath);
            }
            SmbRemoteEntry claimed = client.stat(claimedPath)
                    .filter(entry -> !entry.directory())
                    .orElseThrow(() -> new IocExtractorException(
                            "SMB import claimed object is missing"));
            if (!candidate.matchesContent(claimed)) {
                throw new IocExtractorException(
                        "SMB import claimed object does not match reserved candidate");
            }
            return null;
        });
        return new ClaimImportSourceResult(
                materialize(command.deliveryId(), source, claimedPath, candidate));
    }

    @Override
    public void disposition(DispositionImportSourceCommand command) {
        Objects.requireNonNull(command, "command");
        SmbImportSourceDefinition source = source(command.sourceId());
        String token = deliveryToken(command.deliveryId()) + ".csv";
        String claimed = SmbFileTransport.join(managed(source, "processing"), token);
        String outcomeRoot = managed(source,
                command.outcome() == ImportTerminalOutcome.REJECTED ? "quarantine" : "terminal");
        String destination = SmbFileTransport.join(outcomeRoot, token);
        sessions.withClient(source.endpoint(), "import-disposition", client -> {
            client.createDirectories(outcomeRoot);
            boolean claimedExists = client.fileExists(claimed);
            boolean destinationExists = client.fileExists(destination);
            if (claimedExists && destinationExists) {
                throw new IocExtractorException("SMB import disposition destination collision");
            }
            if (claimedExists) {
                client.rename(claimed, destination);
            } else if (!destinationExists) {
                throw new IocExtractorException("SMB import claimed object is missing during disposition");
            }
            return null;
        });
    }

    @Override
    public void purgeSnapshot(ImportDeliveryId deliveryId, ImportSourceId sourceId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        source(sourceId);
        Path directory = snapshotRoot.resolve(deliveryToken(deliveryId));
        try {
            Files.deleteIfExists(directory.resolve("snapshot.part"));
            Files.deleteIfExists(directory.resolve("snapshot.csv"));
            Files.deleteIfExists(directory);
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot purge SMB import snapshot", failure);
        }
    }

    /** Resolves only immutable local snapshots issued by this SMB adapter. */
    public Path resolveSnapshot(ImportSnapshotReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (!reference.value().startsWith(REFERENCE_PREFIX)) {
            throw new IllegalArgumentException("Unsupported SMB import snapshot reference");
        }
        String token = reference.value().substring(REFERENCE_PREFIX.length());
        if (!token.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Malformed SMB import snapshot reference");
        }
        Path resolved = snapshotRoot.resolve(token).resolve("snapshot.csv").normalize();
        if (!resolved.startsWith(snapshotRoot)) {
            throw new IllegalArgumentException("SMB import snapshot escapes its private root");
        }
        return resolved;
    }

    private ImportSnapshot materialize(
            ImportDeliveryId deliveryId,
            SmbImportSourceDefinition source,
            String claimedPath,
            CandidateEvidence candidate) {
        Path deliveryDirectory = publishedDirectory(deliveryId);
        Path published = deliveryDirectory.resolve("snapshot.csv");
        Path part = published.resolveSibling("snapshot.part");
        try {
            Files.createDirectories(deliveryDirectory);
            protectDirectory(deliveryDirectory);
            Files.deleteIfExists(part);
            SmbRemoteEntry remote = sessions.withClient(source.endpoint(), "import-stat-claimed",
                    client -> client.stat(claimedPath)
                            .filter(entry -> !entry.directory())
                            .orElseThrow(() -> new IocExtractorException(
                                    "SMB import claimed object is missing")));
            if (remote.size() > maximumSnapshotBytes) {
                throw new IocExtractorException("SMB import snapshot exceeds configured byte limit");
            }
            if (!candidate.matchesContent(remote)) {
                throw new IocExtractorException(
                        "SMB import claimed object changed before materialization");
            }
            sessions.withClient(source.endpoint(), "import-materialize", client -> {
                client.download(claimedPath, part);
                return null;
            });
            CopyEvidence evidence = digest(part);
            if (evidence.size() != remote.size()) {
                throw new IocExtractorException("SMB import materialization size mismatch");
            }
            SmbRemoteEntry after = sessions.withClient(source.endpoint(), "import-stat-after-download",
                    client -> client.stat(claimedPath)
                            .filter(entry -> !entry.directory())
                            .orElseThrow(() -> new IocExtractorException(
                                    "SMB import claimed object disappeared during materialization")));
            if (!sameRemoteEvidence(remote, after)) {
                throw new IocExtractorException(
                        "SMB import claimed object changed during materialization");
            }
            force(part);
            try {
                Files.move(part, published, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                Files.deleteIfExists(part);
            }
            protectFile(published);
            force(deliveryDirectory);
            ImportSnapshot snapshot = inspect(deliveryId, published);
            if (snapshot.size() != evidence.size()
                    || !snapshot.digest().value().equals(evidence.sha256())) {
                throw new IocExtractorException(
                        "Published SMB import snapshot does not match materialization");
            }
            return snapshot;
        } catch (IocExtractorException failure) {
            deletePart(part, failure);
            throw failure;
        } catch (IOException failure) {
            deletePart(part, failure);
            throw new IocExtractorException("Cannot durably materialize SMB import snapshot", failure);
        }
    }

    private ImportSnapshot inspect(ImportDeliveryId deliveryId, Path published) {
        if (!Files.isRegularFile(published, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(published)) {
            throw new IocExtractorException("SMB import snapshot is not a protected regular file");
        }
        try {
            CopyEvidence evidence = digest(published);
            return new ImportSnapshot(
                    new ImportSnapshotReference(REFERENCE_PREFIX + deliveryToken(deliveryId)),
                    new ImportSha256(evidence.sha256()), evidence.size());
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot verify SMB import snapshot", failure);
        }
    }

    private CopyEvidence digest(Path file) throws IOException {
        MessageDigest digest = sha256();
        long size;
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            size = input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (size > maximumSnapshotBytes) {
            throw new IocExtractorException("SMB import snapshot exceeds configured byte limit");
        }
        return new CopyEvidence(size, HexFormat.of().formatHex(digest.digest()));
    }

    private Path published(ImportDeliveryId deliveryId) {
        return publishedDirectory(deliveryId).resolve("snapshot.csv");
    }

    private Path publishedDirectory(ImportDeliveryId deliveryId) {
        return snapshotRoot.resolve(deliveryToken(deliveryId));
    }

    private String managed(SmbImportSourceDefinition source, String phase) {
        return SmbFileTransport.join(
                SmbFileTransport.join(source.inbox(), PRIVATE_NAMESPACE), phase);
    }

    private boolean sameRemoteEvidence(SmbRemoteEntry first, SmbRemoteEntry second) {
        return first.size() == second.size()
                && first.modifiedAt().equals(second.modifiedAt())
                && first.fileId() == second.fileId();
    }

    private SmbImportSourceDefinition source(ImportSourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        SmbImportSourceDefinition source = sources.get(sourceId);
        if (source == null) {
            throw new IllegalArgumentException("Unknown SMB import source");
        }
        return source;
    }

    private String leaf(String path) {
        int separator = path.lastIndexOf('/');
        String leaf = separator < 0 ? path : path.substring(separator + 1);
        if (leaf.isBlank() || leaf.contains("\\")) {
            throw new IocExtractorException("SMB import listing returned an unsafe leaf name");
        }
        return leaf;
    }

    static String deliveryToken(ImportDeliveryId deliveryId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        MessageDigest digest = sha256();
        return HexFormat.of().formatHex(digest.digest(
                deliveryId.value().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Path prepare(Path root) {
        Objects.requireNonNull(root, "root");
        try {
            Path normalized = root.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            protectDirectory(normalized);
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot prepare SMB import snapshot root", failure);
        }
    }

    private void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private void protectDirectory(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Platform ACLs remain authoritative on non-POSIX filesystems.
        }
    }

    private void protectFile(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Platform ACLs remain authoritative on non-POSIX filesystems.
        }
    }

    private void deletePart(Path part, Exception primary) {
        try {
            Files.deleteIfExists(part);
        } catch (IOException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private record CandidateIdentity(ImportSourceId sourceId, String leaf) {
    }

    private record StabilitySample(CandidateEvidence evidence, Instant unchangedSince) {
    }

    private record CopyEvidence(long size, String sha256) {
    }

    private record CandidateEvidence(String leaf,
                                     long size,
                                     Instant modifiedAt,
                                     long fileId) {

        private String token() {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    leaf.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "smb-v1:" + encoded + ':' + size + ':' + modifiedAt.getEpochSecond()
                    + ':' + modifiedAt.getNano() + ':' + Long.toUnsignedString(fileId);
        }

        private boolean matches(SmbRemoteEntry entry) {
            return !entry.directory() && size == entry.size()
                    && modifiedAt.equals(entry.modifiedAt())
                    && fileId == entry.fileId()
                    && leaf.equals(entry.path().substring(entry.path().lastIndexOf('/') + 1));
        }

        private boolean matchesContent(SmbRemoteEntry entry) {
            return !entry.directory() && size == entry.size()
                    && modifiedAt.equals(entry.modifiedAt())
                    && fileId == entry.fileId();
        }

        private static CandidateEvidence parse(String token) {
            if (token == null || !token.startsWith("smb-v1:")) {
                throw new IllegalArgumentException("Malformed SMB import candidate token");
            }
            String[] parts = token.substring("smb-v1:".length()).split(":", -1);
            if (parts.length != 5) {
                throw new IllegalArgumentException("Malformed SMB import candidate token");
            }
            try {
                String leaf = new String(Base64.getUrlDecoder().decode(parts[0]),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (leaf.isBlank() || leaf.contains("/") || leaf.contains("\\")
                        || leaf.equals(".") || leaf.equals("..")) {
                    throw new IllegalArgumentException("Unsafe SMB import candidate leaf");
                }
                long size = Long.parseLong(parts[1]);
                if (size < 0) {
                    throw new IllegalArgumentException("Negative SMB import candidate size");
                }
                int nano = Integer.parseInt(parts[3]);
                if (nano < 0 || nano > 999_999_999) {
                    throw new IllegalArgumentException("Invalid SMB import candidate nanoseconds");
                }
                return new CandidateEvidence(
                        leaf,
                        size,
                        Instant.ofEpochSecond(Long.parseLong(parts[2]), nano),
                        Long.parseUnsignedLong(parts[4]));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Malformed SMB import candidate token", failure);
            }
        }
    }
}
