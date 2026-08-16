package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.CanonicalDeadlineScheduleChanged;
import com.iocextractor.application.artifact.lifecycle.MutableArtifactProjectionRequired;
import com.iocextractor.platform.events.ControlEventObserver;
import org.springframework.context.event.EventListener;

import java.util.Objects;

/** Converts post-commit lifecycle hints into coalesced scheduler nudges. */
public final class LifecycleControlEventListener {

    private static final String HANDLER = "LifecycleControlEventListener";
    private final LifecycleDeadlineScheduler deadlines;
    private final LifecycleProjectionScheduler projections;
    private final ControlEventObserver observer;

    public LifecycleControlEventListener(LifecycleDeadlineScheduler deadlines,
                                         LifecycleProjectionScheduler projections,
                                         ControlEventObserver observer) {
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @EventListener
    public void onDeadlineChanged(CanonicalDeadlineScheduleChanged event) {
        observer.dispatching(Objects.requireNonNull(event, "event"), HANDLER);
        deadlines.nudge();
    }

    @EventListener
    public void onProjectionRequired(MutableArtifactProjectionRequired event) {
        observer.dispatching(Objects.requireNonNull(event, "event"), HANDLER);
        projections.nudge();
    }
}
