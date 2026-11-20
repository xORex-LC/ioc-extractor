package com.iocextractor.adapter.out.events.spring;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;

import static com.iocextractor.adapter.out.events.spring.PublisherFixture.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class SpringControlEventPublisherIT {
    @Test
    void defaultMulticastStopsAfterPartialDeliveryAndProducerReturns() {
        var handlers = new OrderedHandlers();
        var observer = new PublisherFixture.Observer();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(OrderedHandlers.class, () -> handlers);
            context.refresh();
            var publisher = new SpringControlEventPublisher(context, observer);

            assertThatNoException().isThrownBy(() -> publisher.publish(event()));

            assertThat(handlers.calls).containsExactly("first", "failing");
            assertThat(observer.failures).containsExactly(handlers.failure);
            assertThat(observer.published).isEmpty();
        }
    }

    @Test
    void rethrowsOriginalErrorWrappedByAnnotatedSpringListener() {
        var handler = new FatalHandler();
        var observer = new PublisherFixture.Observer();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(FatalHandler.class, () -> handler);
            context.refresh();
            var publisher = new SpringControlEventPublisher(context, observer);

            assertThatThrownBy(() -> publisher.publish(event())).isSameAs(handler.fatal);

            assertThat(observer.failures).isEmpty();
            assertThat(observer.published).isEmpty();
        }
    }

    @Test
    void serviceErrorHandlerOwnsContinuationAndPublisherObservesNormalReturn() {
        var handlers = new OrderedHandlers();
        var observer = new PublisherFixture.Observer();
        List<Throwable> handled = new ArrayList<>();
        List<Object> executed = new ArrayList<>();
        try (var context = new AnnotationConfigApplicationContext()) {
            var multicaster = new SimpleApplicationEventMulticaster(context.getBeanFactory());
            multicaster.setErrorHandler(handled::add);
            multicaster.setTaskExecutor(task -> {
                executed.add(task);
                task.run();
            });
            context.getBeanFactory().registerSingleton("applicationEventMulticaster", multicaster);
            context.registerBean(OrderedHandlers.class, () -> handlers);
            context.refresh();
            String[] beanNames = context.getBeanDefinitionNames();
            var publisher = new SpringControlEventPublisher(context, observer);
            executed.clear();
            var event = event();

            publisher.publish(event);

            assertThat(context.getBean("applicationEventMulticaster")).isSameAs(multicaster);
            assertThat(context.getBeanDefinitionNames()).containsExactly(beanNames);
            assertThat(context.getBeansOfType(ControlEventPublisher.class)).isEmpty();
            assertThat(handlers.calls).containsExactly("first", "failing", "last");
            assertThat(handled).containsExactly(handlers.failure);
            assertThat(executed).hasSize(3);
            assertThat(observer.published).containsExactly(event);
            assertThat(observer.failures).isEmpty();
        }
    }

    @Test
    void noListenersStillReportsSuccessfulPublicationWithoutAddingBeans() {
        var observer = new PublisherFixture.Observer();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            Object multicaster = context.getBean("applicationEventMulticaster");
            String[] beanNames = context.getBeanDefinitionNames();
            var event = event();

            new SpringControlEventPublisher(context, observer).publish(event);

            assertThat(context.getBean("applicationEventMulticaster")).isSameAs(multicaster);
            assertThat(context.getBeanDefinitionNames()).containsExactly(beanNames);
            assertThat(observer.published).containsExactly(event);
            assertThat(observer.failures).isEmpty();
        }
    }

    static class OrderedHandlers {
        final List<String> calls = new ArrayList<>();
        final RuntimeException failure = new IllegalStateException("handler failed");

        @EventListener
        @Order(10)
        public void first(PublisherFixture.RefreshRequested event) {
            calls.add("first");
        }

        @EventListener
        @Order(20)
        public void failing(PublisherFixture.RefreshRequested event) {
            calls.add("failing");
            throw failure;
        }

        @EventListener
        @Order(30)
        public void last(PublisherFixture.RefreshRequested event) {
            calls.add("last");
        }
    }

    static class FatalHandler {
        final AssertionError fatal = new AssertionError("fatal listener");

        @EventListener
        public void handle(ControlEvent event) {
            throw fatal;
        }
    }
}
