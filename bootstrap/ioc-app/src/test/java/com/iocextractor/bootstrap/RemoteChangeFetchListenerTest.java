package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.FetchRemoteObjectsCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.port.in.sync.RemoteFetchUseCase;
import com.iocextractor.application.sync.RemoteChangeBatchDetected;
import com.iocextractor.application.sync.RemoteObject;
import com.iocextractor.observability.LogField;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.ControlEventObserver;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteChangeFetchListenerTest {

    @Test
    void submitsDetectedBatchToEndpointKeyedFetchCommand() {
        RecordingFetcher fetcher = new RecordingFetcher();
        RecordingKeyedExecutor executor = new RecordingKeyedExecutor();
        RecordingObserver observer = new RecordingObserver();
        SyncHealthState healthState = new SyncHealthState(java.time.Clock.systemUTC());
        RemoteChangeFetchListener listener = new RemoteChangeFetchListener(
                fetcher, executor, observer, healthState);
        RemoteChangeBatchDetected event = event();

        listener.onRemoteChangeBatchDetected(event);

        assertThat(executor.keys).containsExactly(WorkKey.of("endpoint-a"));
        assertThat(fetcher.commands).singleElement().satisfies(command -> {
            assertThat(command.source()).isEqualTo("source-a");
            assertThat(command.endpoint()).isEqualTo("endpoint-a");
            assertThat(command.remotePath()).isEqualTo("/remote");
            assertThat(command.objects()).containsExactlyElementsOf(event.objects());
            assertThat(command.dryRun()).isFalse();
        });
        assertThat(observer.dispatching).containsExactly(event);
        assertThat(observer.failures).isEmpty();
        assertThat(fetcher.mdcSnapshots).singleElement().satisfies(mdc -> assertThat(mdc)
                .containsEntry(LogField.IOC_RUN_ID.key(), "corr-1")
                .containsEntry(LogField.IOC_EVENT_ID.key(), "event-1")
                .containsEntry(LogField.IOC_EVENT_TYPE.key(), RemoteChangeBatchDetected.EVENT_TYPE)
                .containsEntry(LogField.IOC_EVENT_HANDLER.key(), "RemoteChangeFetchListener")
                .containsEntry(LogField.IOC_SOURCE_ID.key(), "source-a")
                .containsEntry(LogField.IOC_SYNC_ENDPOINT.key(), "endpoint-a"));
        assertThat(healthState.fetchSnapshots().get("source-a"))
                .extracting(snapshot -> snapshot.fetched(),
                        snapshot -> snapshot.skipped(),
                        snapshot -> snapshot.failed())
                .containsExactly(2, 0, 0);
        assertThat(MDC.get(LogField.IOC_EVENT_ID.key())).isNull();
    }

    @Test
    void rejectedAdmissionIsObservedWithoutThrowingToPublisherThread() {
        RecordingFetcher fetcher = new RecordingFetcher();
        RecordingObserver observer = new RecordingObserver();
        SyncHealthState healthState = new SyncHealthState(java.time.Clock.systemUTC());
        RemoteChangeFetchListener listener = new RemoteChangeFetchListener(
                fetcher, new RejectingKeyedExecutor(), observer, healthState);

        listener.onRemoteChangeBatchDetected(event());

        assertThat(fetcher.commands).isEmpty();
        assertThat(observer.failures).singleElement()
                .satisfies(failure -> assertThat(failure).hasMessageContaining("endpoint-a"));
        assertThat(healthState.fetchSnapshots().get("source-a").error())
                .contains("remote fetch work rejected");
    }

    private RemoteChangeBatchDetected event() {
        return new RemoteChangeBatchDetected(
                ControlEventMetadata.withoutCausation(
                        "event-1",
                        RemoteChangeBatchDetected.EVENT_TYPE,
                        RemoteChangeBatchDetected.EVENT_VERSION,
                        Instant.parse("2026-06-28T00:00:00Z"),
                        "corr-1"),
                "source-a",
                "endpoint-a",
                "/remote",
                List.of(
                        new RemoteObject("/remote/a.htm", 1, Instant.parse("2026-06-28T00:00:00Z")),
                        new RemoteObject("/remote/b.htm", 2, Instant.parse("2026-06-28T00:00:01Z"))));
    }

    private static final class RecordingFetcher implements RemoteFetchUseCase {
        private final List<FetchRemoteObjectsCommand> commands = new ArrayList<>();
        private final List<Map<String, String>> mdcSnapshots = new ArrayList<>();

        @Override
        public RemoteFetchResult fetch(RemoteFetchCommand command) {
            throw new UnsupportedOperationException("manual fetch is not used by listener");
        }

        @Override
        public RemoteFetchResult fetch(FetchRemoteObjectsCommand command) {
            commands.add(command);
            mdcSnapshots.add(new LinkedHashMap<>(MDC.getCopyOfContextMap()));
            return new RemoteFetchResult(command.objects().size(), 0, 0);
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

    private static final class RecordingKeyedExecutor implements KeyedSerialExecutor {
        private final List<WorkKey> keys = new ArrayList<>();

        @Override
        public WorkAdmission submit(WorkKey key, Runnable work) {
            keys.add(key);
            work.run();
            return WorkAdmission.accepted(key, 0);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public boolean awaitTermination(Duration timeout) {
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
        public boolean awaitTermination(Duration timeout) {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
