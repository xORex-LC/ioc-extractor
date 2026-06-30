package com.iocextractor.bootstrap;

import com.iocextractor.application.export.SliceCompleted;
import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.port.in.sync.PublishCompletedSliceCommand;
import com.iocextractor.application.sync.PublishTarget;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.ControlEventObserver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SliceCompletedPublishListenerTest {

    @Test
    void submitsPublishCommandForEachTargetInCompletedSliceProfile() {
        RecordingPublisher publisher = new RecordingPublisher();
        RecordingObserver observer = new RecordingObserver();
        SliceCompletedPublishListener listener = new SliceCompletedPublishListener(
                publisher,
                new DirectKeyedExecutor(),
                observer,
                List.of(
                        new PublishTarget("target-a", "endpoint-a", "/a", "reputation"),
                        new PublishTarget("target-b", "endpoint-b", "/b", "reputation"),
                        new PublishTarget("target-c", "endpoint-c", "/c", "archive")));
        SliceCompleted event = event();

        listener.onSliceCompleted(event);

        assertThat(publisher.commands).extracting(command -> command.target().orElseThrow())
                .containsExactly("target-a", "target-b");
        assertThat(publisher.commands).allSatisfy(command -> {
            assertThat(command.profile()).isEqualTo("reputation");
            assertThat(command.sliceId()).isEqualTo("slice-1");
            assertThat(command.sliceName()).isEqualTo("20260628T000000Z__slice-1");
            assertThat(command.correlationId()).isEqualTo("corr-1");
            assertThat(command.causationId()).isEqualTo("event-1");
        });
        assertThat(observer.dispatching).containsExactly(event, event);
        assertThat(observer.failures).isEmpty();
    }

    @Test
    void rejectedAdmissionIsObservedWithoutThrowingToPublisherThread() {
        RecordingPublisher publisher = new RecordingPublisher();
        RecordingObserver observer = new RecordingObserver();
        SliceCompletedPublishListener listener = new SliceCompletedPublishListener(
                publisher,
                new RejectingKeyedExecutor(),
                observer,
                List.of(new PublishTarget("target-a", "endpoint-a", "/a", "reputation")));

        listener.onSliceCompleted(event());

        assertThat(publisher.commands).isEmpty();
        assertThat(observer.failures).singleElement()
                .satisfies(failure -> assertThat(failure).hasMessageContaining("endpoint-a"));
    }

    private SliceCompleted event() {
        return new SliceCompleted(
                ControlEventMetadata.withoutCausation(
                        "event-1", SliceCompleted.EVENT_TYPE, SliceCompleted.EVENT_VERSION,
                        Instant.parse("2026-06-28T00:00:00Z"), "corr-1"),
                "reputation",
                "slice-1",
                "20260628T000000Z__slice-1",
                "a".repeat(64));
    }

    private static final class RecordingPublisher implements ArtifactPublishUseCase {
        private final List<PublishCompletedSliceCommand> commands = new ArrayList<>();

        @Override
        public ArtifactPublishResult reconcile(ArtifactPublishCommand command) {
            throw new UnsupportedOperationException("reconcile is not used by listener");
        }

        @Override
        public ArtifactPublishResult publish(ArtifactPublishCommand command) {
            throw new UnsupportedOperationException("full publish is not used by listener");
        }

        @Override
        public ArtifactPublishResult publishCompletedSlice(PublishCompletedSliceCommand command) {
            commands.add(command);
            return new ArtifactPublishResult(0, 1, 0, 0);
        }
    }

    private static final class RecordingObserver implements ControlEventObserver {
        private final List<ControlEvent> dispatching = new ArrayList<>();
        private final List<RuntimeException> failures = new ArrayList<>();

        @Override
        public void published(ControlEvent event) {
        }

        @Override
        public void publishFailed(ControlEvent event, RuntimeException failure) {
        }

        @Override
        public void dispatching(ControlEvent event, String handlerName) {
            dispatching.add(event);
        }

        @Override
        public void dispatchFailed(ControlEvent event, String handlerName, RuntimeException failure) {
            failures.add(failure);
        }
    }

    private static final class DirectKeyedExecutor implements KeyedSerialExecutor {
        @Override
        public WorkAdmission submit(WorkKey key, Runnable work) {
            work.run();
            return WorkAdmission.accepted(key, 0);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public boolean awaitTermination(java.time.Duration timeout) {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static final class RejectingKeyedExecutor implements KeyedSerialExecutor {
        @Override
        public WorkAdmission submit(WorkKey key, Runnable work) {
            return WorkAdmission.rejected(key, 0);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public boolean awaitTermination(java.time.Duration timeout) {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
