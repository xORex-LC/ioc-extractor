# План реализации этапа 10: ingest daemon

## Цель

Сделать основным режимом приложения долгоживущий **whole-file ingest daemon**,
который обнаруживает новые source-файлы, атомарно claim-ит их, запускает
существующий IOC ETL pipeline, пишет результат в partition artifacts и фиксирует
durable статусы обработки. CLI `extract --source` остаётся поддержанным
`oneshot`-режимом, но больше не диктует lifecycle приложения.

Этап 10 **не** реализует aggregator, stable global ids, tail-ingestion,
retention, Actuator/health и SQLite-ledger. Эти части остаются на этап 11 или
после него.

## Опорные решения

- Published design: [ingestion.md](../ingestion.md),
  [notes/0001](../notes/0001-streaming-ingestion.md).
- Runtime mode: `ioc.runtime.mode = oneshot | daemon`.
- Ingest adapter: новый Maven-модуль `adapters/adapter-ingest`.
- Source detection: Spring Integration file inbound adapter, watch as
  low-latency path + explicit reconciliation/polling as safety net.
- Idempotency: `IngestionLedger` по source key/content hash; SI accept-once
  filter только оптимизация.
- Output: partition wrapper в adapter layer. Core `IocSink` API не расширяем на
  этапе 10.
- Health/Actuator: после этапа 11.

## Границы этапа

Входит:

- root reactor update: добавить `adapters/adapter-ingest`;
- runtime split: `oneshot` vs `daemon`;
- conditional CLI runner, daemon-safe application exit behavior;
- `IngestSourceUseCase` и application-level orchestration;
- driven ports `IngestionLedger` и `SourceLifecycle`;
- whole-file source unit model, source key/content hash;
- filesystem source lifecycle adapter: `inbox -> processing -> done|failed`;
- file ledger adapter with durable statuses and compensation support;
- Spring Integration file flow in `adapter-ingest`;
- partition wrapper around CSV sinks for daemon output;
- retry/dead-letter flow and `.error` sidecar;
- diagnostics producers for initial ingest/read/write/ledger failures;
- tests and CI coverage for daemon baseline.

Не входит:

- tail source support;
- aggregator from partitions to canonical artifacts;
- stable global `dedupKey -> id`;
- partition retention/reaper;
- Actuator/health/metrics;
- SQLite/JDBC ledger;
- multi-worker concurrency beyond single-thread-ready invariants.

## Target Module Map

| Модуль | Изменения |
|---|---|
| root `pom.xml` | добавить `adapters/adapter-ingest`, dependency management for Spring Integration/Spring Retry if needed |
| `core/ioc-application` | `IngestSourceUseCase`, ingestion command/result, `IngestionLedger`, `SourceLifecycle`, `PartitionSinkFactory` port, orchestration service |
| `adapters/adapter-ingest` | SI file flow, file source lifecycle, file ledger, source hashing, retry/dead-letter adapter code |
| `adapters/adapter-sink-csv` | partition wrapper/path resolver using existing CSV row mapping/writer pieces |
| `bootstrap/ioc-app` | runtime properties, conditional CLI/daemon wiring, daemon-safe main behavior, app config for ingest |
| `docs/` | sync docs if implementation changes contracts |

## Runtime Mode

### `ioc.runtime.mode`

Add to `IocProperties`:

```java
Runtime runtime

record Runtime(@NotBlank String mode) {}
```

Default in `application.yml`:

```yaml
ioc:
  runtime:
    mode: oneshot
  observability:
    mode: oneshot
```

Rules:

- `oneshot`: `CliRunner` active; process returns picocli exit code through
  Spring exit handling.
- `daemon`: `CliRunner` inactive; ingest flow active; application does not call
  `System.exit` immediately after context start.
- `ioc.observability.mode` remains log taxonomy field. It may default to runtime
  mode in bootstrap wiring/config, but must not own application lifecycle.

### Application entry point

Current `IocExtractorApplication.main` unconditionally calls:

```java
System.exit(SpringApplication.exit(SpringApplication.run(...)));
```

Change to mode-aware behavior. Acceptable implementation:

- run Spring application first;
- read `IocProperties` or environment after context start;
- call `SpringApplication.exit(context)` + `System.exit(code)` only for
  `oneshot`;
- leave daemon context running for `daemon`.

Keep this logic in `bootstrap/ioc-app`; no lower module should know process exit.

### CLI activation

Make `CliRunner` active only for `ioc.runtime.mode=oneshot`.

Preferred mechanism:

- `@ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "oneshot", matchIfMissing = true)`
  in adapter or bootstrap configuration.

Avoid checking runtime mode inside `CliRunner.run(...)`; activation belongs to
wiring, not business logic.

## Application Contracts

Package proposal in `core/ioc-application`:

```text
com.iocextractor.application.port.in.ingest/
  IngestSourceUseCase.java
  IngestSourceCommand.java
  IngestSourceResult.java

com.iocextractor.application.port.out.ingest/
  IngestionLedger.java
  SourceLifecycle.java
  PartitionSinkFactory.java

com.iocextractor.application.ingest/
  IngestionService.java
  IngestionStatus.java
  SourceUnit.java
  SourceKey.java
```

If local conventions prefer no extra `ingest` subpackage under `port`, use
`application.port.in` / `application.port.out` with clear class names. The key
rule is unchanged: ports in application, implementations in adapters.

### `IngestSourceUseCase`

Responsibility:

- handle one already-detected source unit;
- coordinate ledger, source lifecycle and existing `ExtractIocsUseCase`;
- enforce status transition order;
- emit diagnostics on recoverable/unrecoverable ingest failures.

Must not:

- know Spring Integration classes;
- scan directories;
- parse include/exclude patterns;
- know CSV implementation details.

Sketch:

```java
public interface IngestSourceUseCase {
    IngestSourceResult ingest(IngestSourceCommand command);
}
```

`IngestSourceCommand` should carry:

- original source path;
- claimed/processing path or source id;
- source key/content hash;
- observed timestamp if useful;
- dry-run flag only if we explicitly support daemon dry-run.

### `IngestionLedger`

Responsibility:

- durable upsert/read of processing status by source key;
- list incomplete records for startup recovery;
- be idempotent for repeated updates to same status.

Statuses:

```text
CLAIMED
PARTITION_WRITTEN
LEDGER_RECORDED
SOURCE_ARCHIVED
FAILED
```

`AGGREGATED` is reserved for stage 11.

Minimal methods:

```java
Optional<IngestionRecord> find(SourceKey key);
void markClaimed(SourceUnit unit);
void markPartitionWritten(SourceKey key, List<Path> partitions);
void markLedgerRecorded(SourceKey key);
void markSourceArchived(SourceKey key, Path archivedPath);
void markFailed(SourceKey key, String reason);
List<IngestionRecord> findIncomplete();
```

Exact signatures may differ, but they must support compensation decisions
without parsing adapter-specific side effects.

### `SourceLifecycle`

Responsibility:

- atomically claim source into `processing`;
- archive source into `done`;
- move failed source into `failed`;
- write `.error` sidecar.

Must not:

- run extraction;
- decide business status transitions;
- own ledger.

### `PartitionSinkFactory`

Responsibility:

- create daemon-scoped `IocSink` instances bound to one source key/content hash;
- hide partition path strategy from application orchestration;
- keep existing `IocSink.write(List<Indicator>)` unchanged.

Must not:

- know Spring Integration;
- own ledger/status transitions;
- assign global stable ids.

Sketch:

```java
public interface PartitionSinkFactory {
    List<IocSink> createFor(SourceUnit source);
}
```

Implementation lives in `adapter-sink-csv`; application depends only on this
port. Bootstrap wires this factory into `IngestionService`.

## Adapter-Ingest Module

Create:

```text
adapters/adapter-ingest/
├── pom.xml
├── README.md
└── src/main/java/com/iocextractor/adapter/in/ingest/
```

Dependencies:

- `ioc-application`;
- `ioc-platform-errors`;
- `ioc-platform-diagnostics` if diagnostics mapping lives here;
- Spring Integration file support;
- Spring Retry if retry advice is configured in adapter.

No dependency on concrete CSV sink internals unless strictly required. Partition
output belongs to `adapter-sink-csv`, wired in bootstrap.

Suggested classes:

```text
IngestFlowConfiguration.java
FileSourceMessageHandler.java
FileSourceUnitMapper.java
FileSourceHasher.java
FileSystemSourceLifecycle.java
FileIngestionLedger.java
IngestionErrorSidecarWriter.java
IngestionProperties.java (only if not folded into IocProperties)
```

### Detection Flow

Baseline:

- `FileReadingMessageSource`;
- include/exclude filters;
- `LastModifiedFileListFilter` or equivalent quiet-period filter;
- persistent accept-once filter as fast duplicate suppression;
- explicit reconciliation poller.

Important invariant: accept-once filter is not authoritative. If it says “new”,
we still consult `IngestionLedger` by content hash/source key before processing.

### Claim Flow

For each eligible file:

1. compute content hash/source key while source is still in inbox;
2. if ledger says source is already `SOURCE_ARCHIVED`, skip/archive duplicate
   according to policy;
3. atomically move file to `processing`;
4. mark `CLAIMED`;
5. call `IngestSourceUseCase`.

If hash-before-move is too risky for actively written files, quiet-period filter
must be strict enough and tests must cover “file still changing is not processed”.

## Partition Output Wrapper

Decision: do **not** extend core `IocSink` API in stage 10. Add a small
application port `PartitionSinkFactory`; implement it in `adapter-sink-csv`.

Implement partition behavior in adapter layer:

- keep existing `CsvIocSink` and `ConfigurableRowMapper` behavior for oneshot;
- add a daemon-only wrapper/factory in `adapter-sink-csv` that creates per-source
  artifact paths under `dataframe/partitions`;
- wrapper delegates row mapping/writing to existing CSV components, but resolves
  path from source key/content hash.

Possible shape:

```text
PartitionPathResolver
PartitionedCsvIocSinkFactory
PartitionedCsvIocSink
PartitionWriteContextHolder (only if scoped carefully)
```

Prefer avoiding thread-local context unless absolutely necessary. Better options:

- build daemon-specific sinks per source in `IngestionService`; or
- wrap `ExtractIocsUseCase` construction per source with sinks bound to a
  partition path; or
- add an application-level `PartitionWriter` port separate from `IocSink`.

Design constraint: no domain logic changes and no source-key leaking into domain.

The plan default is **daemon-specific sink factory as an application port**:

```text
IngestionService receives PartitionSinkFactory (port),
adapter-sink-csv implements it,
bootstrap wires implementation into the use case,
IngestionService builds per-source IocSink list and delegates to the same stage chain.
```

If the current `IocExtractionService` constructor makes per-source sink binding
awkward, introduce a small application-level extraction factory port/service
rather than adding global mutable state to sinks.

## Recovery and Compensation

On daemon startup:

1. `IngestionLedger.findIncomplete()`;
2. for each record:
   - `CLAIMED`: rerun extraction from processing path;
   - `PARTITION_WRITTEN`: ensure ledger record is durable, then continue archive;
   - `LEDGER_RECORDED`: archive source;
   - `FAILED`: leave in failed unless retry policy says otherwise;
3. scan `processing/` for orphan files without ledger records and move them back
   to `inbox` or mark failed with diagnostics.

All compensation operations must be idempotent.

## Diagnostics and Logging

Stage 10 should start closing D1 from roadmap by producing diagnostics for real
failures.

Add initial diagnostic codes if missing:

```text
INGEST.CLAIM_FAILED
INGEST.LEDGER_WRITE_FAILED
INGEST.RECOVERY_FAILED
SOURCE.READ_FAILED
SINK.WRITE_FAILED
```

Map them through existing `DiagnosticSink`/`LoggingDiagnosticSink`; do not make
logging the source of truth.

Operational log events should add/confirm actions in logging taxonomy:

```text
ingest.detect
ingest.claim
ingest.skip_duplicate
ingest.partition_written
ingest.archive
ingest.failed
ingest.recovery
```

Keep MDC correlation derived from Envelope/source metadata. Ensure daemon worker
threads do not leak MDC.

## Configuration

Extend `IocProperties`:

```java
Runtime runtime;
Ingestion ingestion;
```

Suggested `application.yml`:

```yaml
ioc:
  runtime:
    mode: oneshot

  ingestion:
    dirs:
      inbox: ./var/inbox
      processing: ./var/processing
      done: ./var/done
      failed: ./var/failed
    patterns:
      include: ["*.htm", "*.html", "*.docx"]
      exclude: ["*.tmp", "*.part", ".*"]
    detect:
      use-watch-service: true
      reconcile-interval: 30s
      max-messages-per-poll: 50
    stability:
      quiet-period: 10s
    output:
      partitions-dir: ./dataframe/partitions
    retry:
      max-attempts: 3
      backoff: 5s
    ledger:
      type: file
      path: ./var/ledger
    concurrency: 1
```

Validation:

- directory paths not blank;
- include patterns not empty;
- quiet-period positive;
- retry attempts >= 1;
- concurrency = 1 for stage 10.

## Test Contour

### Unit Tests

`core/ioc-application`:

- `IngestionServiceTest`
  - new source follows `CLAIMED -> PARTITION_WRITTEN -> LEDGER_RECORDED -> SOURCE_ARCHIVED`;
  - duplicate archived source is skipped;
  - extraction failure moves to failed and records diagnostic;
  - recovery decisions by status.

`adapters/adapter-ingest`:

- `FileSourceHasherTest` with deterministic SHA-256;
- `FileSystemSourceLifecycleTest` using `@TempDir`;
- `FileIngestionLedgerTest` using `@TempDir`, including reload from disk;
- `IngestFlowConfigurationTest` for filters/properties if practical without
  timing-flaky tests.

`adapters/adapter-sink-csv`:

- partition path resolver case-table;
- partition wrapper writes expected CSV under `partitions/<artifact>/<date>/<hash>.csv`;
- repeated write overwrites same partition atomically.

### Integration/E2E Tests

`bootstrap/ioc-app`:

- `OneshotRuntimeModeTest`: CLI runner active by default; existing
  `GoldenPipelineTest` remains green.
- `DaemonRuntimeModeTest`: CLI runner inactive; ingest beans active.
- `DaemonIngestionE2ETest` with `@TempDir`:
  - drop one source into inbox;
  - run ingest flow/service without sleeping on real watcher where possible;
  - verify source moved to done;
  - verify ledger status `SOURCE_ARCHIVED`;
  - verify partition files and row counts;
  - drop duplicate content under another name and verify it is not processed
    twice.
- `RecoveryE2ETest`:
  - pre-seed ledger and processing file in `CLAIMED` or `LEDGER_RECORDED`;
  - start recovery;
  - verify compensation.

Avoid tests that depend on real wall-clock watcher latency. Prefer direct
invocation of message handler/use case with `@TempDir`; keep one smoke test for
Spring Integration wiring only if stable.

### CI Gate

Keep single root gate:

```bash
./mvnw -B -ntp -T 1C verify
```

Add targeted commands to the plan/doc for local iteration:

```bash
./mvnw -B -ntp -pl adapters/adapter-ingest -am test
./mvnw -B -ntp -pl core/ioc-application -am test
./mvnw -B -ntp -pl bootstrap/ioc-app -am verify
```

## Architecture Guardrails

Update `ArchitectureTest` / module-local tests:

- `adapter-ingest` may depend on Spring Integration;
- `ioc-application` must not depend on Spring Integration or adapter packages;
- `PartitionSinkFactory` is a port in `ioc-application`; implementation in
  `adapter-sink-csv` must not leak back through application imports;
- `ioc-domain` remains unaware of ingest, ledger, partitions and Spring;
- `platform-etl` remains generic, no ingest/application dependency;
- CLI adapter active only in `oneshot` wiring.

Update docs if guardrail names change.

## Implementation Order

1. Update docs and plan index.
2. Add `ioc.runtime.mode` to config/properties and make CLI conditional.
3. Make application entry point daemon-safe.
4. Add application ingest contracts and `IngestionService`.
5. Add `adapter-ingest` module and root reactor/dependency management entries.
6. Implement filesystem lifecycle and file ledger.
7. Implement Spring Integration whole-file flow and message handler.
8. Implement partition wrapper/path resolver in `adapter-sink-csv`.
9. Wire daemon beans in `ioc-app`.
10. Add diagnostics codes/producers for ingest baseline.
11. Add tests by module, then daemon e2e.
12. Run full `verify`, update docs/roadmap if implementation changes details.

## Acceptance Criteria

- `./mvnw -B -ntp -T 1C verify` green.
- Default `oneshot` behavior and existing golden output unchanged.
- `ioc.runtime.mode=daemon` starts without running CLI command and without
  immediate `System.exit`.
- Whole-file source in inbox is claimed, processed, partitioned, recorded in
  ledger and archived.
- Duplicate content is not processed twice.
- Recovery handles at least `CLAIMED`, `PARTITION_WRITTEN`, `LEDGER_RECORDED`.
- Failure after retries moves source to `failed/` and writes `.error` sidecar.
- Spring Integration dependencies are isolated to `adapter-ingest`/bootstrap.
- No domain/application dependency on Spring Integration or filesystem adapter
  implementations.
- Health/Actuator remains absent from stage 10 implementation and documented as
  post-stage-11.

## Risks

| Риск | Мера |
|---|---|
| Watcher tests flaky | Test use case/message handler directly; keep watcher as wiring smoke only |
| Partition wrapper needs source context | Bind per-source sinks/factory in application/bootstrap; avoid thread-local mutable state |
| Ledger and file moves drift | Status-driven compensation tests with `@TempDir` |
| Duplicate suppression split-brain | Ledger is authoritative; SI accept-once only optimization |
| `System.exit` kills daemon | Mode-aware main + daemon runtime test |
| Stage 10 absorbs stage 11 | Acceptance criteria explicitly exclude aggregator/stable global ids/retention/health |
