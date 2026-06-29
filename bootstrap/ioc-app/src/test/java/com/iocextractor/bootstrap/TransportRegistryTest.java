package com.iocextractor.bootstrap;

import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.PublishReceipt;
import com.iocextractor.application.sync.RemoteObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void dispatchesByLogicalEndpointWithoutChangingPortArguments() {
        FakeTransport first = new FakeTransport();
        FakeTransport second = new FakeTransport();
        try (TransportRegistry registry = new TransportRegistry(List.of(
                binding("one", first), binding("two", second)))) {
            registry.list("two", "/incoming");
        }

        assertThat(first.calls).isEmpty();
        assertThat(second.calls).containsExactly("list:two:/incoming");
    }

    @Test
    void lifecycleHooksRunOnceForAdapterSharedByMultipleEndpoints() {
        FakeTransport shared = new FakeTransport();
        TransportRegistry registry = new TransportRegistry(List.of(
                binding("one", shared), binding("two", shared)));

        registry.closeIdle();
        registry.close();

        assertThat(shared.idleCalls).isOne();
        assertThat(shared.closeCalls).isOne();
    }

    @Test
    void unknownEndpointFailsBeforeAdapterResolution() {
        try (TransportRegistry registry = new TransportRegistry(List.of(binding("known", new FakeTransport())))) {
            assertThatThrownBy(() -> registry.get("unknown", "/a", tempDir.resolve("a")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown sync endpoint");
        }
    }

    private TransportRegistry.Binding binding(String endpoint, FakeTransport transport) {
        return new TransportRegistry.Binding(endpoint, transport, transport::closeIdle, transport);
    }

    private static final class FakeTransport implements FileTransport, AutoCloseable {
        private final List<String> calls = new ArrayList<>();
        private int idleCalls;
        private int closeCalls;

        @Override
        public List<RemoteObject> list(String endpoint, String remotePath) {
            calls.add("list:" + endpoint + ":" + remotePath);
            return List.of(new RemoteObject(remotePath + "/a", 1, Instant.EPOCH));
        }

        @Override
        public Optional<RemoteObject> stat(String endpoint, String remotePath) {
            return Optional.empty();
        }

        @Override
        public void get(String endpoint, String remotePath, Path localDestination) {
            calls.add("get:" + endpoint + ":" + remotePath);
        }

        @Override
        public void delete(String endpoint, String remotePath) {
            calls.add("delete:" + endpoint + ":" + remotePath);
        }

        @Override
        public PublishReceipt publishAtomically(PublishAtomicallyRequest request) {
            calls.add("publish:" + request.endpoint() + ":" + request.remotePath());
            return new PublishReceipt(request.remotePath(), "ok");
        }

        private void closeIdle() {
            idleCalls++;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
