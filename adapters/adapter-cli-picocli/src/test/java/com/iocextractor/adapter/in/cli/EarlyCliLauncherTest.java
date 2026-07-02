package com.iocextractor.adapter.in.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class EarlyCliLauncherTest {

    private StringWriter out;
    private StringWriter err;
    private EarlyCliLauncher launcher;

    @BeforeEach
    void setUp() {
        out = new StringWriter();
        err = new StringWriter();
        launcher = new EarlyCliLauncher(new PrintWriter(out, true), new PrintWriter(err, true));
    }

    @Test
    void handlesRootHelpWithoutSpringGraph() {
        OptionalInt result = launcher.executeIfHandled("--help");

        assertThat(result).hasValue(0);
        assertThat(out.toString())
                .contains("Usage: ioc")
                .contains("extract", "export", "sync", "health");
    }

    @Test
    void handlesNestedHelpAfterBoundOptionsWithoutCreatingCommand() {
        OptionalInt result = launcher.executeIfHandled(
                "sync", "fetch", "--source", "incoming", "--help");

        assertThat(result).hasValue(0);
        assertThat(out.toString())
                .contains("Usage: ioc sync fetch")
                .contains("--source");
    }

    @Test
    void handlesIncompleteCommandAndPrintsItsUsage() {
        OptionalInt result = launcher.executeIfHandled("sync", "fetch", "--source");

        assertThat(result).hasValue(2);
        assertThat(err.toString())
                .contains("Missing required parameter")
                .contains("Usage: ioc sync fetch");
    }

    @Test
    void handlesParentSyncUsage() {
        OptionalInt result = launcher.executeIfHandled("sync");

        assertThat(result).hasValue(0);
        assertThat(out.toString()).contains("Usage: ioc sync");
    }

    @Test
    void delegatesValidBusinessCommandToSpring() {
        OptionalInt result = launcher.executeIfHandled("extract", "--source", "source.htm");

        assertThat(result).isEmpty();
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void delegatesDaemonLaunchToSpring() {
        OptionalInt result = launcher.executeIfHandled("--ioc.runtime.mode=daemon");

        assertThat(result).isEmpty();
    }
}
