package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmbFileTransportTest {

    @TempDir
    Path tempDir;

    @Test
    void publishAtomicallyExposesMarkerOnlyWithCompleteFinalData() throws IOException {
        Path localSlice = localSlice("manifest-sha-1");
        FakeFactory factory = new FakeFactory();
        SmbFileTransport transport = transport(factory);

        var receipt = transport.publishAtomically(new PublishAtomicallyRequest(
                "primary",
                "export/profile/slice-1",
                localSlice,
                "_SUCCESS"));

        assertThat(receipt.remotePath()).isEqualTo("export/profile/slice-1");
        FakeSmbShareClient client = factory.openedClients.getFirst();
        assertThat(client.fileExists("export/profile/slice-1/_SUCCESS")).isTrue();
        assertThat(client.readText("export/profile/slice-1/_SUCCESS")).isEqualTo("manifest-sha-1");
        assertThat(client.readText("export/profile/slice-1/masks.csv")).isEqualTo("mask,row\n");
        assertThat(client.readText("export/profile/slice-1/hashes.csv")).isEqualTo("hash,row\n");
        assertThat(client.directories).doesNotContain("export/profile/slice-1.tmp-orphan");
        int firstDataUpload = indexOfPrefix(client.operations, "upload:export/profile/slice-1.tmp-");
        int markerUpload = indexOfPrefix(client.operations, "upload-marker:export/profile/slice-1.tmp-");
        int rename = indexOfPrefix(client.operations, "rename:export/profile/slice-1.tmp-");
        assertThat(firstDataUpload).isGreaterThan(indexOf(client.operations, "mkdir:export/profile"));
        assertThat(markerUpload).isGreaterThan(firstDataUpload);
        assertThat(rename).isGreaterThan(markerUpload);
    }

    @Test
    void failureBeforeMarkerLeavesNoCommittedSlice() throws IOException {
        Path localSlice = localSlice("manifest-sha-1");
        FakeFactory factory = new FakeFactory();
        factory.failOnUploadName = "hashes.csv";
        SmbFileTransport transport = transport(factory);

        assertThatThrownBy(() -> transport.publishAtomically(new PublishAtomicallyRequest(
                "primary",
                "export/profile/slice-1",
                localSlice,
                "_SUCCESS")))
                .isInstanceOf(RemoteTransportException.class)
                .hasMessageContaining("simulated upload failure");

        FakeSmbShareClient client = factory.openedClients.getFirst();
        assertThat(client.directoryExists("export/profile/slice-1")).isFalse();
        assertThat(client.fileExists("export/profile/slice-1/_SUCCESS")).isFalse();
        assertThat(client.directories).noneMatch(path -> path.contains(".tmp"));
    }

    @Test
    void matchingRemoteMarkerIsIdempotentSuccessWithoutUpload() throws IOException {
        Path localSlice = localSlice("manifest-sha-1");
        FakeFactory factory = new FakeFactory();
        FakeSmbShareClient initial = factory.precreated;
        initial.createDirectories("export/profile/slice-1");
        initial.putText("export/profile/slice-1/_SUCCESS", "manifest-sha-1");
        SmbFileTransport transport = transport(factory);

        var receipt = transport.publishAtomically(new PublishAtomicallyRequest(
                "primary",
                "export/profile/slice-1",
                localSlice,
                "_SUCCESS"));

        assertThat(receipt.verification()).contains("already committed");
        assertThat(initial.operations).doesNotContain("upload:export/profile/slice-1.tmp");
    }

    @Test
    void mismatchingRemoteMarkerFailsWithoutSuccess() throws IOException {
        Path localSlice = localSlice("manifest-sha-1");
        FakeFactory factory = new FakeFactory();
        factory.precreated.createDirectories("export/profile/slice-1");
        factory.precreated.putText("export/profile/slice-1/_SUCCESS", "other-sha");
        SmbFileTransport transport = transport(factory);

        assertThatThrownBy(() -> transport.publishAtomically(new PublishAtomicallyRequest(
                "primary",
                "export/profile/slice-1",
                localSlice,
                "_SUCCESS")))
                .isInstanceOf(RemoteTransportException.class)
                .extracting("kind")
                .isEqualTo(RemoteErrorKind.TRANSIENT);
    }

    @Test
    void transientFailureClosesClientAndNextCallReconnects() {
        FakeFactory factory = new FakeFactory();
        factory.precreated.failList = true;
        SmbFileTransport transport = transport(factory);

        assertThatThrownBy(() -> transport.list("primary", "remote"))
                .isInstanceOf(RemoteTransportException.class)
                .extracting("kind")
                .isEqualTo(RemoteErrorKind.TRANSIENT);

        assertThat(factory.precreated.closed).isTrue();
        factory.precreated = new FakeSmbShareClient();
        assertThat(transport.list("primary", "remote")).isEmpty();
        assertThat(factory.openedClients).hasSize(2);
    }

    @Test
    void rejectsUnsafeRemotePaths() {
        SmbFileTransport transport = transport(new FakeFactory());

        assertThatThrownBy(() -> transport.stat("primary", "../slice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remotePath");
    }

    private Path localSlice(String marker) throws IOException {
        Path slice = Files.createDirectories(tempDir.resolve("slice"));
        Files.writeString(slice.resolve("masks.csv"), "mask,row\n", StandardCharsets.UTF_8);
        Files.writeString(slice.resolve("hashes.csv"), "hash,row\n", StandardCharsets.UTF_8);
        Files.writeString(slice.resolve("_SUCCESS"), marker, StandardCharsets.UTF_8);
        return slice;
    }

    private static SmbFileTransport transport(FakeFactory factory) {
        return new SmbFileTransport(
                List.of(new SmbEndpointSettings(
                        "primary",
                        "files.example.test",
                        "export",
                        "",
                        "sync-user",
                        "secret".toCharArray(),
                        false,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(5))),
                factory,
                Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC));
    }

    private static int indexOf(List<String> values, String expected) {
        int index = values.indexOf(expected);
        assertThat(index).as("index of %s in %s", expected, values).isNotNegative();
        return index;
    }

    private static int indexOfPrefix(List<String> values, String prefix) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).startsWith(prefix)) {
                return index;
            }
        }
        assertThat(values).as("operations with prefix " + prefix).anyMatch(value -> value.startsWith(prefix));
        return -1;
    }

    private static final class FakeFactory implements SmbShareClientFactory {

        private final List<FakeSmbShareClient> openedClients = new ArrayList<>();
        private FakeSmbShareClient precreated = new FakeSmbShareClient();
        private String failOnUploadName;

        @Override
        public SmbShareClient open(SmbEndpointSettings settings) {
            precreated.failOnUploadName = failOnUploadName;
            openedClients.add(precreated);
            return precreated;
        }
    }

    private static final class FakeSmbShareClient implements SmbShareClient {

        private final Map<String, byte[]> files = new HashMap<>();
        private final Set<String> directories = new HashSet<>();
        private final List<String> operations = new ArrayList<>();
        private String failOnUploadName;
        private boolean failList;
        private boolean closed;

        @Override
        public List<SmbRemoteEntry> list(String remotePath) {
            if (failList) {
                throw new RuntimeException("transport connection reset");
            }
            String prefix = remotePath + "/";
            return files.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .map(entry -> new SmbRemoteEntry(entry.getKey(), entry.getValue().length, Instant.EPOCH, false))
                    .toList();
        }

        @Override
        public Optional<SmbRemoteEntry> stat(String remotePath) {
            byte[] content = files.get(remotePath);
            if (content == null) {
                return Optional.empty();
            }
            return Optional.of(new SmbRemoteEntry(remotePath, content.length, Instant.EPOCH, false));
        }

        @Override
        public void download(String remotePath, Path localDestination) {
            try {
                Files.write(localDestination, files.get(remotePath));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void delete(String remotePath) {
            files.remove(remotePath);
            directories.remove(remotePath);
        }

        @Override
        public boolean fileExists(String remotePath) {
            return files.containsKey(remotePath);
        }

        @Override
        public boolean directoryExists(String remotePath) {
            return directories.contains(remotePath);
        }

        @Override
        public String readText(String remotePath) {
            return new String(files.get(remotePath), StandardCharsets.UTF_8);
        }

        @Override
        public void createDirectories(String remotePath) {
            String[] parts = remotePath.split("/");
            StringBuilder current = new StringBuilder();
            for (String part : parts) {
                if (!current.isEmpty()) {
                    current.append('/');
                }
                current.append(part);
                directories.add(current.toString());
                operations.add("mkdir:" + current);
            }
        }

        @Override
        public void upload(Path localFile, String remotePath) {
            if (remotePath.endsWith("/" + failOnUploadName)) {
                throw new RuntimeException("simulated upload failure: " + failOnUploadName);
            }
            try {
                files.put(remotePath, Files.readAllBytes(localFile));
                operations.add(remotePath.endsWith("/_SUCCESS")
                        ? "upload-marker:" + parent(remotePath)
                        : "upload:" + parent(remotePath));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void rename(String sourcePath, String targetPath) {
            List<String> movedFiles = files.keySet().stream()
                    .filter(path -> path.startsWith(sourcePath + "/"))
                    .toList();
            for (String source : movedFiles) {
                files.put(targetPath + source.substring(sourcePath.length()), files.remove(source));
            }
            List<String> movedDirectories = directories.stream()
                    .filter(path -> path.equals(sourcePath) || path.startsWith(sourcePath + "/"))
                    .toList();
            directories.removeAll(movedDirectories);
            for (String source : movedDirectories) {
                directories.add(targetPath + source.substring(sourcePath.length()));
            }
            operations.add("rename:" + sourcePath + "->" + targetPath);
        }

        @Override
        public void deleteTree(String remotePath) {
            files.keySet().removeIf(path -> path.equals(remotePath) || path.startsWith(remotePath + "/"));
            directories.removeIf(path -> path.equals(remotePath) || path.startsWith(remotePath + "/"));
            operations.add("delete-tree:" + remotePath);
        }

        @Override
        public void close() {
            closed = true;
        }

        private void putText(String remotePath, String value) {
            files.put(remotePath, value.getBytes(StandardCharsets.UTF_8));
        }

        private static String parent(String remotePath) {
            return remotePath.substring(0, remotePath.lastIndexOf('/'));
        }
    }
}
