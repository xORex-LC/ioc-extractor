package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportManagedObjectId;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.port.out.dataframeimport.ImportSnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.ImportSnapshotWriter;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One protected local-filesystem snapshot store shared by every import transport. */
public final class LocalFilesystemImportSnapshotStore implements ImportSnapshotStore {

    static final String REFERENCE_PREFIX = "local-snapshot-v1:";
    private static final List<String> ACCEPTED_PREFIXES = List.of(
            REFERENCE_PREFIX, "smb-snapshot-v1:");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ);

    private final Path root;
    private final long maximumBytes;

    /** Creates and protects the process-private snapshot root. */
    public LocalFilesystemImportSnapshotStore(Path root, long maximumBytes) {
        Objects.requireNonNull(root, "root");
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("Maximum import snapshot bytes must be positive");
        }
        this.maximumBytes = maximumBytes;
        try {
            Path normalized = root.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            protectDirectory(normalized);
            this.root = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw storageFailure("Cannot prepare import snapshot root", failure);
        }
    }

    @Override
    public ImportSnapshot materialize(ImportDeliveryId deliveryId, ImportSnapshotWriter writer) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(writer, "writer");
        Path directory = directory(deliveryId);
        Path published = directory.resolve("snapshot.csv");
        if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
            return inspect(deliveryId, published);
        }
        Path part = directory.resolve("snapshot.part");
        boolean complete = false;
        try {
            Files.createDirectories(directory);
            protectDirectory(directory);
            Files.deleteIfExists(part);
            writer.write(part);
            Evidence written = inspectFile(part);
            force(part);
            try {
                Files.move(part, published, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException collision) {
                Files.deleteIfExists(part);
            }
            protectFile(published);
            force(directory);
            ImportSnapshot snapshot = inspect(deliveryId, published);
            if (snapshot.size() != written.size()
                    || !snapshot.digest().value().equals(written.sha256())) {
                throw new IocExtractorException(
                        "Published import snapshot does not match materialized bytes");
            }
            complete = true;
            return snapshot;
        } catch (IOException failure) {
            throw storageFailure("Cannot durably materialize import snapshot", failure);
        } finally {
            if (!complete) {
                deletePart(part);
            }
        }
    }

    @Override
    public Path resolve(ImportSnapshotReference reference) {
        Objects.requireNonNull(reference, "reference");
        String prefix = ACCEPTED_PREFIXES.stream()
                .filter(value -> reference.value().startsWith(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported import snapshot reference"));
        String token = reference.value().substring(prefix.length());
        if (!token.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Malformed import snapshot reference");
        }
        Path resolved = root.resolve(token).resolve("snapshot.csv").normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Import snapshot escapes its private root");
        }
        return resolved;
    }

    @Override
    public void purge(ImportDeliveryId deliveryId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Path directory = directory(deliveryId);
        try {
            Files.deleteIfExists(directory.resolve("snapshot.part"));
            Files.deleteIfExists(directory.resolve("snapshot.csv"));
            Files.deleteIfExists(directory);
        } catch (IOException failure) {
            throw storageFailure("Cannot purge import snapshot", failure);
        }
    }

    private ImportSnapshot inspect(ImportDeliveryId deliveryId, Path published) {
        Evidence evidence = inspectFile(published);
        return new ImportSnapshot(
                new ImportSnapshotReference(
                        REFERENCE_PREFIX + ImportManagedObjectId.from(deliveryId).value()),
                new ImportSha256(evidence.sha256()), evidence.size());
    }

    private Evidence inspectFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IocExtractorException("Import snapshot is not a protected regular file");
        }
        MessageDigest digest = digest();
        long size = 0;
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                size += read;
                if (size > maximumBytes) {
                    throw new IocExtractorException(
                            "Import snapshot exceeds configured byte limit");
                }
                digest.update(buffer, 0, read);
            }
            return new Evidence(HexFormat.of().formatHex(digest.digest()), size);
        } catch (IOException failure) {
            throw storageFailure("Cannot verify import snapshot", failure);
        }
    }

    private Path directory(ImportDeliveryId deliveryId) {
        return root.resolve(ImportManagedObjectId.from(deliveryId).value());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void protectDirectory(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Platform ACLs remain authoritative on non-POSIX filesystems.
        }
    }

    private static void protectFile(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Platform ACLs remain authoritative on non-POSIX filesystems.
        }
    }

    private static void deletePart(Path part) {
        try {
            Files.deleteIfExists(part);
        } catch (IOException ignored) {
            // A later materialization attempt retries exact partial-file cleanup.
        }
    }

    private static IocExtractorException storageFailure(String message, Exception failure) {
        return new IocExtractorException(message, failure);
    }

    private record Evidence(String sha256, long size) {
    }
}
