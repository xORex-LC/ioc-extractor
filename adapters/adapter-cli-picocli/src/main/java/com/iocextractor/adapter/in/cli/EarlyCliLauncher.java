package com.iocextractor.adapter.in.cli;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Handles CLI-only paths before the Spring application context is created.
 *
 * <p>The parser model is derived from the real annotated command classes, then detached from
 * their field bindings. This keeps help and validation aligned with the Spring-managed command
 * graph while creating only dependency-free command shells, never application dependencies.</p>
 */
public final class EarlyCliLauncher {

    private static final String DEFAULT_HEALTH_HOST = "127.0.0.1";
    private static final String DEFAULT_HEALTH_PORT = "8081";

    private final PrintWriter out;
    private final PrintWriter err;
    private final Supplier<ApplicationBuildInfo> buildInfo;

    public EarlyCliLauncher(PrintWriter out, PrintWriter err) {
        this(out, err, new ApplicationBuildInfoReader()::read);
    }

    EarlyCliLauncher(
            PrintWriter out,
            PrintWriter err,
            Supplier<ApplicationBuildInfo> buildInfo) {
        this.out = out;
        this.err = err;
        this.buildInfo = buildInfo;
    }

    /**
     * Runs a lightweight CLI path when possible.
     *
     * @return an exit code when Spring startup can be skipped; otherwise empty
     */
    public OptionalInt executeIfHandled(String... args) {
        if (args.length == 0 || requestsDaemonMode(args)) {
            return OptionalInt.empty();
        }

        CommandLine metadata = metadataCommandLine();
        ParseResult parsed;
        try {
            parsed = metadata.parseArgs(args);
        } catch (CommandLine.ParameterException ignored) {
            return OptionalInt.of(metadata.execute(args));
        }

        if (helpRequested(parsed)) {
            return OptionalInt.of(metadata.execute(args));
        }

        List<CommandLine> path = parsed.asCommandLineList();
        String leaf = path.get(path.size() - 1).getCommandName();
        if ("health".equals(leaf)) {
            return OptionalInt.of(healthCommandLine().execute(args));
        }
        if ("sync".equals(leaf)) {
            path.get(path.size() - 1).usage(out);
            return OptionalInt.of(0);
        }
        return OptionalInt.empty();
    }

    private CommandLine metadataCommandLine() {
        CommandLine commandLine = new CommandLine(IocRootCommand.class, commandFactory());
        detachBindings(commandLine.getCommandSpec());
        configureWriters(commandLine);
        commandLine.setExecutionExceptionHandler((failure, failedCommand, ignored) -> {
            failedCommand.getErr().println("ioc: " + failure.getMessage());
            return failedCommand.getCommandSpec().exitCodeOnExecutionException();
        });
        return commandLine;
    }

    private boolean helpRequested(ParseResult parsed) {
        ParseResult current = parsed;
        while (current != null) {
            if (current.isUsageHelpRequested() || current.isVersionHelpRequested()) {
                return true;
            }
            current = current.subcommand();
        }
        return false;
    }

    private CommandLine healthCommandLine() {
        CommandLine commandLine = new CommandLine(IocRootCommand.class, commandFactory());
        configureWriters(commandLine);
        return commandLine;
    }

    private CommandLine.IFactory commandFactory() {
        CommandLine.IFactory defaultFactory = CommandLine.defaultFactory();
        return new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                if (type == BuildInfoVersionProvider.class) {
                    return type.cast(new BuildInfoVersionProvider(buildInfo));
                }
                if (type == HealthCommand.class) {
                    return type.cast(new HealthCommand(
                            runtimeSetting("server.address", "SERVER_ADDRESS", DEFAULT_HEALTH_HOST),
                            runtimeSetting("server.port", "SERVER_PORT", DEFAULT_HEALTH_PORT)));
                }
                if (type == ExtractCommand.class) {
                    return type.cast(new ExtractCommand(null, "oneshot"));
                }
                if (type == ExportCommand.class) {
                    return type.cast(new ExportCommand(null, null, "oneshot"));
                }
                if (type == ImportValidateCommand.class) {
                    return type.cast(new ImportValidateCommand());
                }
                if (type == ImportStatusCommand.class) {
                    return type.cast(new ImportStatusCommand());
                }
                if (type == ImportReplayCommand.class) {
                    return type.cast(new ImportReplayCommand());
                }
                if (type == SyncFetchCommand.class) {
                    return type.cast(new SyncFetchCommand(null, null));
                }
                if (type == SyncPublishCommand.class) {
                    return type.cast(new SyncPublishCommand(null, null));
                }
                if (type == SyncAllCommand.class) {
                    return type.cast(new SyncAllCommand(null, null, null));
                }
                return defaultFactory.create(type);
            }
        };
    }

    private void detachBindings(CommandSpec command) {
        for (OptionSpec option : new ArrayList<>(command.options())) {
            command.remove(option);
            command.addOption(option.toBuilder()
                    .getter(new CommandLine.Model.IGetter() {
                        @Override
                        public <T> T get() {
                            return null;
                        }
                    })
                    .setter(new CommandLine.Model.ISetter() {
                        @Override
                        public <T> T set(T value) {
                            return null;
                        }
                    })
                    .initialValue(null)
                    .hasInitialValue(false)
                    .build());
        }
        Set<CommandLine> subcommands = new LinkedHashSet<>(command.subcommands().values());
        subcommands.forEach(subcommand -> detachBindings(subcommand.getCommandSpec()));
    }

    private void configureWriters(CommandLine commandLine) {
        commandLine.setOut(out);
        commandLine.setErr(err);
        new LinkedHashSet<>(commandLine.getSubcommands().values())
                .forEach(this::configureWriters);
    }

    private boolean requestsDaemonMode(String[] args) {
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if ("--ioc.runtime.mode=daemon".equalsIgnoreCase(argument)) {
                return true;
            }
            if ("--ioc.runtime.mode".equalsIgnoreCase(argument)
                    && index + 1 < args.length
                    && "daemon".equalsIgnoreCase(args[index + 1])) {
                return true;
            }
        }
        return false;
    }

    private String runtimeSetting(String systemProperty, String environmentVariable, String fallback) {
        String configured = System.getProperty(systemProperty);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(environmentVariable);
        }
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }
}
