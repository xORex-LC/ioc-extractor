package com.iocextractor.bootstrap;

import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.observation.ManagedImportObservationAdmission;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded retention for terminal registration handshakes and unreferenced ranks. */
final class ObservationRegistrationRetentionScheduler implements SmartLifecycle {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final Logger log = LoggerFactory.getLogger(
            ObservationRegistrationRetentionScheduler.class);
    private static final int BATCH_SIZE = 1_000;

    private final ObservationRegistrationStore registrations;
    private final DocumentAdmissionService documents;
    private final ManagedImportObservationAdmission imports;
    private final Clock clock;
    private final Duration retention;
    private final Duration interval;
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private volatile boolean active;
    private volatile ScheduledExecutorService executor;

    ObservationRegistrationRetentionScheduler(
            ObservationRegistrationStore registrations,
            DocumentAdmissionService documents,
            ManagedImportObservationAdmission imports,
            Clock clock,
            Duration retention,
            Duration interval) {
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.documents = documents;
        this.imports = imports;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = positive(retention, "retention");
        this.interval = positive(interval, "interval");
    }

    @Override
    public synchronized void start() {
        if (active) {
            return;
        }
        active = true;
        runOnce();
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("ioc-observation-retention-").daemon(true).factory());
        executor.scheduleWithFixedDelay(
                this::runOnce, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        active = false;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    void runOnce() {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            var cutoff = clock.instant().minus(retention);
            int purged = registrations.purgeTerminalOneshotBefore(cutoff, BATCH_SIZE);
            if (documents != null) {
                purged += documents.purgeTerminalBefore(cutoff, BATCH_SIZE);
            }
            if (imports != null) {
                purged += imports.purgeTerminalBefore(cutoff, BATCH_SIZE);
            }
            if (purged > 0) {
                LogEvents.info(log)
                        .action(EventAction.RETENTION_SWEEP)
                        .outcome(EventOutcome.SUCCESS)
                        .field(LogField.IOC_ROWS, purged)
                        .message("observation registration retention purged " + purged)
                        .log();
            }
        } catch (RuntimeException failure) {
            LogEvents.error(log)
                    .action(EventAction.RETENTION_SWEEP)
                    .outcome(EventOutcome.FAILURE)
                    .message("observation registration retention failed")
                    .log(failure);
        } finally {
            sweeping.set(false);
        }
    }

    private Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
