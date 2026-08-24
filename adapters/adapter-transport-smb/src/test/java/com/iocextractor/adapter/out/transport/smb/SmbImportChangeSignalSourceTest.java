package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.out.sync.RemoteChangeSignalHandler;
import com.iocextractor.application.port.out.sync.RemoteChangeSignalSource;
import com.iocextractor.application.port.out.sync.RemoteChangeWatch;
import com.iocextractor.application.sync.RemoteWatchTarget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmbImportChangeSignalSourceTest {

    @Test
    void mapsEveryNotificationToACompleteListingHintForItsConfiguredSource() {
        ImportSourceId first = new ImportSourceId("first-feed");
        ImportSourceId second = new ImportSourceId("second-feed");
        CapturingSignalSource watcher = new CapturingSignalSource();
        SmbImportChangeSignalSource signals = new SmbImportChangeSignalSource(
                List.of(
                        new SmbImportSourceDefinition(first, "primary", "/import/first"),
                        new SmbImportSourceDefinition(second, "primary", "/import/second")),
                watcher);
        List<ImportSourceId> received = new ArrayList<>();

        signals.start(received::add);
        watcher.registrations.get(1).handler().signal();
        watcher.registrations.get(0).handler().failed(new RuntimeException("notification lost"));

        assertThat(watcher.registrations)
                .extracting(registration -> registration.target().remotePath())
                .containsExactly("import/first", "import/second");
        assertThat(received).containsExactly(second);

        signals.close();
        assertThat(watcher.registrations)
                .allSatisfy(registration -> assertThat(registration.watch().closed).isTrue());
    }

    @Test
    void preservesStartFailureAndAttachesCleanupFailure() {
        RuntimeException startFailure = new IllegalStateException("cannot register second watch");
        RuntimeException closeFailure = new IllegalStateException("cannot close first watch");
        CapturingSignalSource watcher = new CapturingSignalSource(startFailure, closeFailure);
        SmbImportChangeSignalSource signals = new SmbImportChangeSignalSource(
                List.of(
                        definition("first-feed", "/import/first"),
                        definition("second-feed", "/import/second")),
                watcher);

        assertThatThrownBy(() -> signals.start(ignored -> { }))
                .isSameAs(startFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(closeFailure));
        assertThat(watcher.registrations.getFirst().watch().closed).isTrue();
    }

    @Test
    void closesEveryWatchAndAggregatesFailuresInReverseOwnershipOrder() {
        RuntimeException firstFailure = new IllegalStateException("cannot close first watch");
        RuntimeException secondFailure = new IllegalStateException("cannot close second watch");
        CapturingSignalSource watcher = new CapturingSignalSource(
                null, List.of(firstFailure, secondFailure));
        SmbImportChangeSignalSource signals = new SmbImportChangeSignalSource(
                List.of(
                        definition("first-feed", "/import/first"),
                        definition("second-feed", "/import/second")),
                watcher);
        signals.start(ignored -> { });

        assertThatThrownBy(signals::close)
                .isSameAs(secondFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(firstFailure));
        assertThat(watcher.registrations)
                .extracting(registration -> registration.watch().closed)
                .containsExactly(true, true);
    }

    private static SmbImportSourceDefinition definition(String sourceId, String inbox) {
        return new SmbImportSourceDefinition(new ImportSourceId(sourceId), "primary", inbox);
    }

    private static final class CapturingSignalSource implements RemoteChangeSignalSource {
        private final List<Registration> registrations = new ArrayList<>();
        private final RuntimeException registrationFailure;
        private final List<RuntimeException> closeFailures;

        private CapturingSignalSource() {
            this(null, List.of());
        }

        private CapturingSignalSource(
                RuntimeException registrationFailure,
                RuntimeException closeFailure) {
            this(registrationFailure, List.of(closeFailure));
        }

        private CapturingSignalSource(
                RuntimeException registrationFailure,
                List<RuntimeException> closeFailures) {
            this.registrationFailure = registrationFailure;
            this.closeFailures = closeFailures;
        }

        @Override
        public RemoteChangeWatch watch(RemoteWatchTarget target, RemoteChangeSignalHandler handler) {
            if (!registrations.isEmpty() && registrationFailure != null) {
                throw registrationFailure;
            }
            RuntimeException closeFailure = registrations.size() < closeFailures.size()
                    ? closeFailures.get(registrations.size())
                    : null;
            FakeWatch watch = new FakeWatch(closeFailure);
            registrations.add(new Registration(target, handler, watch));
            return watch;
        }
    }

    private static final class FakeWatch implements RemoteChangeWatch {
        private final RuntimeException closeFailure;
        private boolean closed;

        private FakeWatch(RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public void close() {
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private record Registration(
            RemoteWatchTarget target,
            RemoteChangeSignalHandler handler,
            FakeWatch watch) {
    }
}
