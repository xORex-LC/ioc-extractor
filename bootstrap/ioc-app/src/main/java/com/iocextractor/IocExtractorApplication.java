package com.iocextractor;

import com.iocextractor.adapter.in.cli.EarlyCliLauncher;
import com.iocextractor.bootstrap.IocYamlSyntaxCheck;
import com.iocextractor.bootstrap.RuntimeMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.io.PrintWriter;
import java.util.OptionalInt;

/**
 * Application entry point. Lightweight Picocli paths complete before Spring starts;
 * application commands are then driven from {@code CliRunner}. The process exit code
 * is taken from the selected path or Spring's {@code ExitCodeGenerator}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IocExtractorApplication {

    public static void main(String[] args) {
        OptionalInt syntaxCheckExit = IocYamlSyntaxCheck.executeIfRequested(
                args,
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
        if (syntaxCheckExit.isPresent()) {
            System.exit(syntaxCheckExit.getAsInt());
            return;
        }

        OptionalInt earlyExit = new EarlyCliLauncher(
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true))
                .executeIfHandled(args);
        if (earlyExit.isPresent()) {
            System.exit(earlyExit.getAsInt());
            return;
        }

        var context = SpringApplication.run(IocExtractorApplication.class, args);
        RuntimeMode mode = RuntimeMode.parse(context.getEnvironment()
                .getProperty("ioc.runtime.mode", RuntimeMode.ONESHOT_VALUE));
        if (!mode.isDaemon()) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
