package qualification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.LoggingEventVO;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.status.Status;
import com.iocextractor.platform.logging.LogEvents;
import com.iocextractor.platform.logging.LogFieldSpec;
import com.iocextractor.platform.logging.LogValueType;
import com.iocextractor.platform.logging.ecs.EcsFields;
import examples.CollectionExample;
import examples.FailureExample;
import examples.OmissionExample;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Runs the documented application configuration without IOC classes or resources. */
public final class BootRecipeMain {
    public static void main(String[] args) throws Exception {
        requireNoIocClasses();
        var directory = Path.of(args[0]);
        var environment = new StandardEnvironment();
        var yaml = new YamlPropertySourceLoader().load("recipe",
                new FileSystemResource(directory.resolve("application.yml")));
        yaml.forEach(source -> environment.getPropertySources().addFirst(source));
        var logging = new LogbackLoggingSystem(BootRecipeMain.class.getClassLoader());
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            logging.initialize(new LoggingInitializationContext(environment),
                    directory.resolve("logback-spring.xml").toUri().toString(), null);
            run(context, args[1]);
            var failures = context.getStatusManager().getCopyOfStatusList().stream()
                    .filter(status -> status.getEffectiveLevel() >= Status.WARN)
                    .map(Object::toString).toList();
            Files.write(directory.resolve("status.txt"), failures);
        } finally {
            MDC.clear();
            logging.cleanUp();
            context.stop();
        }
    }

    private static void requireNoIocClasses() {
        for (String name : List.of("com.iocextractor.observability.LogField",
                "com.iocextractor.bootstrap.IocEcsStructuredLogEncoder")) {
            try {
                Class.forName(name, false, BootRecipeMain.class.getClassLoader());
                throw new AssertionError("IOC class leaked into recipe qualification: " + name);
            } catch (ClassNotFoundException expected) {
                // A consumer has only the shared library and its chosen backend.
            }
        }
    }

    private static void run(LoggerContext context, String scenario) {
        var logger = context.getLogger("collector.jobs");
        switch (scenario) {
            case "supported" -> {
                MDC.put("collector.task.id", "parent-task");
                MDC.put("collector.auth.token", "synthetic-ambient");
                MDC.put("event.duration", "ambient-string");
                CollectionExample.run(logger);
                MDC.remove("event.duration");
                OmissionExample.run(logger);
                if (!"parent-task".equals(MDC.get("collector.task.id"))
                        || !"synthetic-ambient".equals(MDC.get("collector.auth.token"))) {
                    throw new AssertionError("The guide examples did not restore their caller's MDC");
                }
                MDC.remove("collector.auth.token");
                FailureExample.run(logger);
                LogEvents.warn(logger).action("collect_type_only")
                        .field(EcsFields.ERROR_TYPE, "collector.RemoteUnavailable")
                        .message("remote unavailable").log();
                appendAndCheckRestoration(context, false, false);
            }
            case "dataset-event" -> LogEvents.info(logger)
                    .field(new LogFieldSpec("event.dataset", LogValueType.STRING), "other")
                    .message("dataset override").log();
            case "dataset-mdc" -> {
                MDC.put("event.dataset", "other");
                LogEvents.info(logger).action("collect_attempt").log();
            }
            case "snapshot" -> appendAndCheckRestoration(context, true, false);
            case "cause-collision" -> appendAndCheckRestoration(context, false, true);
            case "path-collision" -> LogEvents.info(logger)
                    .field(new LogFieldSpec("collector.job", LogValueType.STRING), "scalar")
                    .field(new LogFieldSpec("collector.job.id", LogValueType.STRING), "child")
                    .log();
            case "reserved-metadata" -> LogEvents.info(logger)
                    .field(new LogFieldSpec("service.name", LogValueType.STRING), "other").log();
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }
    }

    private static void appendAndCheckRestoration(LoggerContext context, boolean snapshot, boolean causeConflict) {
        var logger = context.getLogger("collector.jobs");
        var event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName(logger.getName());
        event.setLevel(Level.INFO);
        event.setMessage("direct record");
        event.setThreadName("main");
        event.setTimeStamp(1_792_454_400_000L);
        event.setMDCPropertyMap(Map.of());
        event.addKeyValuePair(new KeyValuePair("event.action", "collect_direct"));
        if (causeConflict) {
            event.addKeyValuePair(new KeyValuePair("error.type", "explicit-type"));
            event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(
                    new IllegalStateException("cause must own error")));
        }
        var original = List.copyOf(event.getKeyValuePairs());
        var appender = (OutputStreamAppender<ILoggingEvent>) context.getLogger("ROOT").getAppender("ECS_FILE");
        appender.doAppend(snapshot ? LoggingEventVO.build(event) : event);
        if (!original.equals(event.getKeyValuePairs())) {
            throw new AssertionError("The application encoder changed the source record");
        }
    }
}
