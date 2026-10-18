# Using the diagnostics library

Use this library when a service needs structured diagnostics alongside a result,
a pure decision about stopping work, or bounded diagnostic samples with exact
counts. It requires JDK 21. It has no production dependencies outside the JDK.
The service owns its codes, messages, logging, exceptions and transactions.

## Dependency and status

The component is implemented in this repository at the shared reactor version,
currently `0.3.0-SNAPSHOT`. It is not independently published yet. The following
coordinates identify the planned consumer dependency; resolving it from a public
repository requires a subsequent component release:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-diagnostics-api</artifactId>
  <version>0.3.0-SNAPSHOT</version>
</dependency>
```

All examples below use classes from `com.iocextractor.platform.diagnostics`,
`.result` and `.sink`. Their executable counterpart is
[`DiagnosticsLibraryExampleTest`](../../platform/platform-diagnostics-api/src/test/java/com/iocextractor/platform/diagnostics/DiagnosticsLibraryExampleTest.java).
Run `make test-module MODULE=platform/platform-diagnostics-api` in this checkout.
For independent packaging qualification, run:

```sh
make library-bundle LIBRARY_COMPONENT=diagnostics LIBRARY_BUNDLE=.dev/diagnostics-bundle
make library-consumer LIBRARY_COMPONENT=diagnostics LIBRARY_BUNDLE=.dev/diagnostics-bundle
```

The [standalone consumer](../../tools/fixtures/diagnostics-consumer/README.md)
executes all three examples below outside the checkout with an empty Maven
cache. The [publication guide](library-publication.md) describes artifact and
repository checks. Local qualification does not make the coordinate public.
The [module reference](../../platform/platform-diagnostics-api/README.md) lists
package responsibilities; public Javadoc supplies member-level contracts.

## Define your codes and clock

```java
private enum ImportCode implements DiagnosticCode {
    BAD_ROW, SUPPRESSED;

    @Override public String id() {
        return "import/" + name().toLowerCase(java.util.Locale.ROOT);
    }
    @Override public DiagnosticSeverity defaultSeverity() {
        return DiagnosticSeverity.WARN;
    }
    @Override public DiagnosticImpact impact() {
        return DiagnosticImpact.ELEMENT;
    }
}

Clock clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
DiagnosticFactory factory = new DiagnosticFactory(clock);
Diagnostic warning = factory.create(ImportCode.BAD_ROW).with("row", 7).build();
```

In production, supply your service's clock, for example `Clock.systemUTC()`.
IDs must be nonblank and stable; no `GROUP.CODE` naming scheme is required.
The consuming service must keep IDs unique and code metadata/equality stable.
Null code, clock, severity, impact, context keys or context values are rejected
with `NullPointerException`; a blank ID causes `IllegalArgumentException`.

The context map is copied, while its values and optional Throwable are shared
references. Keep those values stable. Diagnostic equality compares the **code
object, context and effective severity**; timestamp and cause are excluded.
Equal occurrences still count separately. `toString()` exposes raw context;
apply your own redaction before operational logging.

## Keep values and failure decisions separate

```java
Result<String> result = Result.success("accepted")
        .withDiagnostic(warning)
        .map(String::toUpperCase);
Notification notification = new Notification().addAll(result.diagnostics());
FailureDecision decision = FailurePolicy.failFast().evaluate(notification);
```

The mapped value is `ACCEPTED`; WARN does not stop either built-in policy.
`failFast()` selects the first ERROR or FATAL in the supplied notification's list;
`collectAndContinue()` selects only FATAL. The service must act on the decision
before committing durable changes, for example by throwing its own exception
carrying `decision.diagnostic().orElseThrow()`. Impact alone never permits a write,
a skipped row or a retry.

`Result.success(null)` is valid. `map` skips its mapper for a null value but still
requires a non-null mapper; mapper exceptions propagate unchanged. Results copy
the diagnostics list, not the result value. Custom policies must return a
non-null decision without I/O, mutation or thrown exceptions.
Bounded samples preserve the built-in ERROR/FATAL stopping signals. A custom
policy that needs every code or reason must evaluate the full observations
before compaction; omitted detail cannot be recovered from a retained sample.

## Bound detail and preserve counts

```java
var stage = new BoundedDiagnosticCollector(1, ImportCode.SUPPRESSED);
for (var severity : List.of(DiagnosticSeverity.WARN, DiagnosticSeverity.WARN,
        DiagnosticSeverity.ERROR, DiagnosticSeverity.FATAL)) {
    stage.add(factory.create(ImportCode.BAD_ROW).severity(severity).build());
}
var delivered = new CollectingDiagnosticSink();
var run = new BoundedNotification(1, factory, ImportCode.SUPPRESSED);
run.addAll(stage.batch(), delivered::emit);
```

| Snapshot | Retained detail | Observed total | Suppressed |
|---|---|---:|---:|
| Stage batch | first WARN, ERROR, FATAL | 4 | 1 |
| Run batch | FATAL | 4 | 3 |
| Run display (`diagnostics()`) | FATAL, synthetic marker at ERROR | 4 | 3 |

The stage keeps the first N budgeted samples plus its first ERROR/FATAL if needed
(up to N+2). The run keeps at most N budgeted samples, replacing the earliest
lowest-severity sample when a first ERROR/FATAL arrives. Both keep **every
OPERATION occurrence outside the budget**. Unbounded OPERATION input can therefore
still grow memory. Repeated equal diagnostics are separate occurrences.

The callback above receives three occurrences: WARN, ERROR, FATAL. Replacement
cannot retract a delivery, so the retention limit is not a lifetime limit on
callback calls. Stage and run should use the same budget to preserve the direct
run sample. Smaller stage budgets can lose samples while preserving exact totals.
For example, a stage budget of 1 receiving WARN A, WARN B and ERROR C retains A
and C. A run budget of 3 cannot recover B from that batch: it retains A and C,
counts all three occurrences and reports one suppressed WARN. Direct accumulation
at budget 3 would retain all three. Both paths preserve the observed severity
counts; their retained samples and suppression counts can differ.

Run replacement uses the displaced sample's list position. With budget 2,
WARN A, ERROR B and FATAL C leave the sample `[C, B]`. A fail-fast decision over
that sample selects C, although B arrived first. Compaction preserves the
built-in stopping signals; it does not promise the original failure reason or
chronological sample order. Delivery follows the input batch's retained order.

`DiagnosticBatch.of(retained, summary, suppressionCode)` copies and validates
retained size, severity counts and rejecting signals. An observed ERROR/FATAL
must have a retained ERROR/FATAL, and an observed FATAL must retain a FATAL.
`DiagnosticSummary.plus` adds independent counts; `since` computes a monotonic
delta. Invalid counts cause `IllegalArgumentException`; long overflow causes
`ArithmeticException`. Counts cannot prove the provenance of omitted occurrences:
use the **same stable marker code throughout the aggregation tree**.

`batch()` excludes synthetic markers and is suitable for merging. `diagnostics()`
is a display snapshot; it appends a marker only when something was omitted.
Never feed that marker back as an occurrence. Collectors reject their configured
marker by code equality before counting it. The marker context contains:

- `limit`: integer detail budget;
- `suppressedCount`: long omitted count, here `3L`;
- `suppressedBySeverity`: immutable map of severity names to long counts, here
  `{"WARN": 2L, "ERROR": 1L}`.

Its severity is the highest omitted severity. Snapshot calls deliver nothing,
change no counts and may use a new marker timestamp. The summary counts observed
occurrences only; the marker does not add another occurrence.

Builders, notifications, collectors and collecting sinks belong to one task and
are not thread-safe. Hand immutable batches across task boundaries only when
their shared context values and codes remain stable. A bulk add
can fail after partial mutation; it is not a transaction.

## Isolate delivery failures

```java
var failures = new ArrayList<RuntimeException>();
DiagnosticSink sink = new ResilientDiagnosticSink(
        value -> { throw new IllegalStateException("report unavailable"); },
        (value, failure) -> failures.add(failure));
sink.emit(warning);
```

The failure callback receives the original diagnostic and exception once. The
wrapper contains runtime exceptions from both callbacks, without recursion or
retry. JVM Errors propagate unchanged. Null input is rejected before callbacks.
Delivery is synchronous, so a slow callback delays the caller; no worker, timeout,
queue or durable delivery is provided. The service owns callback resources and
thread safety. A raw bounded-merge callback failure propagates after partial
mutation: pass a resilient sink when reporting must not abort processing.
The failing occurrence has already been counted and retained. Later retained
occurrences and the batch's omitted counts have not been added. Overflow during
a merge also preserves earlier accepted additions. Replaying the entire batch
can count or deliver those additions again; the API provides no rollback or
retry identity.

## IOC integration and compatibility

[ADR 0044](../ADR/0044-reusable-diagnostics-contract.md) records the accepted
boundary and compatibility policy. Product and library versions remain aligned.
During 0.x, breaking API or documented-behavior changes require a minor version
and migration notes; patches preserve the contract.

IOC extends `DiagnosticCode` locally as `IocDiagnosticCode` for categories,
message keys and templates. Those catalogs, `DiagnosticException`, redacting
renderers and logging bridges are separate from the reusable API. A generic code
has no IOC category; IOC rendering falls back to its literal ID.

This extraction changes imports from `com.iocextractor.diagnostics` to
`com.iocextractor.platform.diagnostics`. There is one shared Diagnostic model,
with no old-package aliases. Replace `Notification.throwIfRejected` with policy
evaluation and caller-owned enforcement. Supply the suppression code explicitly
to bounded collectors/batches and a callback to `ResilientDiagnosticSink`.
Old product exception ancestry and IOC catalog IDs remain product contracts.
