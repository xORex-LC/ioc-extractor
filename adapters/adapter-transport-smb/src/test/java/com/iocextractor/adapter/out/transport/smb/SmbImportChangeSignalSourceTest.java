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

    private static final class CapturingSignalSource implements RemoteChangeSignalSource {
        private final List<Registration> registrations = new ArrayList<>();

        @Override
        public RemoteChangeWatch watch(RemoteWatchTarget target, RemoteChangeSignalHandler handler) {
            FakeWatch watch = new FakeWatch();
            registrations.add(new Registration(target, handler, watch));
            return watch;
        }
    }

    private static final class FakeWatch implements RemoteChangeWatch {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    private record Registration(
            RemoteWatchTarget target,
            RemoteChangeSignalHandler handler,
            FakeWatch watch) {
    }
}
