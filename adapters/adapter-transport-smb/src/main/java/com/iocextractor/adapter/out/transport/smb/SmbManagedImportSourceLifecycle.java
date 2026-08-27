package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.dataframeimport.DataframeImportConsistencyException;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportManagedObjectId;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadiness;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadinessPhase;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceResult;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.application.port.out.dataframeimport.ImportSnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.ImportTerminalSourceRetention;
import com.iocextractor.application.port.out.dataframeimport.PurgeImportTerminalSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportSourceCapability;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** SMB ownership adapter: server rename first, then durable private local materialization. */
public final class SmbManagedImportSourceLifecycle
        implements ManagedImportSourceLifecycle, ImportTerminalSourceRetention, ImportSourceCapability {

    private static final String PRIVATE_NAMESPACE = ".ioc-managed-import";
    private final Map<ImportSourceId, SmbImportSourceDefinition> sources;
    private final SmbSessionPool sessions;
    private final ImportSnapshotStore snapshots;
    private final Duration quietPeriod;
    private final long maximumSnapshotBytes;
    private final Map<CandidateIdentity, StabilitySample> samples = new HashMap<>();
    private final Map<ImportSourceId, Object> sourceLocks;

    /** Creates an SMB source lifecycle over the application-shared session pool. */
    public SmbManagedImportSourceLifecycle(
            List<SmbImportSourceDefinition> definitions,
            SmbSessionPool sessions,
            ImportSnapshotStore snapshots,
            Duration quietPeriod,
            long maximumSnapshotBytes) {
        Objects.requireNonNull(definitions, "definitions");
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("At least one SMB import source is required");
        }
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
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

    @Override
    public ImportSourceReadiness probe(ImportSourceId sourceId) {
        SmbImportSourceDefinition source = source(sourceId);
        synchronized (sourceLocks.get(sourceId)) {
            try {
                return sessions.withClient(source.endpoint(), "import-capability",
                        client -> probe(sourceId, source, client));
            } catch (RemoteTransportException failure) {
                boolean retry = failure.kind() == RemoteErrorKind.TRANSIENT
                        || failure.kind() == RemoteErrorKind.UNREACHABLE;
                return ImportSourceReadiness.capabilityFailed(sourceId,
                        ImportSourceReadinessPhase.PRIVATE_OBJECT_FLOW, retry);
            } catch (RuntimeException failure) {
                return ImportSourceReadiness.capabilityFailed(sourceId,
                        ImportSourceReadinessPhase.PRIVATE_OBJECT_FLOW, false);
            }
        }
    }

    private ImportSourceReadiness probe(
            ImportSourceId sourceId,
            SmbImportSourceDefinition source,
            SmbShareClient client) {
        for (String phase : List.of("processing", "terminal", "quarantine", "probe")) {
            if (!client.directoryExists(managed(source, phase))) {
                return ImportSourceReadiness.namespaceIncompatible(sourceId);
            }
        }
        String token = ImportManagedObjectId.from(new ImportDeliveryId(
                "capability:" + sourceId.value() + ":" + java.util.UUID.randomUUID())).value();
        String probe = SmbFileTransport.join(managed(source, "probe"), token + ".probe");
        String processing = SmbFileTransport.join(managed(source, "processing"), token + ".probe");
        String terminal = SmbFileTransport.join(managed(source, "terminal"), token + ".probe");
        try {
            client.createEmptyFile(probe);
            client.rename(probe, processing);
            client.rename(processing, terminal);
            client.deleteRegularFile(terminal);
            return ImportSourceReadiness.ready(sourceId);
        } finally {
            bestEffortDelete(client, terminal);
            bestEffortDelete(client, processing);
            bestEffortDelete(client, probe);
        }
    }

    private void bestEffortDelete(SmbShareClient client, String path) {
        try {
            client.deleteRegularFile(path);
        } catch (RuntimeException ignored) {
            // The failed probe remains closed; a later probe retries cleanup using its own token.
        }
    }

    private List<ImportSourceCandidate> detect(
            ImportSourceId sourceId,
            SmbImportSourceDefinition source,
            Instant observedAt) {
        List<SmbRemoteEntry> entries = sessions.withClient(source.endpoint(), "import-list",
                client -> client.list(source.inbox()));
        List<SmbRemoteEntry> files = entries.stream()
                .filter(SmbRemoteEntry::regularFile)
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
        String producerPath = SmbFileTransport.join(source.inbox(), candidate.leaf());
        String processingRoot = managed(source, "processing");
        String claimedPath = SmbFileTransport.join(processingRoot, deliveryToken(command.deliveryId()) + ".csv");
        sessions.withClient(source.endpoint(), "import-claim", client -> {
            boolean producerExists = client.fileExists(producerPath);
            boolean claimedExists = client.fileExists(claimedPath);
            if (producerExists && claimedExists) {
                throw new IocExtractorException("SMB import ownership destination collision");
            }
            if (!claimedExists) {
                SmbRemoteEntry current = client.stat(producerPath)
                        .filter(SmbRemoteEntry::regularFile)
                        .orElseThrow(() -> new IocExtractorException(
                                "SMB import candidate disappeared before claim"));
                if (!candidate.matches(current)) {
                    throw new IocExtractorException("SMB import candidate changed before claim");
                }
                client.rename(producerPath, claimedPath);
            }
            SmbRemoteEntry claimed = client.stat(claimedPath)
                    .filter(SmbRemoteEntry::regularFile)
                    .orElseThrow(() -> new IocExtractorException(
                            "SMB import claimed object is missing"));
            if (!candidate.matchesContent(claimed)) {
                throw new IocExtractorException(
                        "SMB import claimed object does not match reserved candidate");
            }
            return null;
        });
        return new ClaimImportSourceResult(
                snapshots.materialize(command.deliveryId(),
                        target -> materialize(source, claimedPath, candidate, target)));
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
            var claimedEntry = client.stat(claimed);
            var destinationEntry = client.stat(destination);
            if (claimedEntry.filter(entry -> !entry.regularFile()).isPresent()
                    || destinationEntry.filter(entry -> !entry.regularFile()).isPresent()) {
                throw new IocExtractorException(
                        "SMB import disposition object is not a regular file");
            }
            boolean claimedExists = claimedEntry.isPresent();
            boolean destinationExists = destinationEntry.isPresent();
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

    /** Deletes exactly the expected regular terminal object and treats absence as success. */
    @Override
    public void purge(PurgeImportTerminalSourceCommand command) {
        Objects.requireNonNull(command, "command");
        SmbImportSourceDefinition source = source(command.sourceId());
        String token = command.managedObjectId().value() + ".csv";
        String expected = SmbFileTransport.join(managed(source,
                command.expectedOutcome() == ImportTerminalOutcome.REJECTED
                        ? "quarantine" : "terminal"), token);
        String processing = SmbFileTransport.join(managed(source, "processing"), token);
        String other = SmbFileTransport.join(managed(source,
                command.expectedOutcome() == ImportTerminalOutcome.REJECTED
                        ? "terminal" : "quarantine"), token);
        sessions.withClient(source.endpoint(), "import-retention", client -> {
            if (client.stat(processing).isPresent() || client.stat(other).isPresent()) {
                throw new DataframeImportConsistencyException(
                        "SMB import terminal source evidence is contradictory");
            }
            var entry = client.stat(expected);
            if (entry.isEmpty()) {
                return null;
            }
            if (!entry.orElseThrow().regularFile()) {
                throw new DataframeImportConsistencyException(
                        "SMB import terminal source object is not a regular file");
            }
            client.deleteRegularFile(expected);
            return null;
        });
    }

    /** Resolves only immutable local snapshots issued by this SMB adapter. */
    public Path resolveSnapshot(ImportSnapshotReference reference) {
        return snapshots.resolve(reference);
    }

    private void materialize(
            SmbImportSourceDefinition source,
            String claimedPath,
            CandidateEvidence candidate,
            Path part) {
            SmbRemoteEntry remote = sessions.withClient(source.endpoint(), "import-stat-claimed",
                    client -> client.stat(claimedPath)
                            .filter(SmbRemoteEntry::regularFile)
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
            long downloadedSize;
            try {
                downloadedSize = Files.size(part);
            } catch (IOException failure) {
                throw new IocExtractorException("Cannot inspect SMB import materialization", failure);
            }
            if (downloadedSize != remote.size()) {
                throw new IocExtractorException("SMB import materialization size mismatch");
            }
            SmbRemoteEntry after = sessions.withClient(source.endpoint(), "import-stat-after-download",
                    client -> client.stat(claimedPath)
                            .filter(SmbRemoteEntry::regularFile)
                            .orElseThrow(() -> new IocExtractorException(
                                    "SMB import claimed object disappeared during materialization")));
            if (!sameRemoteEvidence(remote, after)) {
                throw new IocExtractorException(
                        "SMB import claimed object changed during materialization");
            }
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
        return ImportManagedObjectId.from(deliveryId).value();
    }

    private record CandidateIdentity(ImportSourceId sourceId, String leaf) {
    }

    private record StabilitySample(CandidateEvidence evidence, Instant unchangedSince) {
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
            return entry.regularFile() && size == entry.size()
                    && modifiedAt.equals(entry.modifiedAt())
                    && fileId == entry.fileId()
                    && leaf.equals(entry.path().substring(entry.path().lastIndexOf('/') + 1));
        }

        private boolean matchesContent(SmbRemoteEntry entry) {
            return entry.regularFile() && size == entry.size()
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
