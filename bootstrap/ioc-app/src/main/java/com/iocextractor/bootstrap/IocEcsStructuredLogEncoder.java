package com.iocextractor.bootstrap;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot ECS encoder that contributes the static {@code event.dataset}
 * through the same context-pair stream as event-local {@code event.*} fields.
 *
 * <p>Spring Boot 3.5 renders ECS context pairs as nested JSON. A static
 * {@code logging.structured.json.add.event.dataset} member and dynamic
 * {@code event.*} context pairs would therefore create two top-level
 * {@code event} objects. Contributing the dataset as a context pair lets the
 * standard formatter merge them into one object without changing scalar
 * values or the platform logging API.
 */
public final class IocEcsStructuredLogEncoder extends StructuredLogEncoder {

    private static final String EVENT_DATASET = "event.dataset";

    private String eventDataset;

    public void setEventDataset(String eventDataset) {
        this.eventDataset = eventDataset;
    }

    @Override
    public void start() {
        if (eventDataset == null || eventDataset.isBlank()) {
            throw new IllegalStateException("eventDataset must not be blank");
        }
        super.start();
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        if (!(event instanceof LoggingEvent mutableEvent) || containsDataset(event.getKeyValuePairs())) {
            return super.encode(event);
        }

        List<KeyValuePair> originalPairs = mutableEvent.getKeyValuePairs();
        var enrichedPairs = originalPairs == null
                ? new ArrayList<KeyValuePair>()
                : new ArrayList<>(originalPairs);
        enrichedPairs.add(new KeyValuePair(EVENT_DATASET, eventDataset));
        mutableEvent.setKeyValuePairs(enrichedPairs);
        try {
            return super.encode(mutableEvent);
        } finally {
            mutableEvent.setKeyValuePairs(originalPairs);
        }
    }

    private boolean containsDataset(List<KeyValuePair> pairs) {
        return pairs != null && pairs.stream().anyMatch(pair -> EVENT_DATASET.equals(pair.key));
    }
}
