package com.iocextractor.adapter.out.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iocextractor.IocExtractorApplication;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.tck.junit.EndToEndTest;
import com.iocextractor.platform.diagnostics.DiagnosticFactory;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.xml.sax.ContentHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EndToEndTest
@Timeout(60)
class SourceFailureLoggingIT {

    private static final String READ_FAILED = "SOURCE.READ_FAILED";
    private static final String CONTROLLED_REASON = "document parsing or text streaming failed";
    private static final String PARSER_DETAIL = "synthetic-parser-message";
    private static final String CAUSE_DETAIL = "synthetic-nested-message";
    private static final String SUPPRESSED_DETAIL = "synthetic-suppressed-message";

    @TempDir
    Path directory;

    @Test
    void extractReportsTheFailureThroughConsoleAndCliStderr() throws Exception {
        Files.writeString(directory.resolve("sample.html"), "<p>example.test</p>");

        launch(1, "extract", "--source", "sample.html");

        String console = readSafeOutput(directory.resolve("stdout.log"));
        assertThat(console).contains(CONTROLLED_REASON, "stage failed", "command failed");
        String stderr = readSafeOutput(directory.resolve("stderr.log"));
        assertThat(stderr).contains(READ_FAILED, CONTROLLED_REASON);
    }

    @Test
    void daemonRetriesAndRejectsWithSafeConsoleEcsAndDurableReasons() throws Exception {
        Path inbox = Files.createDirectories(directory.resolve("var/inbox"));
        Files.writeString(inbox.resolve("sample.html"), "<p>example.test</p>");

        launch(0);

        assertThat(readSafeOutput(directory.resolve("stdout.log"))).contains(CONTROLLED_REASON, "stage failed");
        readSafeOutput(directory.resolve("stderr.log"));
        String ecs = readSafeOutput(directory.resolve("var/logs/ioc-extractor.ecs.json"));
        var mapper = new ObjectMapper();
        boolean stageFailureFound = false;
        int sourceFailures = 0;
        for (String line : ecs.lines().toList()) {
            var event = mapper.readTree(line);
            assertSafeText(event.toString());
            String message = event.path("message").asText();
            if (event.at("/ioc/diagnostic/code").asText().equals(READ_FAILED)) {
                assertThat(message).contains(CONTROLLED_REASON);
                assertThat(event.at("/ioc/stage").asText()).isEqualTo("READ_SOURCE");
                assertThat(event.at("/ioc/run/id").asText()).isNotBlank();
                assertThat(event.at("/ioc/source/path").asText()).contains("sample.html");
                sourceFailures++;
            }
            if (message.equals("stage failed")) {
                assertThat(event.at("/error/stack_trace").asText()).contains(READ_FAILED, CONTROLLED_REASON);
                stageFailureFound = true;
            }
        }
        assertThat(sourceFailures).isEqualTo(2);
        assertThat(stageFailureFound).isTrue();

        try (var connection = DriverManager.getConnection(serviceDb(directory));
             var statement = connection.createStatement();
             var ledger = statement.executeQuery("""
                     SELECT processing_path, archived_path, reason
                     FROM ingestion_ledger WHERE status = 'FAILED'
                     """)) {
            assertThat(ledger.next()).isTrue();
            Path archived = Path.of(ledger.getString("archived_path"));
            assertThat(Path.of(ledger.getString("processing_path"))).doesNotExist();
            assertThat(archived).hasContent("<p>example.test</p>");
            assertThat(archived.resolveSibling(archived.getFileName() + ".error"))
                    .hasContent("DOCUMENT_PREPARATION_FAILED\n");
            assertThat(ledger.getString("reason")).isEqualTo("DOCUMENT_PREPARATION_FAILED");
            assertThat(ledger.next()).isFalse();
        }

        try (var connection = DriverManager.getConnection(serviceDb(directory));
             var statement = connection.createStatement();
             var runs = statement.executeQuery("SELECT reason FROM ingest_run WHERE status = 'FAILED'")) {
            int failedRuns = 0;
            while (runs.next()) {
                String reason = runs.getString("reason");
                assertThat(reason).contains(READ_FAILED, CONTROLLED_REASON);
                assertSafeText(reason);
                failedRuns++;
            }
            assertThat(failedRuns).isEqualTo(2);
        }
    }

    private void launch(int expectedExit, String... arguments) throws Exception {
        Files.createDirectories(directory.resolve("var/db"));
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), SourceFailureLoggingIT.class.getName()));
        command.addAll(List.of(arguments));
        Path stdout = directory.resolve("stdout.log");
        Path stderr = directory.resolve("stderr.log");
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().keySet().removeIf(name -> name.startsWith("SPRING_") || name.startsWith("IOC_")
                || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(name));
        var process = builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertThat(process.waitFor(40, TimeUnit.SECONDS)).as("source failure test JVM terminates").isTrue();
            assertThat(process.exitValue()).as("%s%n%s", Files.readString(stdout), Files.readString(stderr))
                    .isEqualTo(expectedExit);
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(3, TimeUnit.SECONDS)).as("source failure test JVM cleanup").isTrue();
        }
    }

    private static String readSafeOutput(Path path) throws IOException {
        String output = Files.readString(path);
        assertSafeText(output);
        return output;
    }

    private static void assertSafeText(String output) {
        assertThat(output).doesNotContain(PARSER_DETAIL, CAUSE_DETAIL, SUPPRESSED_DETAIL);
    }

    public static void main(String[] arguments) {
        var application = new SpringApplication(IocExtractorApplication.class, FailingReaderConfiguration.class);
        boolean daemon = arguments.length == 0;
        String[] startupArgs = daemon ? new String[]{
                "--ioc.runtime.mode=daemon",
                "--ioc.observability.mode=daemon",
                "--ioc.ingestion.detect.reconcile-interval=100ms",
                "--ioc.ingestion.stability.quiet-period=0ms",
                "--ioc.ingestion.retry.backoff=50ms",
                "--ioc.ingestion.retry.max-attempts=2",
                    "--ioc.ingestion.ledger.type=jdbc",
                "--server.port=0"
        } : arguments;
        try (var context = application.run(startupArgs)) {
            if (daemon) {
                await().atMost(Duration.ofSeconds(15)).until(SourceFailureLoggingIT::rejectedAfterRetry);
            }
            System.exit(SpringApplication.exit(context));
        }
    }

    private static boolean rejectedAfterRetry() throws Exception {
        try (var connection = DriverManager.getConnection(serviceDb(Path.of(".")));
             var statement = connection.createStatement();
             var admissions = statement.executeQuery("""
                     SELECT execution_attempts FROM document_admission WHERE terminal_outcome = 'REJECTED'
                     """)) {
            return admissions.next() && admissions.getInt("execution_attempts") == 2;
        }
    }

    private static String serviceDb(Path root) {
        return "jdbc:sqlite:" + root.resolve("var/db/ioc-service.db");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingReaderConfiguration {

        @Bean
        @Primary
        SourceReader testSourceReader() {
            return new TikaSourceReader(new FailingParser(), null, new DiagnosticFactory(Clock.systemUTC()));
        }
    }

    private static final class FailingParser implements Parser {

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of();
        }

        @Override
        public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
                throws TikaException {
            var failure = new TikaException(PARSER_DETAIL, new IOException(CAUSE_DETAIL));
            failure.addSuppressed(new IOException(SUPPRESSED_DETAIL));
            throw failure;
        }
    }
}
