package com.iocextractor.adapter.out.events.spring;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.NoopControlEventObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.List;

import static com.iocextractor.adapter.out.events.spring.PublisherFixture.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringControlEventPublisherTest {
    @Test
    void rejectsMissingConstructorArguments() {
        assertThatThrownBy(() -> new SpringControlEventPublisher(null, NoopControlEventObserver.INSTANCE))
                .isInstanceOf(NullPointerException.class).hasMessage("publisher");
        assertThatThrownBy(() -> new SpringControlEventPublisher(ignored -> { }, null))
                .isInstanceOf(NullPointerException.class).hasMessage("observer");
    }

    @Test
    void rejectsNullBeforeCallingSpringOrObserver() {
        List<Object> delivered = new ArrayList<>();
        var observer = new PublisherFixture.Observer();
        var publisher = new SpringControlEventPublisher(delivered::add, observer);

        assertThatThrownBy(() -> publisher.publish(null))
                .isInstanceOf(NullPointerException.class).hasMessage("event");
        assertThat(delivered).isEmpty();
        assertThat(observer.published).isEmpty();
        assertThat(observer.failures).isEmpty();
    }

    @Test
    void publishesOriginalReferenceWithoutInspectingMetadata() {
        List<Object> delivered = new ArrayList<>();
        var observer = new PublisherFixture.Observer();
        ControlEvent event = () -> { throw new AssertionError("Metadata belongs to the producer"); };

        new SpringControlEventPublisher(delivered::add, observer).publish(event);

        assertThat(delivered).containsExactly(event);
        assertThat(observer.published).containsExactly(event);
        assertThat(observer.failures).isEmpty();
    }

    @Test
    void containsRuntimePublicationFailureAndReportsOriginalInstance() {
        var failure = new IllegalStateException("delivery failed");
        var observer = new PublisherFixture.Observer();
        var fallback = new PublisherFixture.Fallback();
        var publisher = new SpringControlEventPublisher(ignored -> { throw failure; }, observer, fallback.logger);

        assertThatNoException().isThrownBy(() -> publisher.publish(event()));

        assertThat(observer.failures).containsExactly(failure);
        assertThat(observer.published).isEmpty();
        assertThat(fallback.failures).isEmpty();
    }

    @Test
    void reportsSuccessObserverFailureWithoutReportingDeliveryFailure() {
        var observation = new IllegalStateException("observer failed");
        var observer = new PublisherFixture.Observer();
        observer.afterSuccess = () -> { throw observation; };
        var fallback = new PublisherFixture.Fallback();
        var event = event();

        new SpringControlEventPublisher(ignored -> { }, observer, fallback.logger).publish(event);

        assertThat(observer.published).containsExactly(event);
        assertThat(observer.failures).isEmpty();
        assertThat(fallback.failures).containsExactly(observation);
        assertThat(fallback.messages).containsExactly("control event observer failed after publication");
    }

    @Test
    void retainsPublicationFailureAsPrimaryAndSuppressesObserverFailure() {
        var publication = new IllegalStateException("publication failed");
        var observation = new IllegalArgumentException("observer failed");
        var observer = new PublisherFixture.Observer();
        observer.afterFailure = () -> { throw observation; };
        var fallback = new PublisherFixture.Fallback();

        new SpringControlEventPublisher(ignored -> { throw publication; }, observer, fallback.logger).publish(event());

        assertThat(observer.failures).containsExactly(publication);
        assertThat(fallback.failures).containsExactly(publication);
        assertThat(publication.getSuppressed()).containsExactly(observation);
        assertThat(fallback.messages).containsExactly("control event observer failed after publication failure");
    }

    @Test
    void avoidsSelfSuppressionWhenObserverRethrowsPublicationFailure() {
        var publication = new IllegalStateException("shared failure");
        var observer = new PublisherFixture.Observer();
        observer.afterFailure = () -> { throw publication; };
        var fallback = new PublisherFixture.Fallback();

        assertThatNoException().isThrownBy(() -> new SpringControlEventPublisher(
                ignored -> { throw publication; }, observer, fallback.logger).publish(event()));

        assertThat(publication.getSuppressed()).isEmpty();
        assertThat(fallback.failures).containsExactly(publication);
    }

    @Test
    void retainsPrimaryFailureWhenSuppressionIsDisabled() {
        var publication = new SuppressionDisabledFailure();
        var observer = new PublisherFixture.Observer();
        observer.afterFailure = () -> { throw new IllegalStateException("observer failed"); };
        var fallback = new PublisherFixture.Fallback();

        new SpringControlEventPublisher(ignored -> { throw publication; }, observer, fallback.logger).publish(event());

        assertThat(publication.getSuppressed()).isEmpty();
        assertThat(fallback.failures).containsExactly(publication);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void containsThrowingFallbackWithoutRetryOrOutcomeReclassification(boolean deliveryFails) {
        var publication = new IllegalStateException("publication failed");
        var observation = new IllegalStateException("observer failed");
        var observer = new PublisherFixture.Observer();
        observer.afterSuccess = () -> { throw observation; };
        observer.afterFailure = () -> { throw observation; };
        var fallback = new PublisherFixture.Fallback();
        fallback.afterWarn = () -> { throw new IllegalStateException("provider failed"); };
        List<Object> attempts = new ArrayList<>();
        var event = event();
        var publisher = new SpringControlEventPublisher(value -> {
            attempts.add(value);
            if (deliveryFails) {
                throw publication;
            }
        }, observer, fallback.logger);

        assertThatNoException().isThrownBy(() -> publisher.publish(event));

        assertThat(attempts).containsExactly(event);
        assertThat(fallback.failures).containsExactly(deliveryFails ? publication : observation);
        assertThat(observer.failures).containsExactlyElementsOf(deliveryFails ? List.of(publication) : List.of());
        assertThat(observer.published).containsExactlyElementsOf(deliveryFails ? List.of() : List.of(event));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesDirectAndSpringWrappedErrorWithoutObservingRuntimeFailure(boolean wrapped) {
        var fatal = new AssertionError("fatal delivery");
        var observer = new PublisherFixture.Observer();
        var publisher = new SpringControlEventPublisher(ignored -> {
            if (wrapped) {
                throw new UndeclaredThrowableException(fatal);
            }
            throw fatal;
        }, observer);

        assertThatThrownBy(() -> publisher.publish(event())).isSameAs(fatal);
        assertThat(observer.published).isEmpty();
        assertThat(observer.failures).isEmpty();
    }

    @Test
    void doesNotUnwrapOrdinaryRuntimeOrCheckedFailures() {
        var observer = new PublisherFixture.Observer();
        var wrappers = List.of(new UndeclaredThrowableException(new IllegalStateException("runtime")),
                new UndeclaredThrowableException(new Exception("checked")),
                new UndeclaredThrowableException(null),
                new IllegalStateException("service wrapper", new AssertionError("nested fatal")));

        for (RuntimeException wrapper : wrappers) {
            new SpringControlEventPublisher(ignored -> { throw wrapper; }, observer).publish(event());
        }

        assertThat(observer.failures).containsExactlyElementsOf(wrappers);
        assertThat(observer.published).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesObserverErrorAfterEitherPublicationOutcome(boolean deliveryFails) {
        var fatal = new AssertionError("fatal observer");
        var observer = new PublisherFixture.Observer();
        observer.afterSuccess = () -> { throw fatal; };
        observer.afterFailure = () -> { throw fatal; };
        var publisher = new SpringControlEventPublisher(ignored -> {
            if (deliveryFails) {
                throw new IllegalStateException("delivery failed");
            }
        }, observer);

        assertThatThrownBy(() -> publisher.publish(event())).isSameAs(fatal);
    }

    @Test
    void propagatesFallbackLoggerError() {
        var fatal = new AssertionError("fatal logger");
        var observer = new PublisherFixture.Observer();
        observer.afterSuccess = () -> { throw new IllegalStateException("observer failed"); };
        var fallback = new PublisherFixture.Fallback();
        fallback.afterWarn = () -> { throw fatal; };

        assertThatThrownBy(() -> new SpringControlEventPublisher(
                ignored -> { }, observer, fallback.logger).publish(event())).isSameAs(fatal);
        assertThat(observer.failures).isEmpty();
        assertThat(fallback.failures).hasSize(1);
    }

    private static final class SuppressionDisabledFailure extends RuntimeException {
        private SuppressionDisabledFailure() {
            super("publication failed", null, false, true);
        }
    }
}
