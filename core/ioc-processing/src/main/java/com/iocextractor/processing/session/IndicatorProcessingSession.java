package com.iocextractor.processing.session;

import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.NetworkAddressParser;
import com.iocextractor.domain.feature.NetworkHostDeriver;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.processing.classification.IndicatorClassifier;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Thread-confined, invocation-owned reuse of successful immutable semantic results.
 * The classifier and its policy must remain fixed during this scope. Classification
 * uses the complete indicator, including source; host derivation reconstructs source.
 * Bounded LRU partitions change computation cost only, never accepted results.
 */
public final class IndicatorProcessingSession implements AutoCloseable {
    /** Conservative total limits, divided between classification and host reuse. */
    public static final int DEFAULT_MAX_ENTRIES = 256;
    public static final long DEFAULT_MAX_RETAINED_BYTES = 1_048_576;
    private static final long MAX_ENTRY_BYTES = 65_536;
    private final IndicatorClassifier classifier;
    private final NetworkHostDeriver deriver = new NetworkHostDeriver(new NetworkAddressParser());
    private final Cache<Indicator, ClassificationDecision> classifications;
    private final Cache<HostKey, HostValue> hosts;
    private boolean closed;

    public IndicatorProcessingSession(IndicatorClassifier classifier) {
        this(classifier, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_RETAINED_BYTES);
    }

    /** Explicit limits also allow an uncached scope with zero admission capacity. */
    public IndicatorProcessingSession(IndicatorClassifier classifier, int maxEntries, long maxRetainedBytes) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        if (maxEntries < 0 || maxRetainedBytes < 0) {
            throw new IllegalArgumentException("Semantic admission budgets must be nonnegative");
        }
        classifications = new Cache<>(maxEntries - maxEntries / 2,
                maxRetainedBytes - maxRetainedBytes / 2);
        hosts = new Cache<>(maxEntries / 2, maxRetainedBytes / 2);
    }

    /** Computes with the existing policy; no occurrence diagnostics or prepared rows are retained. */
    public ClassificationDecision classify(Indicator indicator) {
        requireOpen();
        Objects.requireNonNull(indicator, "indicator");
        ClassificationDecision existing = classifications.get(indicator);
        if (existing != null) {
            return existing;
        }
        ClassificationDecision result = Objects.requireNonNull(classifier.classify(indicator), "classification");
        long bytes = classificationBytes(indicator, result);
        classifications.put(indicator, result, bytes);
        return result;
    }

    /** A different operation policy bypasses this scope's classification cache. */
    public ClassificationDecision classifyWith(Indicator indicator, IndicatorClassifier operationClassifier) {
        requireOpen();
        return classifier.sharesPolicyWith(operationClassifier) ? classify(indicator)
                : operationClassifier.classify(indicator);
    }

    /** Reuses only successful host value/type pairs, always reattaching the current source. */
    public NetworkHostDeriver.Result deriveHost(Indicator indicator) {
        requireOpen();
        Objects.requireNonNull(indicator, "indicator");
        var key = new HostKey(indicator.value(), indicator.type());
        HostValue existing = hosts.get(key);
        if (existing != null) {
            return new NetworkHostDeriver.Result(
                    new Indicator(existing.value(), existing.type(), indicator.source()), null);
        }
        NetworkHostDeriver.Result result = deriver.derive(indicator);
        if (result.isAvailable()) {
            hosts.put(key, new HostValue(result.indicator().value(), result.indicator().type()),
                    256L + textBytes(key.value()) + textBytes(result.indicator().value()));
        }
        return result;
    }

    private static long classificationBytes(Indicator key, ClassificationDecision value) {
        long bytes = 512L + textBytes(key.value()) + textBytes(key.source().label())
                + textBytes(key.source().section()) + textBytes(value.features().value())
                + textBytes(value.features().host()) + textBytes(value.match().urlMatch())
                + textBytes(value.match().hostMatch());
        for (String predicate : value.matchedPredicates()) {
            bytes += 32L + textBytes(predicate);
        }
        return bytes;
    }

    private static long textBytes(String value) {
        return value == null ? 0 : 48L + 2L * value.length();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Indicator processing session is closed");
        }
    }

    /** Clears references on every exit path; a closed session cannot process further observations. */
    @Override
    public void close() {
        classifications.clear();
        hosts.clear();
        closed = true;
    }

    /** Local implementation only: access order keeps hot derived hosts through URL churn. */
    private static final class Cache<K, V> {
        private final int maxEntries;
        private final long maxBytes;
        private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long retainedBytes;

        private Cache(int maxEntries, long maxBytes) {
            this.maxEntries = maxEntries;
            this.maxBytes = maxBytes;
        }

        private V get(K key) {
            Entry<V> entry = entries.get(key);
            return entry == null ? null : entry.value();
        }

        /** Called only on a cache miss. Oversized values never evict useful entries. */
        private void put(K key, V value, long bytes) {
            if (maxEntries == 0 || bytes > MAX_ENTRY_BYTES || bytes > maxBytes) {
                return;
            }
            while (entries.size() >= maxEntries || bytes > maxBytes - retainedBytes) {
                retainedBytes -= entries.pollFirstEntry().getValue().bytes();
            }
            entries.put(key, new Entry<>(value, bytes));
            retainedBytes += bytes;
        }

        private void clear() {
            entries.clear();
            retainedBytes = 0;
        }

        private record Entry<V>(V value, long bytes) { }
    }

    private record HostKey(String value, IndicatorType type) { }
    private record HostValue(String value, IndicatorType type) { }
}
