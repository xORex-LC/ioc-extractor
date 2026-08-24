package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceResult;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
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

/**
 * Strict local managed-import adapter.
 *
 * <p>Detection is a complete direct-child listing. Claim is a no-replace
 * atomic rename followed by a separately forced immutable byte snapshot. No
 * parser can resolve a source-inbox or mutable processing path through this
 * adapter.</p>
 */
public final class LocalManagedImportSourceLifecycle implements ManagedImportSourceLifecycle {

    static final String REFERENCE_PREFIX = LocalImportSnapshotPathResolver.REFERENCE_PREFIX;
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ);

    private final Map<ImportSourceId, Path> inboxes;
    private final Path processingRoot;
    private final Path snapshotRoot;
    private final Path terminalRoot;
    private final Path quarantineRoot;
    private final Duration quietPeriod;
    private final long maximumSnapshotBytes;
    private final StrictAtomicFileOwnership ownership;
    private final SnapshotCopier snapshotCopier;
    private final Map<CandidateIdentity, StabilitySample> samples = new HashMap<>();

    /** Creates protected local intake roots and validates that trust boundaries do not overlap. */
    public LocalManagedImportSourceLifecycle(List<LocalImportSourceDefinition> sources,
                                             Path processingRoot,
                                             Path snapshotRoot,
                                             Path terminalRoot,
                                             Path quarantineRoot,
                                             Duration quietPeriod,
                                             long maximumSnapshotBytes) {
        this(sources, processingRoot, snapshotRoot, terminalRoot, quarantineRoot,
                quietPeriod, maximumSnapshotBytes, new StrictAtomicFileOwnership(),
                LocalManagedImportSourceLifecycle::copyAndDigest);
    }

    LocalManagedImportSourceLifecycle(List<LocalImportSourceDefinition> sources,
                                      Path processingRoot,
                                      Path snapshotRoot,
                                      Path terminalRoot,
                                      Path quarantineRoot,
                                      Duration quietPeriod,
                                      long maximumSnapshotBytes,
                                      StrictAtomicFileOwnership ownership,
                                      SnapshotCopier snapshotCopier) {
        Objects.requireNonNull(sources, "sources");
        this.quietPeriod = Objects.requireNonNull(quietPeriod, "quietPeriod");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.snapshotCopier = Objects.requireNonNull(snapshotCopier, "snapshotCopier");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("At least one local import source is required");
        }
        if (quietPeriod.isNegative()) {
            throw new IllegalArgumentException("Import quiet period must not be negative");
        }
        if (maximumSnapshotBytes < 1) {
            throw new IllegalArgumentException("Maximum import snapshot bytes must be positive");
        }
        this.maximumSnapshotBytes = maximumSnapshotBytes;
        this.processingRoot = prepareDirectory(processingRoot, "processingRoot");
        this.snapshotRoot = prepareDirectory(snapshotRoot, "snapshotRoot");
        this.terminalRoot = prepareDirectory(terminalRoot, "terminalRoot");
        this.quarantineRoot = prepareDirectory(quarantineRoot, "quarantineRoot");
        this.inboxes = prepareSources(sources);
        requireDisjointRoots();
    }

    @Override
    public synchronized List<ImportSourceCandidate> detect(ImportSourceId sourceId, Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        Path inbox = requiredInbox(sourceId);
        List<Path> candidates;
        try (var entries = Files.list(inbox)) {
            candidates = entries.sorted(Comparator.comparing(this::fileName)).toList();
        } catch (IOException failure) {
            throw storageFailure("Failed to list local import source", failure);
        }

        List<ImportSourceCandidate> stable = new ArrayList<>();
        Set<CandidateIdentity> observed = new java.util.HashSet<>();
        for (Path candidate : candidates) {
            CandidateIdentity identity = new CandidateIdentity(sourceId, fileName(candidate));
            observed.add(identity);
            BasicFileAttributes attributes = candidateAttributes(candidate);
            if (attributes == null) {
                samples.remove(identity);
                continue;
            }
            FileIdentity file = FileIdentity.from(candidate, attributes);
            StabilitySample previous = samples.get(identity);
            Instant unchangedSince = previous != null && previous.file().equals(file)
                    ? previous.unchangedSince() : observedAt;
            samples.put(identity, new StabilitySample(file, unchangedSince));
            if (!observedAt.isBefore(unchangedSince.plus(quietPeriod))) {
                stable.add(new ImportSourceCandidate(sourceId, file.token(), observedAt));
            }
        }
        samples.keySet().removeIf(identity -> identity.sourceId().equals(sourceId) && !observed.contains(identity));
        return List.copyOf(stable);
    }

    @Override
    public ClaimImportSourceResult claim(ClaimImportSourceCommand command) {
        Objects.requireNonNull(command, "command");
        Path inbox = requiredInbox(command.sourceId());
        FileIdentity candidate = FileIdentity.parse(command.candidateToken());
        Path source = containedChild(inbox, candidate.fileName());
        Path deliveryProcessing = processingRoot.resolve(deliveryToken(command.deliveryId()));
        Path claimed = deliveryProcessing.resolve("source");
        Path deliverySnapshot = snapshotRoot.resolve(deliveryToken(command.deliveryId()));
        Path published = deliverySnapshot.resolve("snapshot.csv");

        if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
            return new ClaimImportSourceResult(inspectPublished(command.deliveryId(), published));
        }
        boolean sourceExists = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
        boolean claimedExists = Files.exists(claimed, LinkOption.NOFOLLOW_LINKS);
        if (sourceExists && claimedExists) {
            throw new IocExtractorException("Source ownership target already exists");
        }
        if (!claimedExists) {
            revalidateCandidate(source, candidate);
            ownership.claim(source, claimed);
            protectDirectory(deliveryProcessing);
        }
        return new ClaimImportSourceResult(materialize(command.deliveryId(), claimed, published));
    }

    @Override
    public void disposition(DispositionImportSourceCommand command) {
        Objects.requireNonNull(command, "command");
        String token = deliveryToken(command.deliveryId());
        Path claimed = processingRoot.resolve(token).resolve("source");
        if (!Files.exists(claimed, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path outcomeRoot = command.outcome() == com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome.REJECTED
                ? quarantineRoot : terminalRoot;
        Path terminalUnit = outcomeRoot.resolve(token);
        if (!Files.isRegularFile(terminalUnit.resolve("source.csv"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(terminalUnit.resolve("report.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IocExtractorException("Protected import terminal unit is not published");
        }
        try {
            Files.delete(claimed);
            Files.deleteIfExists(requiredParent(claimed));
        } catch (IOException failure) {
            throw storageFailure("Failed to release finalized local import source", failure);
        }
    }

    @Override
    public void purgeSnapshot(ImportDeliveryId deliveryId, ImportSourceId sourceId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        requiredInbox(sourceId);
        Path directory = snapshotRoot.resolve(deliveryToken(deliveryId));
        try {
            Files.deleteIfExists(directory.resolve("snapshot.part"));
            Files.deleteIfExists(directory.resolve("snapshot.csv"));
            Files.deleteIfExists(directory);
        } catch (IOException failure) {
            throw storageFailure("Failed to purge local import snapshot", failure);
        }
    }

    /** Resolves only adapter-issued immutable references for the CSV reader. */
    public Path resolveSnapshot(ImportSnapshotReference reference) {
        return new LocalImportSnapshotPathResolver(snapshotRoot).resolve(reference);
    }

    private ImportSnapshot materialize(ImportDeliveryId deliveryId, Path claimed, Path published) {
        BasicFileAttributes before = requiredRegularAttributes(claimed, "Claimed import source is not regular");
        if (before.size() > maximumSnapshotBytes) {
            throw new IocExtractorException("Import snapshot exceeds configured byte limit");
        }
        Path part = published.resolveSibling("snapshot.part");
        try {
            Path publishedParent = requiredParent(published);
            Files.createDirectories(publishedParent);
            protectDirectory(publishedParent);
            CopyEvidence evidence = snapshotCopier.copy(claimed, part, maximumSnapshotBytes);
            force(part);
            BasicFileAttributes after = requiredRegularAttributes(claimed, "Claimed import source changed type");
            if (!sameSource(before, after) || evidence.size() != after.size()) {
                Files.deleteIfExists(part);
                throw new IocExtractorException("Claimed import source changed while snapshotting");
            }
            try {
                Files.move(part, published, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException collision) {
                Files.deleteIfExists(part);
            }
            forceDirectory(publishedParent);
            protectFile(published);
            ImportSnapshot snapshot = inspectPublished(deliveryId, published);
            if (!snapshot.digest().value().equals(evidence.sha256()) || snapshot.size() != evidence.size()) {
                throw new IocExtractorException("Published import snapshot evidence does not match materialization");
            }
            return snapshot;
        } catch (IocExtractorException failure) {
            deletePart(part, failure);
            throw failure;
        } catch (IOException failure) {
            deletePart(part, failure);
            throw storageFailure("Failed to materialize local import snapshot", failure);
        }
    }

    private ImportSnapshot inspectPublished(ImportDeliveryId deliveryId, Path published) {
        requiredRegularAttributes(published, "Published import snapshot is not regular");
        try {
            CopyEvidence evidence = digest(published, maximumSnapshotBytes);
            return new ImportSnapshot(reference(deliveryId), new ImportSha256(evidence.sha256()), evidence.size());
        } catch (IOException failure) {
            throw storageFailure("Failed to verify local import snapshot", failure);
        }
    }

    private void revalidateCandidate(Path source, FileIdentity candidate) {
        BasicFileAttributes attributes = requiredRegularAttributes(source, "Import candidate is no longer regular");
        if (!candidate.equals(FileIdentity.from(source, attributes))) {
            throw new IocExtractorException("Import candidate changed after detection");
        }
    }

    private BasicFileAttributes candidateAttributes(Path candidate) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !Files.isSymbolicLink(candidate) ? attributes : null;
        } catch (IOException failure) {
            throw storageFailure("Failed to inspect local import candidate", failure);
        }
    }

    private BasicFileAttributes requiredRegularAttributes(Path path, String message) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
                throw new IocExtractorException(message);
            }
            return attributes;
        } catch (IOException failure) {
            throw storageFailure(message, failure);
        }
    }

    private boolean sameSource(BasicFileAttributes first, BasicFileAttributes second) {
        return first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime())
                && Objects.equals(first.fileKey(), second.fileKey());
    }

    private Map<ImportSourceId, Path> prepareSources(List<LocalImportSourceDefinition> sources) {
        Map<ImportSourceId, Path> result = new HashMap<>();
        for (LocalImportSourceDefinition source : sources) {
            Path inbox = prepareDirectory(source.inbox(), "source inbox");
            if (result.putIfAbsent(source.sourceId(), inbox) != null) {
                throw new IllegalArgumentException("Duplicate local import source ID: " + source.sourceId().value());
            }
        }
        return Map.copyOf(result);
    }

    private void requireDisjointRoots() {
        List<Path> managed = List.of(processingRoot, snapshotRoot, terminalRoot, quarantineRoot);
        List<Path> all = new ArrayList<>(managed);
        all.addAll(inboxes.values());
        for (int left = 0; left < all.size(); left++) {
            for (int right = left + 1; right < all.size(); right++) {
                if (overlaps(all.get(left), all.get(right))) {
                    throw new IllegalArgumentException("Managed import paths must not overlap");
                }
            }
        }
    }

    private boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private Path prepareDirectory(Path directory, String name) {
        Objects.requireNonNull(directory, name);
        if (directory.toString().isBlank()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        Path normalized = directory.toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(normalized)) {
                throw new IllegalArgumentException(name + " must not be a symbolic link");
            }
            Files.createDirectories(normalized);
            Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
            protectDirectory(real);
            return real;
        } catch (IOException failure) {
            throw storageFailure("Failed to prepare " + name, failure);
        }
    }

    private Path requiredInbox(ImportSourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        Path inbox = inboxes.get(sourceId);
        if (inbox == null) {
            throw new IllegalArgumentException("Unknown local import source: " + sourceId.value());
        }
        return inbox;
    }

    private Path containedChild(Path parent, String fileName) {
        Path relative = Path.of(fileName);
        if (relative.isAbsolute() || relative.getNameCount() != 1 || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("Import candidate path is not a direct child");
        }
        Path resolved = parent.resolve(relative).normalize();
        if (!requiredParent(resolved).equals(parent)) {
            throw new IllegalArgumentException("Import candidate escapes its source root");
        }
        return resolved;
    }

    private ImportSnapshotReference reference(ImportDeliveryId deliveryId) {
        return referenceFor(deliveryId);
    }

    private Path requiredParent(Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Managed import path must have a parent");
        }
        return parent;
    }

    private String fileName(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("Managed import candidate must have a filename");
        }
        return name.toString();
    }

    static ImportSnapshotReference referenceFor(ImportDeliveryId deliveryId) {
        return LocalImportSnapshotPathResolver.referenceFor(deliveryId);
    }

    static String deliveryToken(ImportDeliveryId deliveryId) {
        return sha256(deliveryId.value());
    }

    static CopyEvidence copyAndDigest(Path source, Path target, long limit) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        MessageDigest digest = newDigest();
        long size = 0;
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(target, options.toArray(OpenOption[]::new))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                size += read;
                if (size > limit) {
                    throw new IocExtractorException("Import snapshot exceeds configured byte limit");
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        }
        return new CopyEvidence(HexFormat.of().formatHex(digest.digest()), size);
    }

    static CopyEvidence digest(Path path, long limit) throws IOException {
        MessageDigest digest = newDigest();
        long size = 0;
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                size += read;
                if (size > limit) {
                    throw new IocExtractorException("Import snapshot exceeds configured byte limit");
                }
                digest.update(buffer, 0, read);
            }
        }
        return new CopyEvidence(HexFormat.of().formatHex(digest.digest()), size);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sha256(String value) {
        MessageDigest digest = newDigest();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private void protectDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX providers retain their platform ACLs.
        } catch (IOException failure) {
            throw storageFailure("Failed to protect managed import directory", failure);
        }
    }

    private void protectFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, PRIVATE_FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX providers retain their platform ACLs.
        } catch (IOException failure) {
            throw storageFailure("Failed to protect managed import snapshot", failure);
        }
    }

    private void deletePart(Path part, Throwable original) {
        try {
            Files.deleteIfExists(part);
        } catch (IOException cleanup) {
            original.addSuppressed(cleanup);
        }
    }

    private IocExtractorException storageFailure(String message, Throwable cause) {
        return new IocExtractorException(message, cause);
    }

    @FunctionalInterface
    interface SnapshotCopier {
        CopyEvidence copy(Path source, Path target, long maximumBytes) throws IOException;
    }

    record CopyEvidence(String sha256, long size) {
    }

    private record CandidateIdentity(ImportSourceId sourceId, String fileName) {
    }

    private record StabilitySample(FileIdentity file, Instant unchangedSince) {
    }

    private record FileIdentity(String fileName, long size, long modifiedAtMillis, String fileKeyHash) {

        private static FileIdentity from(Path path, BasicFileAttributes attributes) {
            String key = Objects.toString(attributes.fileKey(), "unavailable");
            Path name = path.getFileName();
            if (name == null) {
                throw new IllegalArgumentException("Import candidate must have a filename");
            }
            return new FileIdentity(name.toString(), attributes.size(),
                    attributes.lastModifiedTime().toMillis(), sha256(key));
        }

        private String token() {
            String encodedName = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    fileName.getBytes(StandardCharsets.UTF_8));
            return "v1." + encodedName + "." + size + "." + modifiedAtMillis + "." + fileKeyHash;
        }

        private static FileIdentity parse(String token) {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 5 || !parts[0].equals("v1") || !parts[4].matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Malformed local import candidate token");
            }
            try {
                String fileName = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                long size = Long.parseLong(parts[2]);
                long modifiedAt = Long.parseLong(parts[3]);
                if (size < 0 || modifiedAt < 0) {
                    throw new IllegalArgumentException("Malformed local import candidate token");
                }
                return new FileIdentity(fileName, size, modifiedAt, parts[4]);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Malformed local import candidate token", failure);
            }
        }
    }
}
