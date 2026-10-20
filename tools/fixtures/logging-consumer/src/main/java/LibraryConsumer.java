import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import examples.CollectionExample;
import examples.FailureExample;
import examples.OmissionExample;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.stream.Collectors;

/** Runs the actual plain guide against a packaged library and a consumer-selected provider. */
public final class LibraryConsumer {
    /**
     * Executes the guide's collection, omission and failure records.
     * @param args unused
     */
    public static void main(String[] args) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        var logger = context.getLogger("collector.jobs");
        logger.setLevel(Level.DEBUG);
        var records = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        records.setContext(context);
        records.start();
        logger.addAppender(records);
        try {
            MDC.put("collector.task.id", "parent-task");
            MDC.put("collector.auth.token", "synthetic-ambient");
            CollectionExample.run(logger);
            OmissionExample.run(logger);
            require("parent-task".equals(MDC.get("collector.task.id"))
                    && "synthetic-ambient".equals(MDC.get("collector.auth.token")), "caller MDC restored");
            MDC.remove("collector.auth.token");
            FailureExample.run(logger);
            require(records.list.size() == 3, "all three guide records reach the provider");
            var collection = records.list.get(0);
            var fields = fields(collection);
            require(fields.get("event.duration").equals(500L), "duration is native Long");
            require(fields.get("collector.items").equals(12L), "count is native Long");
            require(fields.get("collector.retry").equals(false), "retry is native Boolean");
            require(fields.get("event.outcome").equals("success"), "shared outcome");
            require(fields.get("collector.auth.token").equals("[redacted]"), "selected mask");
            require(collection.getMDCPropertyMap().get("collector.task.id").equals("task-42"), "scoped task");
            require(!collection.getMDCPropertyMap().containsKey("collector.auth.token"), "event masks ambient token");
            var omitted = records.list.get(1);
            require(!fields(omitted).containsKey("event.outcome")
                    && !fields(omitted).containsKey("event.duration"), "inapplicable values omitted");
            require(!omitted.getMDCPropertyMap().containsKey("collector.auth.token"), "hidden ambient token");
            var failure = records.list.get(2);
            require(fields(failure).get("collector.request.url")
                    .equals("https://<redacted>@example.test/p?<redacted>#f"), "lexical URL transform");
            require(failure.getThrowableProxy().getClassName().equals(IllegalStateException.class.getName()),
                    "original Throwable reaches the provider");
            require("parent-task".equals(MDC.get("collector.task.id")), "failure preserves caller task");
            require(com.iocextractor.platform.logging.LogEvents.class.getProtectionDomain()
                    .getCodeSource().getLocation().toString().endsWith(".jar"), "library comes from a JAR");
            System.out.println("Logging consumer: three actual guide examples passed");
        } finally {
            MDC.clear();
            context.stop();
        }
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
