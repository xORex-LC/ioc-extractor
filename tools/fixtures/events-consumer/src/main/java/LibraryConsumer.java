import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.ControlEventPublisher;
import com.iocextractor.platform.events.NoopControlEventObserver;
import com.iocextractor.platform.events.NoopControlEventPublisher;
import com.iocextractor.platform.events.RecordingControlEventPublisher;

import java.time.Instant;
import java.util.List;

/** Exercises the packaged JDK contracts without an IOC application or test framework. */
public final class LibraryConsumer {
    private LibraryConsumer() { }

    /** Runs explicit checks; failures do not depend on the JVM's assertions flag. */
    public static void main(String[] args) {
        var metadata = new ControlEventMetadata(" request-3 ", "collector.feed-refresh", 2,
                Instant.EPOCH, "poll-9", "upstream-1");
        check(metadata.eventId().equals(" request-3 "), "metadata text was normalized");
        check(metadata.causation().orElseThrow().equals("upstream-1"), "causation was lost");
        var event = new FeedRefreshRequested(metadata, "feed-alpha", List.of("etag-3"));
        var recorder = new RecordingControlEventPublisher();
        ControlEventPublisher publisher = recorder;
        publisher.publish(event);
        var snapshot = recorder.events();
        publisher.publish(event);
        check(snapshot.size() == 1 && snapshot.getFirst() == event, "snapshot lost its event reference");
        check(recorder.events().equals(List.of(event, event)), "duplicate event was removed");
        expect(UnsupportedOperationException.class, () -> snapshot.add(event));
        var uncaused = ControlEventMetadata.withoutCausation("id", "collector.feed-refresh", 1, Instant.EPOCH, "poll");
        check(uncaused.causation().isEmpty(), "absent causation was invented");
        expect(IllegalArgumentException.class,
                () -> ControlEventMetadata.withoutCausation("id", "type", 0, Instant.EPOCH, "poll"));
        expect(IllegalArgumentException.class,
                () -> ControlEventMetadata.withoutCausation(" ", "type", 1, Instant.EPOCH, "poll"));
        expect(NullPointerException.class, () -> publisher.publish(null));
        NoopControlEventPublisher.INSTANCE.publish(event);
        var observer = NoopControlEventObserver.INSTANCE;
        observer.published(event);
        observer.publishFailed(event, new IllegalStateException("example"));
        observer.dispatching(event, "collector");
        observer.dispatchFailed(event, "collector", new IllegalStateException("example"));
        absent("org.springframework.context.ApplicationContext");
        absent("org.slf4j.LoggerFactory");
        absent("com.iocextractor.bootstrap.IocExtractorApplication");
        LocalEventsExample.main(new String[0]);
        System.out.println("EVENTS_CONSUMER_OK JDK metadata, recorder, no-ops and exact guide");
    }

    private record FeedRefreshRequested(ControlEventMetadata metadata, String feed, List<String> revisions)
            implements ControlEvent { }

    private static void absent(String name) {
        try {
            Class.forName(name);
            throw new AssertionError("unexpected runtime dependency: " + name);
        } catch (ClassNotFoundException expected) {
            // The JDK-only consumer must not acquire framework or service classes.
        }
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            check(type.isInstance(failure), "unexpected exception: " + failure);
            return;
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
