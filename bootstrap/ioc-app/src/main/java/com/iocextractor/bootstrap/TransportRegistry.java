package com.iocextractor.bootstrap;

import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.PublishReceipt;
import com.iocextractor.application.sync.RemoteObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bootstrap-owned endpoint dispatcher for transport-neutral sync use cases.
 *
 * <p>Each logical endpoint resolves to one adapter instance. The registry keeps connection
 * lifecycle and idle-maintenance hooks outside application ports while exposing the same
 * stateless {@link FileTransport} contract to fetch and publish services.
 */
public final class TransportRegistry implements FileTransport, AutoCloseable {

    private final Map<String, Binding> bindings;

    /** Creates an immutable registry and rejects duplicate logical endpoint names. */
    public TransportRegistry(List<Binding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        Map<String, Binding> indexed = new LinkedHashMap<>();
        for (Binding binding : bindings) {
            Binding previous = indexed.putIfAbsent(binding.endpoint(), binding);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate transport endpoint: " + binding.endpoint());
            }
        }
        this.bindings = Map.copyOf(indexed);
    }

    /** Returns whether a logical endpoint is registered without opening a remote connection. */
    public boolean contains(String endpoint) {
        return bindings.containsKey(endpoint);
    }

    @Override
    public List<RemoteObject> list(String endpoint, String remotePath) {
        return resolve(endpoint).transport().list(endpoint, remotePath);
    }

    @Override
    public Optional<RemoteObject> stat(String endpoint, String remotePath) {
        return resolve(endpoint).transport().stat(endpoint, remotePath);
    }

    @Override
    public void get(String endpoint, String remotePath, Path localDestination) {
        resolve(endpoint).transport().get(endpoint, remotePath, localDestination);
    }

    @Override
    public void delete(String endpoint, String remotePath) {
        resolve(endpoint).transport().delete(endpoint, remotePath);
    }

    @Override
    public PublishReceipt publishAtomically(PublishAtomicallyRequest request) {
        Objects.requireNonNull(request, "request");
        return resolve(request.endpoint()).transport().publishAtomically(request);
    }

    /** Runs each adapter's idle-session maintenance hook exactly once. */
    public void closeIdle() {
        uniqueBindings().forEach(binding -> binding.idleMaintenance().run());
    }

    /** Closes every distinct adapter lifecycle exactly once. */
    @Override
    public void close() {
        RuntimeException failure = null;
        for (Binding binding : uniqueBindings()) {
            try {
                binding.lifecycle().close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = new IllegalStateException("Failed to close sync transport", closeFailure);
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Binding resolve(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        Binding binding = bindings.get(endpoint);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown sync endpoint: " + endpoint);
        }
        return binding;
    }

    private List<Binding> uniqueBindings() {
        Set<FileTransport> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Binding> unique = new ArrayList<>();
        for (Binding binding : bindings.values()) {
            if (seen.add(binding.transport())) {
                unique.add(binding);
            }
        }
        return unique;
    }

    /** One logical endpoint and the adapter-specific lifecycle hooks hidden behind it. */
    public record Binding(String endpoint,
                          FileTransport transport,
                          Runnable idleMaintenance,
                          AutoCloseable lifecycle) {

        public Binding {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("endpoint must not be blank");
            }
            transport = Objects.requireNonNull(transport, "transport");
            idleMaintenance = Objects.requireNonNull(idleMaintenance, "idleMaintenance");
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        }
    }
}
