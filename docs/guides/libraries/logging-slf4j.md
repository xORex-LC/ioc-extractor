# Use typed logs, MDC scopes and ECS fields

[Русская версия](../ru/libraries/logging-slf4j.md) · [Library catalog](README.md)

Use this library for typed scalar fields, scoped MDC restoration and a shared
small ECS vocabulary. It requires Java 21 and SLF4J API. Each service owns its
custom field catalog, actions, disclosure policy and logging provider.

## Dependency and status

Release `0.3.0-rc.1` is available from
[Maven Central](https://repo.maven.apache.org/maven2/io/github/xorex-lc/ioc-platform-logging-slf4j/0.3.0-rc.1/)
and GitHub Packages. Separate cold plain Java and Boot consumers passed against
each repository in
[publication run 39455105300](https://github.com/xORex-LC/ioc-extractor/actions/runs/39455105300).
Central requires no additional repository configuration:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-logging-slf4j</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

The sole main dependency is `org.slf4j:slf4j-api`, qualified at 2.0.18. Choose a
provider in the application. The tests use Logback 1.5.38; those versions do not
establish compatibility with every provider. A no-provider process does not
qualify log delivery or MDC behavior.

The three complete Java classes below are compiled directly from this document
and executed by
[`LoggingLibraryExampleTest`](../../../platform/platform-logging-slf4j/src/test/java/com/iocextractor/platform/logging/LoggingLibraryExampleTest.java).
Run `make test-module MODULE=platform/platform-logging-slf4j`. The
[module reference](../../../platform/platform-logging-slf4j/README.md) lists ownership;
public Javadoc supplies member contracts. Packaging/publication qualification is
separate from these in-checkout consumer examples.

The [publication tooling](../library-publication.md) also compiles these exact
classes against a packaged JAR outside the checkout, using an empty Maven cache.
The application-selected provider cannot supply a missing SLF4J API dependency
on the library's behalf. Released artifacts passed these checks independently
from Central and GitHub Packages, including hashes and repository origin.
A deployed second service remains outside this packaging/API qualification.

The reviewed implementation matches tag `ioc-platform-logging-slf4j-v0.3.0-rc.1`;
the reactor remains at `0.3.0-SNAPSHOT`. The
[permanent release record](../../../.github/release-notes/ioc-platform-logging-slf4j-0.3.0-rc.1.md)
retains the original signed files, source and repository qualification.
The current reactor/publication implementation remains lockstep; independent
library versions are accepted but pending activation. Follow the
[development policy](development.md) for API changes and release ownership.
No starter or provider is included.

## Public tools

The base package is `com.iocextractor.platform.logging`. Eight public types form
the library API; `LogValueNormalizer` and event storage remain internal.

| Type | Use and public operations |
|---|---|
| `LogFieldSpec` | Immutable `(String key, LogValueType valueType)` declaration; ordinary record equality includes both components |
| `LogValueType` | STRING, LONG and BOOLEAN representations |
| `LogEvents` | `trace/debug/info/warn/error(Logger)` each creates a fresh `LogEvent` at that level |
| `LogEvent` | `field(LogFieldSpec, Object)`, `action(String)`, `outcome(EventOutcome)`, `durationNanos(long)`, `message(String)` build one record; `log()` / `log(Throwable)` attempt emission |
| `MdcScope` | `open()`, `put(LogFieldSpec, String)`, `hide(LogFieldSpec)`, `close()` restore touched ambient keys; implements `AutoCloseable` |
| `ecs.EcsFields` | Five shared scalar field constants listed below |
| `ecs.EventOutcome` | SUCCESS, FAILURE, UNKNOWN; `value()` returns the lower-case wire string |
| `redaction.LogRedaction` | `fullMask()` returns a constant; `urlComponents(String)` transforms one selected URL string |

## Declare fields and emit an operation record

Save each complete example below under `src/main/java/examples/`, using its
public class name as the file name. Your application supplies the provider and
obtains a logger, for example `LoggerFactory.getLogger("collector.jobs")`.
Call `CollectionExample.run(logger)` for the operation record; the other examples
use the same `run(Logger)` entry point. Enable DEBUG to exercise the omission
record. The standalone consumer provides this small driver and exact assertions.

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
A rejected call leaves prior values/declarations intact. Descriptor equality
includes key and type, while one event identifies fields by key alone. The last
successful update replaces that key's value; JSON member order is not a contract.
There is no process-global field registry.

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

The skipped record omits outcome and duration. Use `unknown` only
when an applicable outcome cannot be determined. `durationNanos` accepts measured
values from zero through `Long.MAX_VALUE`; callers own measurement and overflow.
Do not put zero into an unmeasured field merely to populate it.

MDC scopes belong to one thread and close in LIFO order. Reconstruct worker
context from explicit command metadata inside that worker. Untouched MDC changes
remain intact. A successful close is idempotent; later writes through that scope
fail. `put` accepts a STRING descriptor and a String value; null hides it.
`hide` accepts a descriptor of any type. `open()` does not read or alter MDC.
The same-thread and LIFO rules are caller obligations, not runtime enforcement.
Provider failures propagate, so complete restoration cannot be guaranteed
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

| `EcsFields` constant | Key / representation | Producer responsibility |
|---|---|---|
| `EVENT_ACTION` | `event.action` / STRING | Choose an operation name; `.action(...)` rejects blank text without trimming |
| `EVENT_OUTCOME` | `event.outcome` / STRING | Use `.outcome(...)` for lower-case success/failure/unknown; omit when not applicable |
| `EVENT_DURATION` | `event.duration` / LONG | Supply measured elapsed nanoseconds; `.durationNanos(...)` rejects negatives |
| `ERROR_TYPE` | `error.type` / STRING | Name an error type; with a Throwable let the qualified Boot encoder own `error.*` |
| `FILE_PATH` | `file.path` / STRING | Resolve and disclose a full file path; generic Path conversion does not make it absolute |

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
JAR. The [Boot ECS recipe](logging-slf4j-boot.md) provides executable YAML,
XML and an application-owned encoder example for the qualified synchronous
mutable-event path. It documents dataset/error ownership and unsupported paths.
IOC producer migrations remain covered by separate service-level regressions.
The [accepted boundary](../../ADR/0045-reusable-slf4j-logging-contract.md) records
which guarantees belong to the library and which require backend evidence.

## Failure behavior

Null required arguments cause `NullPointerException`. Invalid keys, unsupported
scalar inputs, conflicting declarations, blank actions and negative helper
durations cause `IllegalArgumentException`; scope writes after close cause
`IllegalStateException`. Field/helper validation happens before level filtering.
Provider failures propagate; there is no automatic retry or fallback logger.
Disabled emission skips collision preparation and provider delivery. Enabled
emission temporarily hides only non-null event fields' exact same-key MDC values,
including LONG/BOOLEAN fields, and attempts restoration after success or failure.
Prefix conflicts such as `collector` versus `collector.items` remain service work.

Library type-mismatch errors omit the supplied value, but keys appear in some
errors and may themselves be sensitive. Custom `toString()` and provider failures
can throw their own exceptions. The library is not a safe error renderer.

A builder belongs to one thread and one record's construction. Calling `log`
twice attempts two emissions. An empty default message and a null optional cause
are valid. Successful return does not imply durable storage or successful JSON
rendering. For service-internal wiring, see the
[observability capability](../../dev/observability.md).


## Sources, tests and maintenance

- [Source and package Javadoc](../../../platform/platform-logging-slf4j/src/main/java/com/iocextractor/platform/logging/)
  define exact member contracts. The [module README](../../../platform/platform-logging-slf4j/README.md)
  maps dependency and package ownership.
- [Module tests](../../../platform/platform-logging-slf4j/src/test/java/com/iocextractor/platform/logging/)
  cover descriptor/type identity, scalar snapshots, disabled-level validation,
  provider failures, MDC cleanup, explicit worker context and masking. Run
  `make test-module MODULE=platform/platform-logging-slf4j`.
- [Plain consumer](../../../tools/fixtures/logging-consumer/README.md) and
  [Boot consumer](../../../tools/fixtures/logging-boot-consumer/README.md) compile
  these actual examples from packaged JARs with separate caches. The latter also
  loads the exact recipe Java/YAML/XML and checks output bytes and status.
- [Publication contracts](../../../tools/tests/library-publication-test.py)
  enforce EN/RU Java/XML/YAML parity and extraction: `make library-publication-test`.
  Run `make docs` for links. The unchanged independent drivers and JSON assertions
  remain separate from the text being compiled.

Update both languages and their executable examples when public behavior changes.
Keep member details in Javadoc, IOC integration in the capability document and
release identities in the permanent release record. A different provider, Boot
baseline, async encoder path, deployed second service, collector/Elasticsearch
mapping or load claim needs its own qualification.
