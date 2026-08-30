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

    private static final String POOL_OWNER = "pool";

    private final Map<String, SmbEndpointSettings> endpoints;
    private final SmbShareClientFactory clientFactory;
    private final Clock clock;
    private final SmbTransportTelemetry telemetry;
    private final Map<String, CachedClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Object> endpointLocks = new HashMap<>();

    /** Creates the production pool without opening network connections eagerly. */
    public SmbSessionPool(List<SmbEndpointSettings> endpoints) {
        this(endpoints, new SmbTransportTelemetry());
    }

    /** Creates the production pool with shared adapter telemetry. */
    public SmbSessionPool(List<SmbEndpointSettings> endpoints, SmbTransportTelemetry telemetry) {
        this(endpoints, new SmbjShareClientFactory(), Clock.systemUTC(), telemetry);
    }

    SmbSessionPool(List<SmbEndpointSettings> endpoints,
                   SmbShareClientFactory clientFactory,
                   Clock clock) {
        this(endpoints, clientFactory, clock, new SmbTransportTelemetry(clock));
    }

    SmbSessionPool(List<SmbEndpointSettings> endpoints,
                   SmbShareClientFactory clientFactory,
                   Clock clock,
                   SmbTransportTelemetry telemetry) {
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
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    <T> T withClient(String endpoint, String operation, Function<SmbShareClient, T> action) {
        requireEndpointName(endpoint);
        endpoint(endpoint);
        synchronized (endpointLocks.get(endpoint)) {
            SmbShareClient client;
            try {
                client = client(endpoint);
            } catch (RuntimeException failure) {
                RemoteTransportException mapped = SmbExceptionMapper.map(failure, operation, endpoint);
                telemetry.recordOpenFailure(endpoint,
                        SmbTransportTelemetry.Role.POOLED_TRANSPORT, POOL_OWNER, mapped.kind());
                throw mapped;
            }
            try {
                T result = action.apply(client);
                touch(endpoint);
                telemetry.recordOperationSuccess(
                        endpoint, SmbTransportTelemetry.Role.POOLED_TRANSPORT, POOL_OWNER);
                return result;
            } catch (RuntimeException failure) {
                RemoteTransportException mapped = SmbExceptionMapper.map(failure, operation, endpoint);
                telemetry.recordOperationFailure(endpoint,
                        SmbTransportTelemetry.Role.POOLED_TRANSPORT, POOL_OWNER, mapped.kind());
                if (mapped.kind() == RemoteErrorKind.TRANSIENT
                        || mapped.kind() == RemoteErrorKind.UNREACHABLE
                        || mapped.kind() == RemoteErrorKind.RESOURCE_EXHAUSTED) {
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
        SmbTransportTelemetry.Lease lease = telemetry.sessionOpened(
                endpoint, SmbTransportTelemetry.Role.POOLED_TRANSPORT, POOL_OWNER);
        clients.put(endpoint, new CachedClient(opened, clock.instant(), lease));
        return opened;
    }

    private void touch(String endpoint) {
        CachedClient cached = clients.get(endpoint);
        if (cached != null) {
            clients.put(endpoint, new CachedClient(cached.client(), clock.instant(), cached.lease()));
        }
    }

    private void closeClient(String endpoint) {
        CachedClient cached = clients.remove(endpoint);
        if (cached != null) {
            try {
                cached.client().close();
            } finally {
                cached.lease().close();
            }
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

    private record CachedClient(
            SmbShareClient client,
            Instant lastUsedAt,
            SmbTransportTelemetry.Lease lease) {
    }
}
