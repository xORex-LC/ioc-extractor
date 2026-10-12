package com.iocextractor.adapter.out.store.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Child JVM stopped by its parent at a durable boundary, without unwinding owned resources. */
public final class StagedPublicationCrashProbe {
    private StagedPublicationCrashProbe() { }

    /**
     * Opens native databases and parks at the parent's requested durable boundary.
     * @param arguments workspace root, publication phase, zero-based chunk ordinal and optional "ack" operation
     * @throws Exception if fixture setup, file signalling or bounded coordination fails
     */
    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]);
        var boundary = JdbcPublicationObserver.Phase.valueOf(arguments[1]);
        int ordinal = Integer.parseInt(arguments[2]);
        try (var fixture = new StagedPublicationFixture(root, true, (phase, attempt, chunk) -> {
            // The seed observation must complete normally before arming the requested operation.
            if (Files.exists(root.resolve("armed")) && phase == boundary && chunk == ordinal) {
                try {
                    Files.writeString(root.resolve("reached"), phase + ":" + attempt + ":" + chunk);
                    if (!new CountDownLatch(1).await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Parent did not terminate the child at its crash boundary");
                    }
                } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }, arguments.length >= 5 ? JdbcTypedJournal.valueOf(arguments[4]) : JdbcTypedJournal.ROW_POINTER,
                arguments.length >= 6 ? JdbcReceiptPayloadLayout.valueOf(arguments[5]) : JdbcReceiptPayloadLayout.VERSIONED)) {
            boolean ack = arguments.length >= 4 && arguments[3].equals("ack");
            fixture.seed();
            boolean document = arguments.length >= 4 && arguments[3].equals("document");
            if (!ack && !document) { fixture.stageAndRegister(); }
            Files.writeString(root.resolve("armed"), "ready");
            if (ack) {
                fixture.backend.directProjections().acknowledge(new com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement(
                        "masks", fixture.backend.directProjections().load("masks").requiredGeneration()));
            } else if (document) {
                fixture.backend.confirmations().confirm(fixture.document("crash-document", "crash.example", "crash-source"));
            } else { fixture.promoteHead(); }
            throw new IllegalStateException("Crash boundary was not reached");
        }
    }
}
