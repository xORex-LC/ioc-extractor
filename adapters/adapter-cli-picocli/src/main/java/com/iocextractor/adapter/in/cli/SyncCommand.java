package com.iocextractor.adapter.in.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Parent command for explicit remote fetch and artifact publish operations. */
@Component
@Command(
        name = "sync",
        mixinStandardHelpOptions = true,
        description = "Synchronize IOC inputs and completed export slices with remote storage.",
        subcommands = {SyncFetchCommand.class, SyncPublishCommand.class, SyncAllCommand.class})
public final class SyncCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
