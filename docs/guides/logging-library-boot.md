# Use the logging library with Spring Boot ECS

This recipe belongs to the consuming application. It is qualified with Java 21,
Spring Boot 4.0.8, Logback 1.5.38 and SLF4J 2.0.18. The reusable JAR still
requires only SLF4J API. Start with the [plain Java guide](logging-library.md)
for field types, MDC lifetime, masking and error behavior.

The supported path is a **synchronous FileAppender receiving mutable Logback
LoggingEvent objects**. Do not put an AsyncAppender, serialized event transport
or LoggingEventVO in this recipe. The library's own async snapshot tests do not
qualify this application encoder for those paths. A second service should keep
the following small encoder in its own bootstrap code and test its complete
logging configuration before deployment.

## Dependencies

Use the service's Spring Boot dependency management and `spring-boot-starter`
for its selected provider. Add the logging library at the same product/library
version as the other platform libraries. At this checkpoint the new artifact is
implemented in the reactor; publication and repository consumption are separate
release steps. Do not treat an unpublished snapshot as available in Central.

## Configure one file destination

Put these settings in the service's `application.yml`. The environment variable
allows an operator to choose the destination; the example defaults to a relative
path owned by that service. Choose retention and rotation separately for the
deployment; this minimal example does not configure them.

```yaml
spring:
  application:
    name: feeds-collector
logging:
  file:
    name: ${COLLECTOR_LOG_FILE:var/logs/feeds-collector.ecs.json}
  structured:
    ecs:
      service:
        name: ${spring.application.name}
        version: "example-1"
        environment: example
```

Place this `logback-spring.xml` on the application classpath. Boot must load it:
plain Logback configuration cannot resolve `springProperty`. Configuration
scanning is not supported by these Boot extensions. Keep the root level and
any additional destinations under application configuration ownership.
[Boot logging reference](https://docs.spring.io/spring-boot/4.0/reference/features/logging.html#features.logging.logback-extensions).

```xml
<configuration>
    <springProperty scope="context" name="COLLECTOR_LOG_FILE" source="logging.file.name"/>
    <springProperty scope="context" name="SERVICE_NAME" source="logging.structured.ecs.service.name"/>
    <appender name="ECS_FILE" class="ch.qos.logback.core.FileAppender">
        <file>${COLLECTOR_LOG_FILE}</file>
        <append>true</append>
        <encoder class="examples.logging.CollectorEcsEncoder">
            <format>ecs</format>
            <charset>UTF-8</charset>
            <eventDataset>${SERVICE_NAME}</eventDataset>
        </encoder>
    </appender>
    <logger name="collector" level="DEBUG"/>
    <root level="INFO">
        <appender-ref ref="ECS_FILE"/>
    </root>
</configuration>
```

The example appends across restarts and enables the plain guide's DEBUG omission
record for the `collector` namespace. Add rotation and retention appropriate to
the service. The IOC daemon already has a separate RollingFileAppender policy;
this recipe does not replace it.

## Add the application encoder

Save this class as `examples/logging/CollectorEcsEncoder.java` in the service.
The configured dataset enters the same key/value stream as dynamic `event.*`
fields, so Boot writes one nested `event` object. A plain
`logging.structured.json.add.event.dataset` is not a substitute for this tested
combination. Other services can change the package and class name together with
the XML reference; neither is a type exported by the library.

```java
package examples.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

import java.util.ArrayList;

/** Adds one configured dataset to synchronous, mutable Logback records. */
public final class CollectorEcsEncoder extends StructuredLogEncoder {
    private static final String DATASET = "event.dataset";
    private String eventDataset;

    /** Sets the application-owned dataset; startup rejects a missing value. */
    public void setEventDataset(String eventDataset) {
        this.eventDataset = eventDataset;
    }

    /** Validates configuration before starting Boot's formatter. */
    @Override
    public void start() {
        if (eventDataset == null || eventDataset.isBlank()) {
            throw new IllegalStateException("eventDataset must not be blank");
        }
        super.start();
    }

    /**
     * Encodes one supported record and restores its original key/value pairs.
     * Unsupported snapshots and dataset overrides are rejected; an appender
     * can contain that exception, so inspect its status as well as output bytes.
     */
    @Override
    public byte[] encode(ILoggingEvent event) {
        if (!(event instanceof LoggingEvent mutableEvent)) {
            throw new IllegalArgumentException("This recipe requires a mutable LoggingEvent");
        }
        var original = mutableEvent.getKeyValuePairs();
        if (event.getMDCPropertyMap().containsKey(DATASET)
                || (original != null && original.stream().anyMatch(pair -> DATASET.equals(pair.key)))) {
            throw new IllegalArgumentException("event.dataset belongs to application configuration");
        }
        var enriched = original == null ? new ArrayList<KeyValuePair>() : new ArrayList<>(original);
        enriched.add(new KeyValuePair(DATASET, eventDataset));
        mutableEvent.setKeyValuePairs(enriched);
        try {
            return super.encode(mutableEvent);
        } finally {
            mutableEvent.setKeyValuePairs(original);
        }
    }
}
```

The service owns the entire reserved-field policy. Do not write `event.dataset`
from a producer or MDC. Do not redeclare `service.*`, `log.*`, `process.*`,
`ecs.*`, message or timestamp. Reject scalar/object prefix conflicts when
reviewing the service catalog. These checks cannot intercept arbitrary raw
SLF4J or third-party MDC writes.

With a Throwable, omit explicit `error.*` fields and let Boot encode the complete
error object. Without a Throwable, `EcsFields.ERROR_TYPE` supports type-only
errors. See the [Boot structured logging reference](https://docs.spring.io/spring-boot/4.0/reference/features/logging.html#features.logging.structured).

## Verify a service integration

Run the plain guide's collection, omission and failure examples through this
file destination. A collection record must contain these independent query
results; process ID, timestamp and thread values are runtime metadata.

| JSON pointer | Type | Expected value |
|---|---|---|
| `/service/name` | string | `feeds-collector` |
| `/service/version` | string | `example-1` |
| `/event/dataset` | string | `feeds-collector` |
| `/event/action` | string | `collect_complete` |
| `/event/outcome` | string | `success` |
| `/event/duration` | integer | `500` |
| `/collector/items` | integer | `12` |
| `/collector/retry` | boolean | `false` |
| `/collector/auth/token` | string | `[redacted]` |

Also check the omission record's absent outcome/duration, the failure record's
error type/stack trace and the type-only error's absent stack trace. Parse with
duplicate-key detection enabled. Require nonempty output and no internal WARN
or ERROR statuses for supported input. Appender acceptance alone is insufficient.

The repository's `LoggingBootRecipeIT` compiles this exact Java block and the
plain guide examples, loads these exact YAML/XML blocks through Boot, and runs
them in a separate JVM without IOC classes. It also checks that unsupported
snapshots, dataset overrides and known JSON path/error conflicts produce no
record and an observable appender error. This is in-checkout backend evidence,
not cold repository consumption, a production deployment, collector ingestion
or Elasticsearch mapping qualification. The [accepted boundary](../ADR/0045-reusable-slf4j-logging-contract.md)
keeps those responsibilities separate.
