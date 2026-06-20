package com.iocextractor.adapter.in.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Root CLI command. Holds sub-commands so the tool can grow (e.g. {@code extract},
 * future {@code verify}, {@code export}) without changing existing ones. With no
 * sub-command it prints usage.
 */
@Component
@Command(
        name = "ioc",
        mixinStandardHelpOptions = true,
        description = "IOC extraction toolkit.",
        subcommands = {ExtractCommand.class})
public final class IocRootCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }
}
