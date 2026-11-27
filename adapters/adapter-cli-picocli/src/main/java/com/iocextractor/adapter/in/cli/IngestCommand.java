package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentAdmissionOperations;
import com.iocextractor.application.ingest.admission.DocumentExecutionState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.Optional;

/** Inspects and resumes ordered document admissions through the durable journal. */
@Component
@Command(name = "ingest", mixinStandardHelpOptions = true, description = "Operate document ingestion admissions.")
public final class IngestCommand implements Runnable {
    private final ObjectProvider<DocumentAdmissionOperations> operations;

    @Spec
    private CommandSpec spec;

    /** Creates a command shell for lightweight help. */
    public IngestCommand() {
        this(null);
    }

    @Autowired
    public IngestCommand(ObjectProvider<DocumentAdmissionOperations> operations) {
        this.operations = operations;
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "status", mixinStandardHelpOptions = true,
            description = "Show a document admission, defaulting to the head of the queue.")
    int status(@Option(names = "--observation", paramLabel = "ID") String id) {
        var current = operations.getObject().status(Optional.ofNullable(id).map(ObservationId::new));
        if (current.isPresent()) {
            print(current.orElseThrow());
            return 0;
        }
        if (id == null) {
            spec.commandLine().getOut().println("No pending document admission.");
            return 0;
        }
        spec.commandLine().getErr().println("Document admission was not found.");
        return 1;
    }

    @Command(name = "resume", mixinStandardHelpOptions = true,
            description = "Request a retry of one held document at the specified status version.")
    int resume(@Option(names = "--observation", paramLabel = "ID", required = true) String id,
               @Option(names = "--at-version", required = true) long version) {
        try {
            print(operations.getObject().resume(new ObservationId(id), version));
            return 0;
        } catch (IllegalStateException conflict) {
            spec.commandLine().getErr().println("Document is not held at that version. Check ingest status.");
            return 1;
        }
    }

    private void print(DocumentAdmission value) {
        String code = Optional.ofNullable(value.execution().failure()).filter(DocumentExecutionState.Failure::isKnownCode)
                .or(() -> value.rejection().map(intent -> intent.code().name())).orElse("-");
        spec.commandLine().getOut().printf(
                "Observation: %s%nVersion: %d%nPhase: %s%nHeld: %s%nAttempts: %d%nCode: %s%nOutcome: %s%n",
                value.observationId().value(), value.version(), value.phase(),
                value.execution().retryAfter().equals(DocumentExecutionState.HELD),
                value.execution().attempts(), code, value.terminalOutcome().map(Enum::name).orElse("-"));
    }
}
