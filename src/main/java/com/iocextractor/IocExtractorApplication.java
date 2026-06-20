package com.iocextractor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Application entry point. Runs as a CLI (no web server); the picocli command
 * is driven from {@code CliRunner}. The process exit code is taken from the
 * command via Spring's {@code ExitCodeGenerator}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IocExtractorApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(IocExtractorApplication.class, args)));
    }
}
