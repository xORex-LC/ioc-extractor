package com.iocextractor.bootstrap;

import com.iocextractor.application.export.SliceCompleted;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.port.in.sync.PublishCompletedSliceCommand;
import com.iocextractor.application.sync.PublishTarget;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import com.iocextractor.platform.events.ControlEventObserver;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Objects;

/** Spring listener that turns slice-completed facts into per-target publish commands. */
public final class SliceCompletedPublishListener {

    private static final String HANDLER = "SliceCompletedPublishListener";

    private final ArtifactPublishUseCase publisher;
    private final KeyedSerialExecutor executor;
    private final ControlEventObserver observer;
    private final List<PublishTarget> targets;

    public SliceCompletedPublishListener(ArtifactPublishUseCase publisher,
                                         KeyedSerialExecutor executor,
                                         ControlEventObserver observer,
                                         List<PublishTarget> targets) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
    }

    @EventListener
    public void onSliceCompleted(SliceCompleted event) {
        Objects.requireNonNull(event, "event");
        for (PublishTarget target : targetsFor(event.profile())) {
            submit(event, target);
        }
    }

    private List<PublishTarget> targetsFor(String profile) {
        return targets.stream()
                .filter(target -> target.exportProfile().equals(profile))
                .toList();
    }

    private void submit(SliceCompleted event, PublishTarget target) {
        WorkAdmission admission = executor.submit(WorkKey.of(target.endpoint()), () -> publish(event, target));
        if (!admission.accepted()) {
            observer.dispatchFailed(event, HANDLER, new IllegalStateException(
                    "slice publish work rejected for endpoint " + target.endpoint()));
        }
    }

    private void publish(SliceCompleted event, PublishTarget target) {
        observer.dispatching(event, HANDLER);
        try {
            publisher.publishCompletedSlice(new PublishCompletedSliceCommand(
                    event.profile(),
                    event.sliceId(),
                    event.sliceName(),
                    target.targetId(),
                    target.endpoint(),
                    event.metadata().correlationId(),
                    event.metadata().eventId()));
        } catch (RuntimeException failure) {
            observer.dispatchFailed(event, HANDLER, failure);
            throw failure;
        }
    }
}
