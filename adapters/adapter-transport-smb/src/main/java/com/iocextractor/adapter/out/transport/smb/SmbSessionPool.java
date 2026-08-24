package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Shared endpoint-keyed SMB session pool for sync and managed-import operations. */
public final class SmbSessionPool implements AutoCloseable {

    private final Map<String, SmbEndpointSettings> endpoints;
    private final SmbShareClientFactory clientFactory;
    private final Clock clock;
    private final Map<String, CachedClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Object> endpointLocks = new HashMap<>();

    /** Creates the production pool without opening network connections eagerly. */
    public SmbSessionPool(List<SmbEndpointSettings> endpoints) {
        this(endpoints, new SmbjShareClientFactory(), Clock.systemUTC());
    }

    SmbSessionPool(List<SmbEndpointSettings> endpoints,
                   SmbShareClientFactory clientFactory,
                   Clock clock) {
        Objects.requireNonNull(endpoints, "endpoints");
        Map<String, SmbEndpointSettings> indexed = new HashMap<>();
        for (SmbEndpointSettings endpoint : endpoints) {
            SmbEndpointSettings previous = indexed.putIfAbsent(endpoint.name(), endpoint);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate SMB endpoint: " + endpoint.name());
            }
            endpointLocks.put(endpoint.name(), new Object());
        }
        this.endpoints = Map.copyOf(indexed);
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    <T> T withClient(String endpoint, String operation, Function<SmbShareClient, T> action) {
        requireEndpointName(endpoint);
        endpoint(endpoint);
        synchronized (endpointLocks.get(endpoint)) {
            try {
                SmbShareClient client = client(endpoint);
                T result = action.apply(client);
                touch(endpoint);
                return result;
            } catch (RuntimeException failure) {
                RemoteTransportException mapped = SmbExceptionMapper.map(failure, operation, endpoint);
                if (mapped.kind() == RemoteErrorKind.TRANSIENT
                        || mapped.kind() == RemoteErrorKind.UNREACHABLE) {
                    closeClient(endpoint);
                }
                throw mapped;
            }
        }
    }

    /** Closes cached endpoint sessions whose configured idle lease elapsed. */
    public void closeIdle() {
        Instant now = clock.instant();
        for (String endpoint : endpoints.keySet()) {
            synchronized (endpointLocks.get(endpoint)) {
                CachedClient cached = clients.get(endpoint);
                if (cached != null && !now.minus(endpoint(endpoint).idleTimeout())
                        .isBefore(cached.lastUsedAt())) {
                    closeClient(endpoint);
                }
            }
        }
    }

    /** Returns whether the configured endpoint catalog contains a logical identity. */
    public boolean contains(String endpoint) {
        return endpoints.containsKey(endpoint);
    }

    @Override
    public void close() {
        for (String endpoint : endpoints.keySet()) {
            synchronized (endpointLocks.get(endpoint)) {
                closeClient(endpoint);
            }
        }
    }

    private SmbShareClient client(String endpoint) {
        CachedClient cached = clients.get(endpoint);
        if (cached != null) {
            return cached.client();
        }
        SmbShareClient opened = clientFactory.open(endpoint(endpoint));
        clients.put(endpoint, new CachedClient(opened, clock.instant()));
        return opened;
    }

    private void touch(String endpoint) {
        CachedClient cached = clients.get(endpoint);
        if (cached != null) {
            clients.put(endpoint, new CachedClient(cached.client(), clock.instant()));
        }
    }

    private void closeClient(String endpoint) {
        CachedClient cached = clients.remove(endpoint);
        if (cached != null) {
            cached.client().close();
        }
    }

    private SmbEndpointSettings endpoint(String name) {
        SmbEndpointSettings endpoint = endpoints.get(name);
        if (endpoint == null) {
            throw new IllegalArgumentException("unknown SMB endpoint: " + name);
        }
        return endpoint;
    }

    private void requireEndpointName(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
    }

    private record CachedClient(SmbShareClient client, Instant lastUsedAt) {
    }
}
