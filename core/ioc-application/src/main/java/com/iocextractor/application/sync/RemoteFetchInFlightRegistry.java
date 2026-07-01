package com.iocextractor.application.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local suppression of remote identities already admitted for fetch execution.
 *
 * <p>This registry is only a latency and queue-pressure optimization. The durable fetch ledger
 * remains the idempotency source of truth, and identities are released after every execution
 * outcome so failed or rejected work can be detected again.</p>
 */
public final class RemoteFetchInFlightRegistry {

    private final Set<RemoteObjectIdentity> claimed = ConcurrentHashMap.newKeySet();

    /** Atomically claims identities not already in flight and returns their objects in input order. */
    public List<RemoteObject> claim(Collection<RemoteObject> objects) {
        Objects.requireNonNull(objects, "objects");
        List<RemoteObject> newlyClaimed = new ArrayList<>(objects.size());
        for (RemoteObject object : objects) {
            RemoteObject required = Objects.requireNonNull(object, "objects must not contain null");
            if (claimed.add(required.identity())) {
                newlyClaimed.add(required);
            }
        }
        return List.copyOf(newlyClaimed);
    }

    /** Returns whether this identity is currently queued or executing in this process. */
    public boolean contains(RemoteObjectIdentity identity) {
        return claimed.contains(Objects.requireNonNull(identity, "identity"));
    }

    /** Releases identities after success, failure or rejected admission. */
    public void release(Collection<RemoteObject> objects) {
        Objects.requireNonNull(objects, "objects");
        for (RemoteObject object : objects) {
            claimed.remove(Objects.requireNonNull(object, "objects must not contain null").identity());
        }
    }
}
