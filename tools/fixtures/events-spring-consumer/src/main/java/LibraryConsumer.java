import com.iocextractor.adapter.out.events.spring.SpringControlEventPublisher;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.ControlEventObserver;
import com.iocextractor.platform.events.ControlEventPublisher;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.annotation.Order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Qualifies the public packaged adapter without IOC wiring, a Boot runtime or a provider. */
public final class LibraryConsumer {
    private LibraryConsumer() { }

    /** Runs synchronous contract scenarios and both exact guide examples. */
    public static void main(String[] args) {
        var event = new FeedRefreshRequested(ControlEventMetadata.withoutCausation(
                "refresh-4", "collector.feed-refresh", 1, Instant.EPOCH, "poll-2"), "feed-alpha");
        partialDelivery(event);
        serviceOwnsContinuation(event);
        fatalPublication(event);
        observationAndInput(event);
        check(LoggerFactory.getILoggerFactory().getClass().getName().equals("org.slf4j.helpers.NOPLoggerFactory"),
                "adapter supplied a logging provider");
        absent("org.springframework.boot.SpringApplication");
        absent("com.iocextractor.bootstrap.IocExtractorApplication");
        absent("com.iocextractor.application.tck.junit.IntegrationTest");
        LocalEventsExample.main(new String[0]);
        SpringEventsExample.main(new String[0]);
        System.out.println("EVENTS_CONSUMER_OK Spring partial delivery, Errors, observation and service-owned dispatch");
    }

    private static void partialDelivery(FeedRefreshRequested event) {
        var listeners = new OrderedListeners();
        var observer = new Observer();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(OrderedListeners.class, () -> listeners);
            context.refresh();
            var multicaster = context.getBean("applicationEventMulticaster");
            var names = context.getBeanDefinitionNames();
            var publisher = new SpringControlEventPublisher(context, observer);
            publisher.publish(event);
            check(listeners.calls.equals(List.of("first", "failing")), "partial dispatch retried or continued");
            check(observer.failures.equals(List.of(listeners.failure)) && observer.published.isEmpty(),
                    "publication failure identity/outcome was changed");
            check(context.getBean("applicationEventMulticaster") == multicaster, "adapter replaced the multicaster");
            check(Arrays.equals(names, context.getBeanDefinitionNames()), "adapter registered beans");
            check(context.getBeansOfType(ControlEventPublisher.class).isEmpty(), "adapter registered a publisher");
        }
    }

    private static void serviceOwnsContinuation(FeedRefreshRequested event) {
        var listeners = new OrderedListeners();
        var observer = new Observer();
        List<Throwable> handled = new ArrayList<>();
        List<Runnable> executed = new ArrayList<>();
        try (var context = new AnnotationConfigApplicationContext()) {
            var multicaster = new SimpleApplicationEventMulticaster(context.getBeanFactory());
            multicaster.setErrorHandler(handled::add);
            multicaster.setTaskExecutor(task -> {
                executed.add(task);
                task.run();
            });
            context.getBeanFactory().registerSingleton("applicationEventMulticaster", multicaster);
            context.registerBean(OrderedListeners.class, () -> listeners);
            context.refresh();
            executed.clear();
            new SpringControlEventPublisher(context, observer).publish(event);
            check(context.getBean("applicationEventMulticaster") == multicaster, "service multicaster was replaced");
            check(listeners.calls.equals(List.of("first", "failing", "last")), "service handler did not continue");
            check(handled.equals(List.of(listeners.failure)) && executed.size() == 3, "service dispatch configuration changed");
            check(observer.published.equals(List.of(event)) && observer.failures.isEmpty(), "handled failure was reclassified");
        }
    }

    private static void fatalPublication(FeedRefreshRequested event) {
        var observer = new Observer();
        var direct = new AssertionError("direct publication Error");
        expectSame(direct, () -> new SpringControlEventPublisher(value -> { throw direct; }, observer).publish(event));
        var listener = new FatalListener();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(FatalListener.class, () -> listener);
            context.refresh();
            expectSame(listener.fatal, () -> new SpringControlEventPublisher(context, observer).publish(event));
        }
        check(observer.published.isEmpty() && observer.failures.isEmpty(), "Error became an observed runtime failure");
    }

    private static void observationAndInput(FeedRefreshRequested event) {
        var observer = new Observer();
        List<Object> delivered = new ArrayList<>();
        var publisher = new SpringControlEventPublisher(delivered::add, observer);
        try {
            publisher.publish(null);
            throw new AssertionError("null event accepted");
        } catch (NullPointerException expected) {
            check(delivered.isEmpty() && observer.published.isEmpty() && observer.failures.isEmpty(),
                    "null event reached publication or observation");
        }
        observer.telemetryFailure = new IllegalStateException("telemetry failed");
        publisher.publish(event);
        check(delivered.equals(List.of(event)) && observer.published.equals(List.of(event)) && observer.failures.isEmpty(),
                "success observation failure became delivery failure");
        var primary = new IllegalStateException("publication failed");
        new SpringControlEventPublisher(value -> { throw primary; }, observer).publish(event);
        check(observer.failures.equals(List.of(primary)), "primary publication evidence changed");
        check(Arrays.equals(primary.getSuppressed(), new Throwable[]{observer.telemetryFailure}),
                "observer failure was not retained with primary evidence");
        observer.telemetryFailure = null;
        observer.telemetryFatal = new AssertionError("observer Error");
        expectSame(observer.telemetryFatal, () -> publisher.publish(event));
    }

    /** An unrelated service payload; no IOC model or listener is reused. */
    public record FeedRefreshRequested(ControlEventMetadata metadata, String sourceId) implements ControlEvent { }

    /** Ordered listeners make partial dispatch observable. */
    public static final class OrderedListeners {
        private final List<String> calls = new ArrayList<>();
        private final RuntimeException failure = new IllegalStateException("feed refresh failed");

        @EventListener @Order(10)
        public void first(FeedRefreshRequested event) { calls.add("first"); }

        @EventListener @Order(20)
        public void failing(FeedRefreshRequested event) { calls.add("failing"); throw failure; }

        @EventListener @Order(30)
        public void last(FeedRefreshRequested event) { calls.add("last"); }
    }

    /** A real annotated listener makes Spring's reflection wrapper part of the test. */
    public static final class FatalListener {
        private final AssertionError fatal = new AssertionError("fatal listener");

        @EventListener
        public void refresh(FeedRefreshRequested event) { throw fatal; }
    }

    private static final class Observer implements ControlEventObserver {
        private final List<ControlEvent> published = new ArrayList<>();
        private final List<RuntimeException> failures = new ArrayList<>();
        private RuntimeException telemetryFailure;
        private Error telemetryFatal;

        @Override
        public void published(ControlEvent event) {
            published.add(event);
            if (telemetryFatal != null) { throw telemetryFatal; }
            if (telemetryFailure != null) { throw telemetryFailure; }
        }

        @Override
        public void publishFailed(ControlEvent event, RuntimeException failure) {
            failures.add(failure);
            if (telemetryFailure != null) { throw telemetryFailure; }
        }

        @Override
        public void dispatching(ControlEvent event, String handlerName) {
            throw new AssertionError("adapter invoked service-owned dispatch callback");
        }

        @Override
        public void dispatchFailed(ControlEvent event, String handlerName, RuntimeException failure) {
            throw new AssertionError("adapter invoked service-owned dispatch callback");
        }
    }

    private static void expectSame(Error expected, Runnable action) {
        try {
            action.run();
        } catch (Error actual) {
            check(actual == expected, "original Error identity was lost");
            return;
        }
        throw new AssertionError("expected original Error");
    }

    private static void absent(String name) {
        try {
            Class.forName(name);
            throw new AssertionError("unexpected runtime dependency: " + name);
        } catch (ClassNotFoundException expected) {
            // A standalone adapter must not pull the service, Boot or test support into runtime.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
