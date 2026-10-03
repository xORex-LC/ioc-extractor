package com.iocextractor.processing.session;

import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.NetworkAddressParser;
import com.iocextractor.domain.feature.NetworkHostDeriver;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.processing.classification.IndicatorClassifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-confined, invocation-owned reuse of successful immutable semantic results.
 * The classifier and its policy must remain fixed during this scope. Classification
 * uses the complete indicator, including source; host derivation reconstructs source.
 * Exhausted admission budgets change computation cost only, never accepted results.
 */
public final class IndicatorProcessingSession implements AutoCloseable {
    /** Conservative defaults; limits apply jointly to both caches, not to total input memory. */
    public static final int DEFAULT_MAX_ENTRIES = 256;
    public static final long DEFAULT_MAX_RETAINED_BYTES = 1_048_576;
    private static final long MAX_ENTRY_BYTES = 65_536;
    private final IndicatorClassifier classifier;
    private final NetworkHostDeriver deriver = new NetworkHostDeriver(new NetworkAddressParser());
    private final int maxEntries;
    private final long maxRetainedBytes;
    private final Map<Indicator, ClassificationDecision> classifications = new HashMap<>();
    private final Map<HostKey, HostValue> hosts = new HashMap<>();
    private long retainedBytes;
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
        this.maxEntries = maxEntries;
        this.maxRetainedBytes = maxRetainedBytes;
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
        if (admit(bytes)) {
            classifications.put(indicator, result);
        }
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
        if (result.isAvailable() && admit(256L + textBytes(key.value()) + textBytes(result.indicator().value()))) {
            hosts.put(key, new HostValue(result.indicator().value(), result.indicator().type()));
        }
        return result;
    }

    private boolean admit(long bytes) {
        if (classifications.size() + hosts.size() >= maxEntries || bytes > MAX_ENTRY_BYTES
                || bytes > maxRetainedBytes - retainedBytes) {
            return false;
        }
        retainedBytes += bytes;
        return true;
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
        retainedBytes = 0;
        closed = true;
    }

    private record HostKey(String value, IndicatorType type) { }
    private record HostValue(String value, IndicatorType type) { }
}
