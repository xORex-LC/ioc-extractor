package com.iocextractor.adapter.in.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Bridges Spring Boot's lifecycle to picocli: builds the command with the
 * Spring {@link IFactory} (so commands get their dependencies injected) and
 * propagates picocli's exit code to the process.
 */
@Component
@ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "oneshot", matchIfMissing = true)
public final class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private final IocRootCommand command;
    private final IFactory factory;
    private int exitCode;

    public CliRunner(IocRootCommand command, IFactory factory) {
        this.command = command;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        this.exitCode = new CommandLine(command, factory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
