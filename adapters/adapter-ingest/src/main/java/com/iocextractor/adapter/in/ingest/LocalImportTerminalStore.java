package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.ImportReplaySnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.ImportReportStore;
import com.iocextractor.application.port.out.dataframeimport.ImportTerminalRetentionStore;
import com.iocextractor.application.port.out.dataframeimport.ImportSnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/** Atomic protected local source/report unit, replay materializer and retention adapter. */
public final class LocalImportTerminalStore
        implements ImportReportStore, ImportReplaySnapshotStore, ImportTerminalRetentionStore {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ);

    private final Path terminalRoot;
    private final Path quarantineRoot;
    private final Path snapshotRoot;
    private final Function<ImportSnapshotReference, Path> snapshots;
    private final ImportSnapshotStore snapshotStore;
    private final long maximumSnapshotBytes;

    /** Creates protected roots and a resolver for adapter-issued immutable snapshots. */
    public LocalImportTerminalStore(Path terminalRoot,
                                    Path quarantineRoot,
                                    Path snapshotRoot,
                                    Function<ImportSnapshotReference, Path> snapshots,
                                    long maximumSnapshotBytes) {
        this(terminalRoot, quarantineRoot, snapshotRoot, snapshots, maximumSnapshotBytes,
                new LocalFilesystemImportSnapshotStore(snapshotRoot, maximumSnapshotBytes));
    }

    /** Creates terminal storage over the composition-root shared snapshot store. */
    public LocalImportTerminalStore(Path terminalRoot,
                                    Path quarantineRoot,
                                    Path snapshotRoot,
                                    Function<ImportSnapshotReference, Path> snapshots,
                                    long maximumSnapshotBytes,
                                    ImportSnapshotStore snapshotStore) {
        this.terminalRoot = prepare(terminalRoot);
        this.quarantineRoot = prepare(quarantineRoot);
        this.snapshotRoot = prepare(snapshotRoot);
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        if (maximumSnapshotBytes < 1) {
            throw new IllegalArgumentException("Maximum replay snapshot bytes must be positive");
        }
        this.maximumSnapshotBytes = maximumSnapshotBytes;
    }

    @Override
    public void publish(PublishImportReportCommand command) {
        Objects.requireNonNull(command, "command");
        Path root = root(command.outcome());
        String token = LocalManagedImportSourceLifecycle.deliveryToken(command.deliveryId());
        Path target = root.resolve(token);
        if (terminalUnit(target)) {
            return;
        }
        Path pending = root.resolve("." + token + ".part");
        try {
            deleteUnit(pending);
            Files.createDirectory(pending);
            protectDirectory(pending);
            Path source = Objects.requireNonNull(
                    snapshots.apply(command.snapshotReference()), "snapshot path");
            Path terminalSource = pending.resolve("source.csv");
            copy(source, terminalSource);
            Path report = pending.resolve("report.json");
            Files.writeString(report, reportJson(command), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            protectFile(report);
            force(terminalSource);
            force(report);
            force(pending);
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException collision) {
                deleteUnit(pending);
            }
            force(root);
            if (!terminalUnit(target)) {
                throw new IocExtractorException("Protected import terminal unit is incomplete");
            }
        } catch (IocExtractorException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot atomically publish import terminal unit", failure);
        }
    }

    @Override
    public ImportSnapshot materializeReplay(ImportDeliveryId terminalDeliveryId,
                                             ImportDeliveryId replayDeliveryId) {
        Objects.requireNonNull(terminalDeliveryId, "terminalDeliveryId");
        Objects.requireNonNull(replayDeliveryId, "replayDeliveryId");
        Path source = retainedUnit(terminalDeliveryId).resolve("source.csv");
        return snapshotStore.materialize(replayDeliveryId,
                target -> LocalManagedImportSourceLifecycle.copyBounded(
                        source, target, maximumSnapshotBytes));
    }

    @Override
    public void delete(ImportDeliveryId deliveryId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        String token = LocalManagedImportSourceLifecycle.deliveryToken(deliveryId);
        try {
            deleteUnit(terminalRoot.resolve(token));
            deleteUnit(quarantineRoot.resolve(token));
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot purge protected import terminal unit", failure);
        }
    }

    @Override
    public void archive(ImportDeliveryId deliveryId, Path archiveDirectory) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Path archiveRoot = prepareArchiveRoot(archiveDirectory);
        String token = LocalManagedImportSourceLifecycle.deliveryToken(deliveryId);
        Path success = terminalRoot.resolve(token);
        Path rejected = quarantineRoot.resolve(token);
        Path archivedSuccess = archiveRoot.resolve("terminal").resolve(token);
        Path archivedRejected = archiveRoot.resolve("quarantine").resolve(token);
        boolean sourceSuccess = terminalUnit(success);
        boolean sourceRejected = terminalUnit(rejected);
        boolean targetSuccess = terminalUnit(archivedSuccess);
        boolean targetRejected = terminalUnit(archivedRejected);
        if (!sourceSuccess && !sourceRejected && (targetSuccess ^ targetRejected)) {
            return;
        }
        if ((sourceSuccess ? 1 : 0) + (sourceRejected ? 1 : 0)
                + (targetSuccess ? 1 : 0) + (targetRejected ? 1 : 0) != 1) {
            throw new IocExtractorException("Import terminal archive source is missing or ambiguous");
        }
        Path source = sourceSuccess ? success : rejected;
        Path destination = sourceSuccess ? archivedSuccess : archivedRejected;
        Path destinationRoot = Objects.requireNonNull(destination.getParent(), "archive outcome root");
        try {
            Files.createDirectories(destinationRoot);
            protectDirectory(destinationRoot);
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            force(destinationRoot);
            force(sourceSuccess ? terminalRoot : quarantineRoot);
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot atomically archive protected import terminal unit", failure);
        }
    }

    private Path retainedUnit(ImportDeliveryId deliveryId) {
        String token = LocalManagedImportSourceLifecycle.deliveryToken(deliveryId);
        Path success = terminalRoot.resolve(token);
        Path rejected = quarantineRoot.resolve(token);
        boolean hasSuccess = terminalUnit(success);
        boolean hasRejected = terminalUnit(rejected);
        if (hasSuccess == hasRejected) {
            throw new IllegalArgumentException("Retained import terminal unit is missing or ambiguous");
        }
        return hasSuccess ? success : rejected;
    }

    private boolean terminalUnit(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(path.resolve("source.csv"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(path.resolve("report.json"), LinkOption.NOFOLLOW_LINKS);
    }

    private Path root(ImportTerminalOutcome outcome) {
        return outcome == ImportTerminalOutcome.REJECTED ? quarantineRoot : terminalRoot;
    }

    private void copy(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IocExtractorException("Import snapshot is not a regular protected file");
        }
        Files.copy(source, target);
        protectFile(target);
    }

    private String reportJson(PublishImportReportCommand command) {
        StringBuilder json = new StringBuilder(512);
        json.append("{\n  \"schemaVersion\":1")
                .append(",\n  \"deliveryId\":\"").append(escape(command.deliveryId().value())).append('"')
                .append(",\n  \"sourceId\":\"").append(escape(command.sourceId().value())).append('"')
                .append(",\n  \"outcome\":\"").append(command.outcome().name()).append('"')
                .append(",\n  \"acceptedRows\":").append(command.acceptedRows())
                .append(",\n  \"rejectedRows\":").append(command.rejectedRows())
                .append(",\n  \"publicMutations\":").append(command.publicMutations());
        command.contract().ifPresent(contract -> appendContract(json, contract));
        appendStrings(json, "affectedArtifacts", command.affectedArtifacts().stream().sorted().toList());
        appendStrings(json, "deliveryCodes", command.deliveryCodes());
        json.append(",\n  \"issues\":[");
        for (int index = 0; index < command.issues().size(); index++) {
            var issue = command.issues().get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"row\":").append(issue.sourceRowNumber())
                    .append(",\"artifact\":");
            appendNullable(json, issue.artifact());
            json.append(",\"code\":\"").append(escape(issue.code())).append("\"}");
        }
        return json.append("]\n}\n").toString();
    }

    private void appendContract(StringBuilder json, ImportContractPin contract) {
        json.append(",\n  \"contractId\":\"").append(escape(contract.id().value())).append('"')
                .append(",\n  \"contractVersion\":").append(contract.version())
                .append(",\n  \"contractFingerprint\":\"")
                .append(contract.fingerprint().value()).append('"');
    }

    private void appendStrings(StringBuilder json, String name, java.util.List<String> values) {
        json.append(",\n  \"").append(name).append("\":[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(escape(values.get(index))).append('"');
        }
        json.append(']');
    }

    private void appendNullable(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(escape(value)).append('"');
        }
    }

    private String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u%04x".formatted((int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private Path prepare(Path root) {
        Objects.requireNonNull(root, "root");
        try {
            Path normalized = root.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            protectDirectory(normalized);
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new IocExtractorException("Cannot prepare protected import terminal root", failure);
        }
    }

    private Path prepareArchiveRoot(Path directory) {
        Path archiveRoot = prepare(Objects.requireNonNull(directory, "archiveDirectory"));
        if (archiveRoot.startsWith(terminalRoot) || terminalRoot.startsWith(archiveRoot)
                || archiveRoot.startsWith(quarantineRoot) || quarantineRoot.startsWith(archiveRoot)
                || archiveRoot.startsWith(snapshotRoot) || snapshotRoot.startsWith(archiveRoot)) {
            throw new IllegalArgumentException("Import archive directory must be disjoint from managed roots");
        }
        return archiveRoot;
    }

    private void deleteUnit(Path unit) throws IOException {
        if (!Files.exists(unit, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(unit)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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
}
