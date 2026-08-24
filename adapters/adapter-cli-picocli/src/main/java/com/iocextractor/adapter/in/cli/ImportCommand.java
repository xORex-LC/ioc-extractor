package com.iocextractor.adapter.in.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Operator commands for managed dataframe delivery inspection and replay. */
@Component
@Command(name = "import", mixinStandardHelpOptions = true,
        description = "Validate, inspect or replay managed dataframe deliveries.",
        subcommands = {
                ImportValidateCommand.class,
                ImportStatusCommand.class,
                ImportReplayCommand.class
        })
public final class ImportCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
