package com.iocextractor.platform.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.MDC;

import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.iocextractor.platform.logging.LogCapture.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Compiles the actual guide blocks as consumers outside the library package. */
@Timeout(30)
@ResourceLock("slf4j-mdc-adapter")
class LoggingLibraryExampleTest {
    private final LogCapture capture = new LogCapture();
    @TempDir Path directory;

    @AfterEach
    void releaseAppender() {
        capture.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CollectionExample", "OmissionExample", "FailureExample"})
    void guideExampleCompilesAndProducesItsDocumentedRecord(String className) throws Exception {
        var examples = guideExamples();
        assertThat(examples).containsOnlyKeys("CollectionExample", "OmissionExample", "FailureExample");
        var source = directory.resolve(className + ".java");
        Files.writeString(source, examples.get(className));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Guide examples require a full JDK 21").isNotNull();
        var diagnostics = new StringWriter();
        try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
            boolean compiled = compiler.getTask(diagnostics, fileManager, null,
                    List.of("--release", "21", "-proc:none", "-classpath", System.getProperty("java.class.path"),
                            "-d", directory.toString()), null, fileManager.getJavaFileObjects(source)).call();
            assertThat(compiled).as(diagnostics.toString()).isTrue();
        }

        MDC.put(TASK.key(), "parent-task");
        MDC.put(TOKEN.key(), "synthetic-ambient");
        try (var consumer = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},
                getClass().getClassLoader())) {
            consumer.loadClass("examples." + className).getMethod("run", Logger.class).invoke(null, capture.logger);
        }
        assertThat(MDC.get(TASK.key())).isEqualTo("parent-task");
        assertThat(MDC.get(TOKEN.key())).isEqualTo("synthetic-ambient");
        assertThat(capture.output.list).singleElement().satisfies(record -> {
            var values = fields(record);
            switch (className) {
                case "CollectionExample" -> {
                    assertThat(values).containsExactlyInAnyOrderEntriesOf(Map.of(
                            "event.action", "collect_complete", "event.outcome", "success",
                            "event.duration", 500L, ITEMS.key(), 12L, RETRY.key(), false, TOKEN.key(), "[redacted]"));
                    assertThat(record.getMDCPropertyMap()).containsEntry(TASK.key(), "task-42")
                            .doesNotContainKey(TOKEN.key());
                }
                case "OmissionExample" -> {
                    assertThat(values).containsExactlyEntriesOf(Map.of("event.action", "collect_skipped"));
                    assertThat(record.getMDCPropertyMap()).doesNotContainKey(TOKEN.key());
                }
                case "FailureExample" -> {
                    assertThat(values).containsExactlyInAnyOrderEntriesOf(Map.of(
                            "event.action", "collect_fetch", "event.outcome", "failure",
                            "collector.request.url", "https://<redacted>@example.test/p?<redacted>#f"));
                    assertThat(record.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
                }
                default -> throw new AssertionError("Unqualified guide example: " + className);
            }
        });
    }

    private static Map<String, String> guideExamples() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("docs/guides/logging-library.md"))) {
            root = root.getParent();
        }
        assertThat(root).as("Reactor root containing the consumer guide").isNotNull();
        var document = Files.readString(root.resolve("docs/guides/logging-library.md"));
        var blocks = Pattern.compile("```java\\R(.*?)\\R```", Pattern.DOTALL).matcher(document);
        var examples = new LinkedHashMap<String, String>();
        while (blocks.find()) {
            var name = Pattern.compile("public final class (\\w+)").matcher(blocks.group(1));
            assertThat(name.find()).as("Every guide Java block must be a complete executable example").isTrue();
            assertThat(examples.put(name.group(1), blocks.group(1))).isNull();
        }
        return examples;
    }
}
