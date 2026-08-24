package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.PublishReceipt;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteObject;
import com.iocextractor.application.sync.RemoteTransportException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SMBJ-backed {@link FileTransport} implementation.
 *
 * <p>The application sees only stateless file operations. SMB connections and shares are lazy,
 * cached per endpoint, closed on transient failure and owned entirely by this adapter.
 */
public final class SmbFileTransport implements FileTransport, AutoCloseable {

    private final SmbSessionPool sessions;
    private final boolean ownsSessions;

    public SmbFileTransport(List<SmbEndpointSettings> endpoints) {
        this(new SmbSessionPool(endpoints), true);
    }

    SmbFileTransport(List<SmbEndpointSettings> endpoints, SmbShareClientFactory clientFactory, Clock clock) {
        this(new SmbSessionPool(endpoints, clientFactory, clock), true);
    }

    /** Creates a stateless sync transport over the application-shared SMB pool. */
    public SmbFileTransport(SmbSessionPool sessions) {
        this(sessions, false);
    }

    private SmbFileTransport(SmbSessionPool sessions, boolean ownsSessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.ownsSessions = ownsSessions;
    }

    @Override
    public List<RemoteObject> list(String endpoint, String remotePath) {
        String normalizedPath = normalizeRemotePath(remotePath);
        return sessions.withClient(endpoint, "list", client -> client.list(normalizedPath).stream()
                .filter(entry -> !entry.directory())
                .map(entry -> new RemoteObject(entry.path(), entry.size(), entry.modifiedAt()))
                .toList());
    }

    @Override
    public Optional<RemoteObject> stat(String endpoint, String remotePath) {
        String normalizedPath = normalizeRemotePath(remotePath);
        return sessions.withClient(endpoint, "stat", client -> client.stat(normalizedPath)
                .filter(entry -> !entry.directory())
                .map(entry -> new RemoteObject(entry.path(), entry.size(), entry.modifiedAt())));
    }

    @Override
    public void get(String endpoint, String remotePath, Path localDestination) {
        Objects.requireNonNull(localDestination, "localDestination");
        String normalizedPath = normalizeRemotePath(remotePath);
        sessions.withClient(endpoint, "get", client -> {
            client.download(normalizedPath, localDestination);
            return null;
        });
    }

    @Override
    public void delete(String endpoint, String remotePath) {
        String normalizedPath = normalizeRemotePath(remotePath);
        sessions.withClient(endpoint, "delete", client -> {
            client.delete(normalizedPath);
            return null;
        });
    }

    @Override
    public PublishReceipt publishAtomically(PublishAtomicallyRequest request) {
        Objects.requireNonNull(request, "request");
        return sessions.withClient(request.endpoint(), "publish", client -> publish(client, request));
    }

    @Override
    public void close() {
        if (ownsSessions) {
            sessions.close();
        }
    }

    /** Closes cached clients that have been idle longer than their endpoint policy. */
    public void closeIdle() {
        sessions.closeIdle();
    }

    private PublishReceipt publish(SmbShareClient client, PublishAtomicallyRequest request) {
        String remotePath = normalizeRemotePath(request.remotePath());
        Path localDirectory = requireDirectory(request.localDirectory());
        Path localMarker = localDirectory.resolve(request.commitMarkerName());
        String localMarkerValue = readMarker(localMarker);
        String remoteMarker = join(remotePath, request.commitMarkerName());

        if (client.fileExists(remoteMarker)) {
            String remoteMarkerValue = client.readText(remoteMarker).strip();
            if (remoteMarkerValue.equals(localMarkerValue)) {
                return new PublishReceipt(remotePath, "remote marker already committed: " + localMarkerValue);
            }
            throw new RemoteTransportException(
                    RemoteErrorKind.TRANSIENT,
                    "remote commit marker mismatch at " + remoteMarker);
        }

        String temporaryPath = remotePath + ".tmp-" + UUID.randomUUID();
        List<UploadFile> files = uploadPlan(localDirectory, temporaryPath);
        UploadFile marker = files.stream()
                .filter(file -> file.leafName().equals(request.commitMarkerName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("commit marker is not a regular file: " + localMarker));
        cleanup(client, temporaryPath);
        try {
            client.createDirectories(temporaryPath);
            for (UploadFile file : files) {
                if (!file.leafName().equals(request.commitMarkerName())) {
                    client.upload(file.localPath(), file.remotePath());
                }
            }
            client.upload(marker.localPath(), marker.remotePath());
            verifyUploadedSizes(client, files);
            if (client.directoryExists(remotePath) && !client.fileExists(remoteMarker)) {
                client.deleteTree(remotePath);
            }
            client.rename(temporaryPath, remotePath);
            if (!client.fileExists(remoteMarker)) {
                throw new RemoteTransportException(RemoteErrorKind.TRANSIENT, "remote commit marker is absent after publish");
            }
            String committedMarker = client.readText(remoteMarker).strip();
            if (!committedMarker.equals(localMarkerValue)) {
                throw new RemoteTransportException(
                        RemoteErrorKind.TRANSIENT,
                        "remote commit marker mismatch after publish at " + remoteMarker);
            }
            return new PublishReceipt(remotePath, "committed marker " + localMarkerValue + ", files=" + files.size());
        } catch (RuntimeException failure) {
            cleanup(client, temporaryPath);
            throw failure;
        }
    }

    private static Path requireDirectory(Path localDirectory) {
        if (!Files.isDirectory(localDirectory)) {
            throw new IllegalArgumentException("localDirectory must be an existing directory: " + localDirectory);
        }
        return localDirectory;
    }

    private static String readMarker(Path marker) {
        try {
            return Files.readString(marker, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalArgumentException("commit marker is not readable: " + marker, e);
        }
    }

    private static List<UploadFile> uploadPlan(Path localDirectory, String temporaryPath) {
        try (var stream = Files.list(localDirectory)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> uploadFile(path, temporaryPath))
                    .sorted(Comparator.comparing(UploadFile::leafName))
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("localDirectory cannot be listed: " + localDirectory, e);
        }
    }

    private static UploadFile uploadFile(Path localPath, String temporaryPath) {
        Path fileName = localPath.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("local publish file has no leaf name: " + localPath);
        }
        String leafName = safeLeaf(fileName.toString());
        return new UploadFile(localPath, leafName, join(temporaryPath, leafName));
    }

    private static void verifyUploadedSizes(SmbShareClient client, List<UploadFile> files) {
        for (UploadFile file : files) {
            Optional<SmbRemoteEntry> uploaded = client.stat(file.remotePath());
            if (uploaded.isEmpty()) {
                throw new RemoteTransportException(
                        RemoteErrorKind.TRANSIENT, "uploaded file is absent: " + file.remotePath());
            }
            try {
                long localSize = Files.size(file.localPath());
                if (uploaded.get().size() != localSize) {
                    throw new RemoteTransportException(
                            RemoteErrorKind.TRANSIENT, "uploaded file size mismatch: " + file.remotePath());
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("local file size cannot be read: " + file.localPath(), e);
            }
        }
    }

    private static void cleanup(SmbShareClient client, String remotePath) {
        try {
            if (client.directoryExists(remotePath)) {
                client.deleteTree(remotePath);
            }
        } catch (RuntimeException ignored) {
            // Cleanup is best-effort; the original publish failure remains authoritative.
        }
    }

    static String normalizeRemotePath(String remotePath) {
        if (remotePath == null || remotePath.isBlank()) {
            throw new IllegalArgumentException("remotePath must not be blank");
        }
        String normalized = remotePath.replace('\\', '/');
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        normalized = trimSlashes(normalized);
        if (normalized.isBlank() || normalized.equals(".") || normalized.contains("/../") || normalized.startsWith("../") || normalized.endsWith("/..")) {
            throw new IllegalArgumentException("remotePath must stay inside SMB share: " + remotePath);
        }
        return normalized;
    }

    static String join(String parent, String child) {
        String safeChild = safeLeaf(child);
        String normalizedParent = normalizeRemotePath(parent);
        return normalizedParent + "/" + safeChild;
    }

    private static String safeLeaf(String value) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("path segment must be safe: " + value);
        }
        return value;
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    private record UploadFile(Path localPath, String leafName, String remotePath) {
    }
}
