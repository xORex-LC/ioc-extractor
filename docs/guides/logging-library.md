# Using the SLF4J logging library

Use this library for typed scalar fields, scoped MDC restoration and a shared
small ECS vocabulary. It requires Java 21 and SLF4J API. Each service owns its
custom field catalog, actions, disclosure policy and logging provider.

## Dependency and status

The module is implemented at the reactor version, currently `0.3.0-SNAPSHOT`.
It has not been independently published. These coordinates identify the local
implementation; public repository resolution requires a later component release:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-logging-slf4j</artifactId>
  <version>0.3.0-SNAPSHOT</version>
</dependency>
```

The sole main dependency is `org.slf4j:slf4j-api`, qualified at 2.0.18. Choose a
provider in the application. The tests use Logback 1.5.38; those versions do not
establish compatibility with every provider. A no-provider process does not
qualify log delivery or MDC behavior.

The three complete Java classes below are compiled directly from this document
and executed by
[`LoggingLibraryExampleTest`](../../platform/platform-logging-slf4j/src/test/java/com/iocextractor/platform/logging/LoggingLibraryExampleTest.java).
Run `make test-module MODULE=platform/platform-logging-slf4j`. The
[module reference](../../platform/platform-logging-slf4j/README.md) lists ownership;
public Javadoc supplies member contracts. Packaging/publication qualification is
separate from these in-checkout consumer examples.

The [publication tooling](library-publication.md) also compiles these exact
classes against a packaged JAR outside the checkout, using an empty Maven cache.
The application-selected provider cannot supply a missing SLF4J API dependency
on the library's behalf. Local file-repository qualification remains separate
from release downloads from Central and GitHub Packages.

## Declare fields and emit an operation record

```java
package examples;

import com.iocextractor.platform.logging.LogEvents;
import com.iocextractor.platform.logging.LogFieldSpec;
import com.iocextractor.platform.logging.LogValueType;
import com.iocextractor.platform.logging.MdcScope;
import com.iocextractor.platform.logging.ecs.EventOutcome;
import com.iocextractor.platform.logging.redaction.LogRedaction;
import org.slf4j.Logger;

public final class CollectionExample {
    private static final LogFieldSpec TASK = new LogFieldSpec("collector.task.id", LogValueType.STRING);
    private static final LogFieldSpec ITEMS = new LogFieldSpec("collector.items", LogValueType.LONG);
    private static final LogFieldSpec RETRY = new LogFieldSpec("collector.retry", LogValueType.BOOLEAN);
    private static final LogFieldSpec TOKEN = new LogFieldSpec("collector.auth.token", LogValueType.STRING);

    public static void run(Logger logger) {
        try (var scope = MdcScope.open()) {
            scope.put(TASK, "task-42");
            LogEvents.info(logger).action("collect_complete").outcome(EventOutcome.SUCCESS)
                    .durationNanos(500).field(ITEMS, 12).field(RETRY, false)
                    .field(TOKEN, LogRedaction.fullMask())
                    .message("collection completed").log();
        }
    }
}
```

The record carries task context, a numeric count, a boolean and exactly
`[redacted]` for the selected STRING token representation. After the method
returns, the previous task context is restored. Acquire the scope first and
populate it inside the body: chained population in a resource initializer can
fail before Java acquires the resource.

Keys are case-sensitive dotted paths. Every segment follows
`[A-Za-z_][A-Za-z0-9_-]*`; invalid paths, including whitespace and empty segments,
are rejected rather than normalized. The service checks catalog uniqueness and
parent/child conflicts such as `collector` versus `collector.task.id`.

| Type | Supported inputs | Emitted representation |
|---|---|---|
| STRING | CharSequence, Enum, Path, UUID | Snapshot using toString() |
| LONG | Byte, Short, Integer, Long | Long |
| BOOLEAN | Boolean | Boolean |

Arrays, collections, maps, floating-point values and numeric/boolean strings are
rejected. Enum conversion uses `toString()`; the outcome helper deliberately uses
its lower-case wire value. Equivalent field descriptors replace one key's value.
Changing its type within the event fails even after a first or repeated null.
A rejected call leaves prior values/declarations intact.

## Omit a field while hiding ambient context

```java
package examples;

import com.iocextractor.platform.logging.LogEvents;
import com.iocextractor.platform.logging.LogFieldSpec;
import com.iocextractor.platform.logging.LogValueType;
import com.iocextractor.platform.logging.MdcScope;
import org.slf4j.Logger;

public final class OmissionExample {
    private static final LogFieldSpec TOKEN = new LogFieldSpec("collector.auth.token", LogValueType.STRING);

    public static void run(Logger logger) {
        try (var scope = MdcScope.open()) {
            scope.hide(TOKEN);
            LogEvents.debug(logger).action("collect_skipped")
                    .field(TOKEN, null).message("collection skipped").log();
        }
    }
}
```

Null omits the event-local value but normally leaves ambient MDC visible.
The protected hide scope above removes that ambient copy during emission and
restores it afterwards. Prefer keeping raw secrets out of MDC entirely. A mask
or hide affects only the selected key; messages, causes and other copies retain
their original content. A textual mask cannot be sent to a LONG/BOOLEAN field.

The skipped informational record omits outcome and duration. Use `unknown` only
when an applicable outcome cannot be determined. `durationNanos` accepts measured
values from zero through `Long.MAX_VALUE`; callers own measurement and overflow.
Do not put zero into an unmeasured field merely to populate it.

MDC scopes belong to one thread and close in LIFO order. Reconstruct worker
context from explicit command metadata inside that worker. Untouched MDC changes
remain intact. A successful close is idempotent; later writes through that scope
fail. Provider failures propagate, so complete restoration cannot be guaranteed
when the provider itself fails.

## Record a failure with a selected URL transformation

```java
package examples;

import com.iocextractor.platform.logging.LogEvents;
import com.iocextractor.platform.logging.LogFieldSpec;
import com.iocextractor.platform.logging.LogValueType;
import com.iocextractor.platform.logging.ecs.EventOutcome;
import com.iocextractor.platform.logging.redaction.LogRedaction;
import org.slf4j.Logger;

public final class FailureExample {
    private static final LogFieldSpec URL = new LogFieldSpec("collector.request.url", LogValueType.STRING);

    public static void run(Logger logger) {
        var failure = new IllegalStateException("synthetic connection failure");
        LogEvents.error(logger).action("collect_fetch").outcome(EventOutcome.FAILURE)
                .field(URL, LogRedaction.urlComponents("https://u:p@example.test/p?q=secret#f"))
                .message("collection failed").log(failure);
    }
}
```

The URL becomes `https://<redacted>@example.test/p?<redacted>#f`. This lexical
helper does not parse/normalize a URI. It recognizes user-info only after `://`,
through the last `@` before `/`, `?` or `#`. It then replaces content after the
first `?` up to the next `#` or end. Scheme-less user-info, paths and fragments
can remain visible; a `?` inside a fragment is still processed. Null returns null.
Use full masking when these retained parts are unsuitable for disclosure.
The Throwable is passed unchanged and may contain sensitive text; the service
must decide whether it is appropriate to log.

## ECS and encoder ownership

The common descriptors are `event.action`, `event.outcome`, `event.duration`,
`error.type` and `file.path`, using ECS 8.11 meanings. Action is service-defined;
the outcome helper emits success/failure/unknown, and duration is nonnegative
nanoseconds. `file.path` denotes a full path including the name; the producer
owns path resolution and disclosure. Generic Path conversion preserves relative
input. See the [ECS event](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/event.yml),
[file](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/file.yml) and
[error](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/error.yml) definitions.

Generic `.field(...)` checks representation only: it can bypass helper semantics.
Raw SLF4J and third-party MDC can also bypass this profile. Services therefore
check their catalog, producer behavior and final output rather than treating
this facade as a complete ECS validator. Type/category arrays are outside v1.

The application owns serialization, `@timestamp`, message, `ecs.*`, `service.*`,
`process.*`, `log.*` and configured built-in metadata. Do not redeclare those
paths in events or MDC. Custom fields use a service namespace such as
`collector.*` or `ioc.*`. A task ID is not a distributed trace/span ID.

For the tested Boot 4.0.8 recipe, application configuration owns `event.dataset`:
no per-record or ambient override. A Throwable record lets the encoder supply
the entire `error.*` object; the example therefore does not add `error.type`.
Without a Throwable, an explicit `EcsFields.ERROR_TYPE` can describe a type-only
error. Check actual output bytes and encoder status: an appender can drop a
conflicting record without throwing back to the producer.

The IOC Boot encoder/configuration stays application-owned; it is not in this
JAR. The [Boot ECS recipe](logging-library-boot.md) provides executable YAML,
XML and an application-owned encoder example for the qualified synchronous
mutable-event path. It documents dataset/error ownership and unsupported paths.
IOC producer migrations remain covered by separate service-level regressions.
The [accepted boundary](../ADR/0045-reusable-slf4j-logging-contract.md) records
which guarantees belong to the library and which require backend evidence.

## Failure behavior

Null required arguments cause `NullPointerException`. Invalid keys, unsupported
scalar inputs, conflicting declarations, blank actions and negative helper
durations cause `IllegalArgumentException`; scope writes after close cause
`IllegalStateException`. Field/helper validation happens before level filtering.
Provider failures propagate; there is no automatic retry or fallback logger.
Disabled emission skips collision preparation and provider delivery.

A builder belongs to one thread and one record's construction. Calling `log`
twice attempts two emissions. An empty default message and a null optional cause
are valid. Successful return does not imply durable storage or successful JSON
rendering. For service-internal wiring, see the
[observability capability](../dev/observability.md).
