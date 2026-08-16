package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.in.artifact.lifecycle.ReplayConfirmationReceiptUseCase;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.application.port.out.artifact.lifecycle.ConfirmationReceiptStore;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/** Replays only complete, current-policy receipts through the canonical writer. */
public final class ConfirmationReceiptReplayService implements ReplayConfirmationReceiptUseCase {

    private final ConfirmationReceiptStore receipts;
    private final CanonicalArtifactWriter writer;
    private final LifecycleTimeSource timeSource;

    public ConfirmationReceiptReplayService(ConfirmationReceiptStore receipts,
                                            CanonicalArtifactWriter writer,
                                            LifecycleTimeSource timeSource) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    @Override
    public Optional<ConfirmationReceiptReplayResult> replay(ConfirmationReceiptReplayCommand command) {
        Objects.requireNonNull(command, "command");
        LifecycleWriteContext context = command.writeContext();
        EffectiveTime asOf = timeSource.now();
        return receipts.findComplete(
                        context.sourceKey(), context.receipt().processingPolicyFingerprint(), asOf)
                .map(receipt -> replay(receipt, context));
    }

    private ConfirmationReceiptReplayResult replay(ConfirmationReceiptSnapshot receipt,
                                                    LifecycleWriteContext context) {
        var outcomes = new LinkedHashMap<String, LifecycleWriteResult>();
        for (ConfirmationReceiptArtifact artifact : receipt.artifacts()) {
            LifecycleWriteResult outcome = writer.confirm(new CanonicalArtifactConfirmation(
                    context.observationId(),
                    context.sourceKey(),
                    context.receipt(),
                    artifact.artifactName(),
                    artifact.header(),
                    artifact.records()));
            outcomes.put(artifact.artifactName(), outcome);
        }
        return new ConfirmationReceiptReplayResult(outcomes);
    }
}
