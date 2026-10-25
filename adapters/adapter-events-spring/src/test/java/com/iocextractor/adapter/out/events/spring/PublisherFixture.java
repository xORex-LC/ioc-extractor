package com.iocextractor.adapter.out.events.spring;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.ControlEventObserver;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class PublisherFixture {
    private PublisherFixture() {
    }

    static ControlEvent event() {
        return new RefreshRequested(ControlEventMetadata.withoutCausation(
                "refresh-1", "collector.refresh-requested", 1, Instant.EPOCH, "poll-1"), "feed-a");
    }

    record RefreshRequested(ControlEventMetadata metadata, String sourceId) implements ControlEvent {
    }

    static final class Observer implements ControlEventObserver {
        final List<ControlEvent> published = new ArrayList<>();
        final List<RuntimeException> failures = new ArrayList<>();
        Runnable afterSuccess = () -> { };
        Runnable afterFailure = () -> { };

        @Override
        public void published(ControlEvent event) {
            published.add(event);
            afterSuccess.run();
        }

        @Override
        public void publishFailed(ControlEvent event, RuntimeException failure) {
            failures.add(failure);
            afterFailure.run();
        }

        @Override
        public void dispatching(ControlEvent event, String handlerName) {
            throw new AssertionError("The publisher must not report individual dispatch");
        }

        @Override
        public void dispatchFailed(ControlEvent event, String handlerName, RuntimeException failure) {
            throw new AssertionError("The publisher must not report individual dispatch");
        }
    }

    static final class Fallback {
        final List<Throwable> failures = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        Runnable afterWarn = () -> { };
        final Logger logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[]{Logger.class}, (proxy, method, args) -> {
                    if (method.getName().equals("warn")) {
                        messages.add((String) args[0]);
                        failures.add((Throwable) args[1]);
                        afterWarn.run();
                        return null;
                    }
                    throw new AssertionError("Unexpected fallback logger call: " + method);
                });
    }
}
