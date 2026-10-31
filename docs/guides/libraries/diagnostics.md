# Results and diagnostics with the diagnostics library

[Русская версия](../ru/libraries/diagnostics.md) · [Library catalog](README.md)

Use `ioc-platform-diagnostics-api` when an operation must return a value together
with structured problems, decide whether to stop, or retain a small diagnostic
sample while counting every occurrence. A collector can report malformed feed
rows without coupling its processing code to a logger or Spring.

The library supplies values, policies, accumulators and synchronous delivery.
The service defines its codes, messages, redaction, exceptions, transaction
checkpoint and recovery. No catalog, logging provider, Spring starter, audit
store or wire format is installed.

## Dependency and version

Use Java 21; this is the qualified baseline. The artifact has no non-JDK
production dependencies and its consumer POM requires no reactor parent:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-diagnostics-api</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

The release is published to Maven Central and GitHub Packages. Default Central
needs no extra repository declaration or credentials. GitHub Packages requires
authenticated Maven settings; see the [publication procedure](../library-publication.md#consumption).
The reactor remains at `0.3.0-SNAPSHOT`. The Java implementation reviewed for
this guide matches `ioc-platform-diagnostics-api-v0.3.0-rc.1`.
[The release record](../../../.github/release-notes/ioc-platform-diagnostics-api-0.3.0-rc.1.md)
preserves the source, signed hashes and original repository qualification.

## Public tools

The eighteen public types use three packages under
`com.iocextractor.platform.diagnostics`:

| Package / type | Use |
|---|---|
| `DiagnosticCode` | Service-owned stable ID, default severity and impact |
| `DiagnosticSeverity` | Ordered TRACE, DEBUG, INFO, WARN, ERROR, FATAL levels; `isErrorOrWorse()` identifies ERROR/FATAL |
| `DiagnosticImpact` | ELEMENT, RUN or OPERATION ownership; independent of severity |
| `DiagnosticFactory` | Creates builders with an explicit shared clock |
| `DiagnosticBuilder` | Sets effective severity, context and cause; `build()` timestamps the occurrence |
| `Diagnostic` | Occurrence with code, effective severity, context, optional cause and timestamp |
| `result.Result<T>` | Nullable value plus copied diagnostics, mapping and severity helpers |
| `result.Notification` | Unbounded task-owned collection; `add`, `addAll`, `diagnostics`, `toResult` and severity helpers |
| `result.FailurePolicy` | Pure `evaluate(Notification)` strategy; built-in fail-fast and collect-and-continue |
| `result.FailureDecision` | Continue or stop with an associated diagnostic |
| `result.BoundedDiagnosticCollector` | Stage-local retained samples and exact counts |
| `result.BoundedNotification` | Run-level retained budget, merge, delivery callback and display marker |
| `result.DiagnosticBatch` | Immutable retained occurrences plus exact summary, without a display marker |
| `result.DiagnosticSummary` | Exact totals, suppressed counts, severity counts and checked arithmetic |
| `sink.DiagnosticSink` | Synchronous `emit(Diagnostic)` port |
| `sink.NoopDiagnosticSink` | Discards non-null occurrences |
| `sink.CollectingDiagnosticSink` | Unbounded list for tests or report assembly |
| `sink.ResilientDiagnosticSink` | Contains runtime delegate/fallback failures with a service-supplied callback |

## Define codes, create a result and evaluate policy

Put the three Java examples on this page in the same Maven project's
`src/main/java/`, with the dependency above and `maven.compiler.release=21`.
The later examples reuse the first example's service-owned enum and clock.
Run each main class from your IDE, or use the isolated consumer described below.

Save this as `DiagnosticResultExample.java`:

```java
import com.iocextractor.platform.diagnostics.DiagnosticCode;
import com.iocextractor.platform.diagnostics.DiagnosticFactory;
import com.iocextractor.platform.diagnostics.DiagnosticImpact;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import com.iocextractor.platform.diagnostics.result.FailurePolicy;
import com.iocextractor.platform.diagnostics.result.Notification;
import com.iocextractor.platform.diagnostics.result.Result;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

public final class DiagnosticResultExample {
    enum ImportCode implements DiagnosticCode {
        BAD_ROW, SUPPRESSED;

        @Override public String id() { return "import/" + name().toLowerCase(Locale.ROOT); }
        @Override public DiagnosticSeverity defaultSeverity() { return DiagnosticSeverity.WARN; }
        @Override public DiagnosticImpact impact() { return DiagnosticImpact.ELEMENT; }
    }

    static DiagnosticFactory factory() {
        return new DiagnosticFactory(
                Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC));
    }

    public static void main(String[] args) {
        var factory = factory();
        var warning = factory.create(ImportCode.BAD_ROW).with("row", 7).build();
        Result<String> result = Result.success("accepted")
                .withDiagnostic(warning).map(value -> value.toUpperCase(Locale.ROOT));
        var notification = new Notification().addAll(result.diagnostics());
        if (FailurePolicy.failFast().evaluate(notification).shouldStop()) {
            throw new IllegalStateException("A warning must allow continuation");
        }

        notification.add(factory.create(ImportCode.BAD_ROW)
                .severity(DiagnosticSeverity.ERROR).with("row", 8).build());
        var failFast = FailurePolicy.failFast().evaluate(notification);
        var collect = FailurePolicy.collectAndContinue().evaluate(notification);
        if (!"ACCEPTED".equals(result.value()) || !failFast.shouldStop() || collect.shouldStop()) {
            throw new IllegalStateException("Result or policy behavior differs");
        }
        System.out.println("Value: " + result.value() + "; fail-fast: stop; collect-and-continue: continue");
    }
}
```

Expected output: `Value: ACCEPTED; fail-fast: stop; collect-and-continue: continue`.
Use a service-owned clock such as `Clock.systemUTC()` in production. Calling
`Diagnostic.builder(code, clock)` directly is equivalent to starting through
the factory. Builders accept `severity`, `with`, `context` and `cause`; omit
`cause` when absent. Reusing a context key replaces its previous value.

### Codes, severity and ownership

Keep code IDs nonblank and unique within the service's catalog. No `GROUP.CODE`
syntax is imposed. Code metadata, equality and hash codes must remain stable.
Code objects are compared by their own equality, rather than by ID strings.
Diagnostic equality includes code object, context and **effective severity**;
time and cause are excluded. Equal input occurrences still count separately.

| Severity | `failFast()` | `collectAndContinue()` |
|---|---|---|
| TRACE / DEBUG / INFO / WARN | Continue | Continue |
| ERROR | Stop | Continue |
| FATAL | Stop | Stop |

Policies select the first matching diagnostic in the supplied list. Evaluating
one neither cancels processing nor throws a product exception. The service must
act on `shouldStop()` before durable writes; a stop decision's `diagnostic()`
contains the reason, while a continue decision's optional is empty. Custom
policies must return a non-null decision without I/O, mutation or thrown failures;
the library does not enforce purity or repair an invalid custom policy.

Impact names the affected scope. ELEMENT and RUN spend the retained-detail
budget; OPERATION bypasses it. Impact by itself never authorizes skipping a row,
committing data or retrying work. A custom policy that depends on every code must
see full observations before compaction, since omitted reasons cannot be recovered.

### Values and snapshots

`Result.success(null)` is valid and does not itself indicate failure. `map`
requires a non-null mapper, skips it for a null value, preserves diagnostic order
and propagates mapper failures unchanged. `withDiagnostic` returns a new result;
`Notification` instead mutates its own collection. `toResult(value)` snapshots
that collection. `hasErrors` checks ERROR/FATAL and `hasFatal` checks FATAL.
Neither a result nor a notification delivers diagnostics automatically.

Context maps and diagnostic lists are copied, but their values, code objects,
result values and Throwables are shared references. Keep them stable after
publication. `Diagnostic.toString()` and batch inspection can expose raw context;
use a service-owned rendering/redaction boundary before logging or returning it.

## Bound stage detail and merge into a run

Save this as `DiagnosticBudgetExample.java` alongside the first example:

```java
import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import com.iocextractor.platform.diagnostics.result.BoundedDiagnosticCollector;
import com.iocextractor.platform.diagnostics.result.BoundedNotification;
import com.iocextractor.platform.diagnostics.result.FailurePolicy;
import com.iocextractor.platform.diagnostics.result.Notification;
import com.iocextractor.platform.diagnostics.sink.CollectingDiagnosticSink;

import java.util.List;
import java.util.Map;

public final class DiagnosticBudgetExample {
    public static void main(String[] args) {
        var factory = DiagnosticResultExample.factory();
        var code = DiagnosticResultExample.ImportCode.BAD_ROW;
        var markerCode = DiagnosticResultExample.ImportCode.SUPPRESSED;
        var stage = new BoundedDiagnosticCollector(1, markerCode);
        for (var severity : List.of(DiagnosticSeverity.WARN, DiagnosticSeverity.WARN,
                DiagnosticSeverity.ERROR, DiagnosticSeverity.FATAL)) {
            stage.add(factory.create(code).severity(severity).build());
        }
        var batch = stage.batch();
        var delivered = new CollectingDiagnosticSink();
        var run = new BoundedNotification(1, factory, markerCode);
        run.addAll(batch, delivered::emit);
        var summary = run.summary();
        var marker = run.diagnostics().getLast();
        var decision = FailurePolicy.collectAndContinue()
                .evaluate(new Notification().addAll(run.batch().retained()));

        if (batch.retained().size() != 3 || batch.summary().suppressed() != 1
                || summary.total() != 4 || summary.suppressed() != 3
                || delivered.diagnostics().size() != 3 || !decision.shouldStop()
                || !run.batch().retained().stream().map(Diagnostic::severity).toList()
                    .equals(List.of(DiagnosticSeverity.FATAL))
                || marker.code() != markerCode || marker.severity() != DiagnosticSeverity.ERROR
                || !marker.context().equals(Map.of("limit", 1, "suppressedCount", 3L,
                    "suppressedBySeverity", Map.of("WARN", 2L, "ERROR", 1L)))) {
            throw new IllegalStateException("Bounded counts, detail or marker differs");
        }
        System.out.printf("Observed: %d; retained: %d; suppressed: %d; delivered: %d%n",
                summary.total(), run.batch().retained().size(), summary.suppressed(),
                delivered.diagnostics().size());
    }
}
```

Expected output: `Observed: 4; retained: 1; suppressed: 3; delivered: 3`.

For the input WARN A, WARN B, ERROR C, FATAL D with positive budget N=1:

| View | Real retained detail | Observed total | Suppressed |
|---|---|---:|---:|
| Stage `batch()` | A, C, D in encounter order | 4 | 1 |
| Run `batch()` | D | 4 | 3 |
| Run `diagnostics()` display | D followed by a synthetic marker at ERROR | 4 | 3 |

The stage retains the first N budgeted occurrences and, when necessary, its
first rejecting occurrence and first FATAL, up to N+2. The run retains at most
N budgeted occurrences: its first ERROR/FATAL can replace the earliest
lowest-severity budgeted sample. Both retain **every OPERATION occurrence**
outside the budget. Memory can therefore still grow through OPERATION input,
large context values, unbounded `Notification` or `CollectingDiagnosticSink`.

Replacement preserves the displaced list position. At budget 2, WARN A,
ERROR B, FATAL C leave `[C, B]`. Fail-fast over this sample selects C even though
B arrived first. Compaction preserves the built-in stopping signals, not
chronological sample order or the original first failure reason.

Matching stage/run budgets preserve the direct-run retained sample. A smaller
stage budget can lose detail that a larger run would have kept: stage budget 1
with A, B, C retains A and C, so a run budget of 3 still cannot recover B.
Counts remain exact; retained samples and suppression counts can differ from
direct accumulation. Delivery follows the batch's retained order.

`offer` returns whether the new occurrence was retained at that point. `add`
discards this Boolean result. `addAll(batch, delivery)` calls delivery for each
newly retained occurrence, including replacements. A later replacement does not
retract an earlier delivery; a detail budget is not a lifetime delivery limit.
Use `addAll(batch)` to merge without delivery.

### Batches, counters and the reserved marker

Merge `batch()` snapshots, which contain real occurrences and exact counts.
`diagnostics()` is a display snapshot with an optional synthetic marker. Never
feed the marker back as an occurrence. Bounded entry points reject their
configured marker by code equality before counting it. Share the **same stable
marker code throughout the aggregation tree**: a summary cannot prove the origin
of omitted occurrences, and the validation marker is not stored in the batch.

The display marker has integer `limit`, long `suppressedCount` and an immutable
`suppressedBySeverity` map of severity names to long counts. Its effective
severity is the highest omitted severity. Snapshot reads deliver nothing and
change no counts; successive markers can have different timestamps.

`DiagnosticBatch.of(retained, summary, markerCode)` copies and validates detail:
size must equal total minus suppressed, represented severity counts cannot
exceed observed counts, observed ERROR/FATAL needs a retained rejecting signal,
and observed FATAL needs a retained FATAL. It excludes the reserved marker.
Batch equality uses retained detail and summary, excluding marker configuration.

`DiagnosticSummary.plus` adds independent counts; `since` computes a monotonic
delta from an earlier snapshot; `plusDiagnostics` appends real occurrences
without changing suppression. `count` returns zero for an absent severity;
`hasErrors()` checks all observed ERROR/FATAL counts, including omitted detail.
The map's values sum to total; explicit zero entries are valid and participate
in record equality. `suppressedBySeverity()` on a batch omits zero entries.
Counts use checked long arithmetic and exclude synthetic markers.

## Isolate failures when reporting diagnostics

Save this as `DiagnosticDeliveryExample.java` alongside the first example:

```java
import com.iocextractor.platform.diagnostics.sink.DiagnosticSink;
import com.iocextractor.platform.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.platform.diagnostics.sink.ResilientDiagnosticSink;

import java.util.ArrayList;

public final class DiagnosticDeliveryExample {
    public static void main(String[] args) {
        var warning = DiagnosticResultExample.factory()
                .create(DiagnosticResultExample.ImportCode.BAD_ROW).with("row", 7).build();
        var unavailable = new IllegalStateException("report unavailable");
        var failures = new ArrayList<RuntimeException>();
        DiagnosticSink sink = new ResilientDiagnosticSink(
                diagnostic -> { throw unavailable; },
                (diagnostic, failure) -> {
                    if (diagnostic != warning) {
                        throw new AssertionError("Fallback received a different occurrence");
                    }
                    failures.add(failure);
                });
        sink.emit(warning);
        NoopDiagnosticSink.INSTANCE.emit(warning);
        if (failures.size() != 1 || failures.getFirst() != unavailable) {
            throw new IllegalStateException("Delivery failure was not reported once");
        }
        System.out.println("Delivery failures: " + failures.size());
    }
}
```

Expected output: `Delivery failures: 1`.

Delivery runs synchronously in the caller. The resilient wrapper calls its
delegate once; after a runtime failure it calls the fallback once with the
original diagnostic and exception. Runtime failures from the fallback are
contained, with no recursion or retry. `Error` propagates from either callback.
There is no worker, timeout, queue or durable-delivery guarantee. Services own
callback resources, latency, thread safety and disclosure.

### Partial mutation during merging

A raw delivery callback can throw after the failing occurrence was already
counted and retained. Later retained occurrences and the batch's omitted counts
have not yet been merged. Overflow also leaves earlier additions accepted.
There is no rollback or retry identity; replaying the entire batch can count
or deliver that accepted prefix again. Wrap fallible reporting with
`ResilientDiagnosticSink` when it must not abort processing.

## Thread ownership and invalid inputs

Builders, notifications, bounded collectors and collecting sinks are mutable,
task-owned and not thread-safe. Share immutable batches only when referenced
codes, context values and causes remain stable. The factory has no mutable
state, but sharing it requires a thread-safe clock. Sink thread safety depends
on the supplied callbacks. None of these objects owns a thread or transaction.

- Null required arguments, metadata, context keys/values or list elements cause
  `NullPointerException`. Optional result values may be null; optional causes
  are represented by omitting the builder call, not by `cause(null)`.
- Blank code IDs, nonpositive budgets, configured markers passed as real
  occurrences, inconsistent counts, invalid deltas or hidden rejecting signals
  cause `IllegalArgumentException`.
- Count overflow throws `ArithmeticException`. Bulk operations may have accepted
  earlier entries before a failure. Snapshot/count validation checks arithmetic
  and retained evidence, not the truth of a producer's omitted observations.

## IOC integration, sources and maintenance

IOC adds `IocDiagnosticCode` categories, message templates, catalogs, exceptions,
rendering and logging in local modules. The generic API has no IOC category.
IOC's renderer can fall back to the literal ID for a generic code. Old
`com.iocextractor.diagnostics` model imports have moved to
`com.iocextractor.platform.diagnostics`; there are no old-package aliases.
Replace old `Notification.throwIfRejected` usage with explicit policy evaluation
and service-owned enforcement, and supply marker/fallback configuration explicitly.

- [Module reference](../../../platform/platform-diagnostics-api/README.md),
  [value API](../../../platform/platform-diagnostics-api/src/main/java/com/iocextractor/platform/diagnostics/),
  [results and accumulation](../../../platform/platform-diagnostics-api/src/main/java/com/iocextractor/platform/diagnostics/result/)
  and [delivery](../../../platform/platform-diagnostics-api/src/main/java/com/iocextractor/platform/diagnostics/sink/)
  contain the implementations and public/package Javadoc.
- [Module tests](../../../platform/platform-diagnostics-api/src/test/java/com/iocextractor/platform/diagnostics/)
  check identity, shallow copies, policies, counts, sample replacement, merge
  failure, markers and delivery. [The standalone consumer](../../../tools/fixtures/diagnostics-consumer/README.md)
  compiles and runs all three actual examples above against a resolved JAR.
- [Publication contracts](../../../tools/tests/library-publication-test.py)
  check extraction and EN/RU Java/XML parity. Run
  `make test-module MODULE=platform/platform-diagnostics-api`,
  `make library-publication-test` and `make docs` from the repository root.
- For isolated packaged usage, follow the bundle/consumer commands in the
  [publication guide](../library-publication.md#local-packaging-qualification).
  [ADR 0044](../../ADR/0044-reusable-diagnostics-contract.md) owns the architecture
  and compatibility decision; [observability](../../dev/observability.md)
  explains IOC's integration.

Product and library versions remain aligned. During 0.x, breaking public API
or documented behavior needs a new minor version and migration notes; patches
preserve the contract. A real second service, arbitrary custom policies over
omitted detail and wire/persistence compatibility remain outside qualification.
Update both language versions and their examples when these behaviors change.
