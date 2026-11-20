package qualification.logging;

import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Runs only this research fixture, outside the reactor's suite universe. */
public final class QualificationMain {
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the reviewed case count");
        }
        long expectedTests = Long.parseLong(args[0]);
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(GenericLoggingProbeTest.class), selectClass(EcsBackendProbeTest.class))
                .build();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary = listener.getSummary();
        var writer = new PrintWriter(System.out, true);
        summary.printTo(writer);
        summary.printFailuresTo(writer);
        if (summary.getTestsFoundCount() != expectedTests || summary.getTestsFailedCount() > 0
                || summary.getTestsAbortedCount() > 0 || summary.getTestsSkippedCount() > 0
                || summary.getTestsSucceededCount() != summary.getTestsFoundCount()
                || summary.getContainersFailedCount() > 0) {
            System.exit(1);
        }
    }
}
