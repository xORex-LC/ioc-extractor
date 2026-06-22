# План реализации этапа 11: daemon finalization

## Цель

Довести daemon-режим до законченного runtime-контура:

```text
inbox -> ingest -> partitions -> aggregation -> canonical artifacts -> health
```

Этап 10 уже умеет принимать whole-file источники, писать source-scoped
partition artifacts и фиксировать durable статусы. Этап 11 добавляет
single-writer агрегацию партиций в канонические артефакты, стабильные
глобальные id, artifact-aware lookup для последующих прогонов и минимальный
health/readiness контур.

Ключевое ограничение: **Aggregator не дедуплицирует сам**. Aggregator — это
application-level process manager. Уникальность, stable id и row upsert
выносятся в отдельные контракты/сервисы, а CSV-детали остаются в adapter layer.

## Опорные решения

- Published design: [ingestion.md](../ingestion.md),
  [notes/0001](../notes/0001-streaming-ingestion.md),
  [services.md](../services.md), [modularization.md](../modularization.md).
- Stage 10 уже реализован:
  - `ioc.runtime.mode = oneshot | daemon`;
  - `adapters/adapter-ingest`;
  - `IngestSourceUseCase`, `IngestionLedger`, `SourceLifecycle`;
  - partition output через `PartitionSinkFactory`;
  - retry/recovery hardening.
- Stage 11 включает health/Actuator как часть финализации daemon-модели.
- Retention/Reaper не реализуется на этапе 11. Фиксируем как technical debt,
  `enabled: false` по умолчанию.
- Stable id index на этапе 11 — **sidecar CSV**. SQLite/JDBC ledger/index —
  явный technical debt и future migration.
- Aggregation trigger: scheduled daemon aggregation + use case, все интервалы и
  включатели через config.
- Canonical output path — существующий `ioc.sink.artifacts[].path`.
- Partition output остаётся в отдельной директории
  `ioc.ingestion.output.partitions-dir`.
- Conflict policy на этапе 11 — `keep-first`. Более богатая модель merge/update
  фиксируется как technical debt.
- Hash-aware lookup не оставляем вне этапа 11: lookup должен стать
  artifact-aware для masks и hashes.

## Границы этапа

Входит:

- application use case `AggregatePartitionsUseCase`;
- application service `AggregationService`;
- разделение aggregation orchestration и identity/upsert responsibilities;
- stable id sidecar CSV index;
- canonical artifact repository для чтения/атомарной записи canonical CSV;
- artifact-aware lookup для network + file IOC;
- ledger extension: `AGGREGATED` и методы выборки готовых к агрегации записей;
- scheduled daemon aggregation trigger;
- конфигурация aggregation schedule/enabled/index paths;
- минимальный Actuator health/readiness для daemon;
- тесты по application, CSV adapters, bootstrap wiring и daemon e2e contour;
- синхронизация `roadmap.md`, `ingestion.md`, `services.md`,
  `output-mapping.md`, `logging-taxonomy.md`, `diagnostic-catalog.md` при
  добавлении кодов/событий.

Не входит:

- tail source support;
- SQLite/JDBC ledger/index implementation;
- retention/reaper implementation;
- conflict merge model сложнее `keep-first`;
- multi-worker aggregation;
- UI/API для управления aggregation;
- перенос текущего file ledger на новую storage-модель.

## Target Module Map

| Модуль | Изменения |
|---|---|
| `core/ioc-application` | aggregation use case, application service, artifact row model, identity/upsert ports, ledger contract extension |
| `adapters/adapter-sink-csv` | CSV partition reader, canonical artifact reader/writer, stable id sidecar CSV, artifact-aware row key extraction |
| `adapters/adapter-lookup-csv` | заменить mask-only lookup на artifact-aware canonical lookup либо добавить новый adapter за тем же портом |
| `adapters/adapter-ingest` | file ledger support for `AGGREGATED`; no aggregation logic |
| `bootstrap/ioc-app` | config binding, scheduled aggregation runner, Actuator dependency/wiring, health indicators |
| `platform/platform-diagnostics` | optional aggregation diagnostic codes |
| `platform/platform-observability` | optional aggregation `event.action` values |
| `docs/` | sync stage 10/11 status and final decisions |

## Responsibility Map

### AggregationService

Ответственность:

- найти source records, готовые к aggregation;
- запросить partition rows у repository;
- передать rows в canonical artifact writer/upsert service;
- зафиксировать `AGGREGATED` в ledger;
- вернуть summary;
- быть идемпотентным при повторном запуске.

Не должен:

- читать CSV напрямую;
- знать delimiter/header/null literal;
- вычислять artifact-specific stable key из CSV-схемы;
- назначать id вручную;
- удалять partitions;
- смешивать ingest claim/retry lifecycle.

### Artifact Identity / Upsert

Ответственность:

- получить stable row key;
- найти/создать stable id;
- применить `keep-first` policy;
- гарантировать единственность canonical row per key.

Не должен:

- сканировать inbox/processing;
- управлять Spring schedule;
- принимать решения о source lifecycle.

### CSV Adapter

Ответственность:

- читать partition CSV rows;
- читать canonical CSV rows;
- писать canonical artifact атомарно (`*.tmp` -> move);
- читать/писать stable id sidecar CSV;
- применять CSV dialect (`;`, quote, `NULL`);
- извлекать key по artifact schema/config.

Не должен:

- содержать application process flow;
- зависеть на Spring scheduling;
- знать `IngestionService`.

### IngestionLedger

Ответственность:

- durable status per source key;
- хранить partition paths;
- отдавать records ready for aggregation;
- фиксировать `AGGREGATED`.

Не должен:

- читать canonical artifacts;
- назначать stable ids;
- удалять partitions.

## Application Contracts

Предлагаемая структура:

```text
core/ioc-application/src/main/java/com/iocextractor/application/aggregation/
  AggregationService.java
  AggregationStatus.java         (если нужен отдельный enum/summary)
  AggregationBatch.java
  AggregationResult.java
  ArtifactRow.java
  ArtifactRowKey.java
  StableArtifactId.java
  ArtifactMergePolicy.java
  KeepFirstMergePolicy.java

core/ioc-application/src/main/java/com/iocextractor/application/port/in/aggregation/
  AggregatePartitionsUseCase.java
  AggregationCommand.java
  AggregationResult.java          (можно держать здесь вместо application/aggregation)

core/ioc-application/src/main/java/com/iocextractor/application/port/out/aggregation/
  PartitionArtifactRepository.java
  CanonicalArtifactRepository.java
  StableIdIndex.java
  ArtifactIdentityResolver.java
```

Имена можно уточнить при реализации, но граница обязательна: use case
оркестрирует, ports скрывают storage/schema details.

### `AggregatePartitionsUseCase`

```java
public interface AggregatePartitionsUseCase {
    AggregationResult aggregate(AggregationCommand command);
}
```

`AggregationCommand`:

- `boolean force` — опционально; для stage 11 можно не включать;
- `Instant startedAt` или clock внутри service;
- optional artifact filter для тестов/будущего CLI.

`AggregationResult`:

- processed source count;
- aggregated partition count;
- per artifact rows read;
- per artifact rows written;
- new stable ids count;
- unchanged/duplicate rows count;
- failed source keys / diagnostics if introduced.

### `PartitionArtifactRepository`

Ответственность: получить rows из partition paths, уже записанных stage 10.

Sketch:

```java
List<PartitionArtifact> readPartitions(List<IngestionRecord> records);
```

Где `PartitionArtifact` содержит:

- `SourceKey sourceKey`;
- `String artifactName`;
- `Path path`;
- `List<ArtifactRow> rows`;

### `CanonicalArtifactRepository`

Ответственность: читать/писать canonical artifacts по artifact name.

Sketch:

```java
CanonicalArtifact load(String artifactName);
void write(String artifactName, CanonicalArtifact artifact);
```

Repository не решает, какие rows оставить. Он только storage adapter за портом.

### `ArtifactIdentityResolver`

Ответственность: stable key extraction.

Sketch:

```java
ArtifactRowKey keyOf(String artifactName, ArtifactRow row);
```

Stage 11 default:

- `masks`: key column `mask`;
- `hashes`: first non-empty of `hash_md5`, `hash_sha1`, `hash_sha256`;
- `id` excluded;
- `NULL`/blank normalized as empty;
- key normalized consistently with artifact rules.

Важно: этот resolver не принадлежит domain. Он зависит от artifact schema.
Реализация живёт в CSV adapter/config.

### `StableIdIndex`

Ответственность: stable id per `artifactName + rowKey`.

Sketch:

```java
OptionalLong find(String artifactName, ArtifactRowKey key);
long getOrCreate(String artifactName, ArtifactRowKey key);
void save();
```

Sidecar CSV format proposal:

```csv
artifact;key;id;created_at;updated_at
masks;example.com;123;2026-06-22T00:00:00Z;2026-06-22T00:00:00Z
hashes;sha256:ABC...;10024;...
```

File placement:

```text
dataframe/.ioc-id-index.csv
```

or configurable:

```yaml
ioc.aggregation.id-index.path: ./dataframe/.ioc-id-index.csv
```

Atomic write is mandatory.

### `ArtifactMergePolicy`

Stage 11 implementation: `KeepFirstMergePolicy`.

Rules:

- existing canonical row wins;
- if key is new, first partition row wins;
- partition processing order must be stable for deterministic output;
- richer merge/update model is technical debt.

## Ledger Extension

Add status:

```text
AGGREGATED
```

Updated lifecycle:

```text
CLAIMED
  -> PARTITION_WRITTEN
  -> LEDGER_RECORDED
  -> SOURCE_ARCHIVED
  -> AGGREGATED
```

`FAILED` remains terminal for failed ingestion.

Extend `IngestionLedger`:

```java
List<IngestionRecord> findReadyForAggregation();
void markAggregated(SourceKey key);
```

Optional if useful:

```java
List<IngestionRecord> findAggregated();
```

File ledger implementation:

- support reading old records without `AGGREGATED` by defaulting to existing
  statuses;
- `findIncomplete()` should still mean "not completed for ingestion lifecycle"
  or be renamed/split to avoid treating `SOURCE_ARCHIVED` as incomplete.

Recommended split:

- `findIncomplete()` for ingest recovery: excludes `SOURCE_ARCHIVED`,
  `AGGREGATED`, `FAILED`;
- `findReadyForAggregation()` returns `SOURCE_ARCHIVED` records with partitions;
- `markAggregated()` moves `SOURCE_ARCHIVED -> AGGREGATED`.

## Aggregation Algorithm

Deterministic baseline:

1. `records = ledger.findReadyForAggregation()`.
2. Sort records by `detectedAt`, then `sourceKey`.
3. `partitionRepository.readPartitions(records)`.
4. Group partition rows by `artifactName`.
5. For each artifact in configured artifact order:
   - load canonical artifact;
   - load stable id index;
   - build existing key -> row map from canonical rows;
   - for each partition row in stable order:
     - resolve row key;
     - if key exists: keep existing row;
     - if key is new:
       - `id = stableIdIndex.getOrCreate(artifact, key)`;
       - write `id` into row;
       - append row;
   - write canonical artifact atomically.
6. Save stable id index atomically.
7. Mark every successfully included source as `AGGREGATED`.
8. Return summary.

Failure model:

- If read/write/index save fails before `markAggregated`, source remains
  `SOURCE_ARCHIVED` and will be retried.
- Canonical artifact and id index writes must be atomic.
- Re-running aggregation must be idempotent:
  - same key -> same id;
  - existing row preserved by keep-first;
  - `AGGREGATED` records are skipped.

Open implementation detail:

- If one artifact write succeeds and another fails, records must not be marked
  `AGGREGATED`. Next run replays all `SOURCE_ARCHIVED` records. Because writes
  are deterministic and idempotent, replay is acceptable.

## Artifact-Aware Lookup

Stage 11 must replace or extend current `CsvMaskLookupRepository`, which is
mask-only.

Goal:

- extraction dedup reads canonical artifacts for both network and file IOC;
- masks and hashes are checked by correct artifact identity, not by a single
  `mask` column;
- `LookupRepository` remains application port.

Possible implementation:

```text
adapter-lookup-csv/
  CsvArtifactLookupRepository
```

It can reuse CSV artifact schema/key resolver logic:

- masks: check `mask`;
- hashes: check `hash_md5`, `hash_sha1`, `hash_sha256`;
- normalize according to existing output rules;
- load max id across configured canonical artifacts or expose per-artifact id if
  needed.

Risk:

- current `LookupRepository.maxId()` is global and was designed for masks.
  Stage 11 stable ids should no longer rely on this for daemon aggregation.
  For oneshot `id.start:auto`, current behavior can remain, but it should not
  be used for stable daemon ids.

Plan default:

- Keep `LookupRepository` API for extraction dedup.
- Implement artifact-aware `contains(Indicator)`.
- Leave `maxId()` behavior compatible with existing oneshot use.
- Stable daemon ids live in `StableIdIndex`, not in `LookupRepository`.

## Scheduling

Add scheduled daemon aggregation trigger in bootstrap or adapter-ingest
configuration.

Preferred placement:

- trigger/wiring in `bootstrap/ioc-app` or `adapter-ingest` only if it remains a
  driving adapter concern;
- use case stays in `ioc-application`.

Config:

```yaml
ioc:
  aggregation:
    enabled: true
    interval: 1m
    initial-delay: 10s
    id-index:
      path: ./dataframe/.ioc-id-index.csv
```

Rules:

- active only when `ioc.runtime.mode=daemon`;
- disabled in tests unless explicitly enabled;
- single-thread/single-writer;
- no overlapping runs.

Implementation options:

- Spring `@Scheduled` with a guard lock;
- Spring Integration poller;
- `ApplicationRunner` + scheduler bean.

Plan default: simple Spring scheduling in bootstrap with an `AtomicBoolean`
guard against overlapping executions.

## Health / Actuator

Stage 11 includes minimal health/readiness.

Add dependency:

```text
spring-boot-starter-actuator
```

Potential issue: app is currently `web-application-type: none`.

Options:

1. Non-web Actuator health contributors only, no HTTP endpoint.
2. Enable management web server for daemon profile.
3. Use heartbeat file for systemd/container probes.

Plan default for stage 11:

- add health indicators as beans;
- keep HTTP exposure configurable;
- do not force web server for all modes;
- document deployment choice.

Health indicators:

- `IngestionLedgerHealthIndicator`: ledger path readable/writable.
- `ArtifactStorageHealthIndicator`: canonical output directory and partitions
  directory writable.
- `AggregationHealthIndicator`: last aggregation result/time/status.

Readiness semantics:

- ready if app context started, ledger accessible, artifact dirs accessible;
- unhealthy if last aggregation failed and failure is not transient? For seed,
  expose details but keep readiness tied to storage accessibility.

## Configuration

Extend `IocProperties`:

```java
Aggregation aggregation;
```

Suggested YAML:

```yaml
ioc:
  aggregation:
    enabled: true
    interval: 1m
    initial-delay: 10s
    id-index:
      path: ./dataframe/.ioc-id-index.csv
    artifacts:
      - name: masks
        key-columns: [ mask ]
        conflict-policy: keep-first
      - name: hashes
        key-columns: [ hash_md5, hash_sha1, hash_sha256 ]
        key-mode: first-non-empty
        conflict-policy: keep-first
    retention:
      enabled: false
```

Validation:

- interval positive;
- id-index path not blank;
- artifact names match enabled `ioc.sink.artifacts`;
- key-columns not empty;
- only `keep-first` accepted in stage 11;
- retention accepted but disabled; enabled retention should fail fast or be
  documented as unsupported until implemented.

## Diagnostics and Logging

Potential diagnostic codes:

```text
AGGREGATION.PARTITION_READ_FAILED
AGGREGATION.CANONICAL_WRITE_FAILED
AGGREGATION.ID_INDEX_FAILED
AGGREGATION.CONFLICT_DETECTED
```

Do not overproduce diagnostics on every duplicate; duplicates are normal.

Potential log actions:

```text
aggregation_start
aggregation_complete
partition_read
canonical_write
id_index_update
health_check
```

Follow [logging-taxonomy.md](../logging-taxonomy.md):

- stable `event.action`;
- `ioc.artifact.name`;
- `ioc.rows`;
- `file.path`;
- no verbose IOC values at `INFO`.

## Test Contour

### Unit Tests: `core/ioc-application`

`AggregationServiceTest`:

- aggregates only `SOURCE_ARCHIVED` records;
- ignores `FAILED`/`AGGREGATED`;
- calls repositories in deterministic order;
- marks records `AGGREGATED` only after successful writes;
- does not mark aggregated on write failure;
- rerun is idempotent;
- keep-first policy preserves existing canonical row.

`StableIdIndex` can be port-only fake in application tests.

### Adapter Tests: `adapter-sink-csv`

- read partition CSV with configured dialect;
- read canonical artifact;
- write canonical artifact atomically;
- stable id sidecar CSV:
  - existing key keeps id;
  - new key gets next id;
  - reload preserves mapping;
  - atomic rewrite;
- artifact key resolver:
  - masks key from `mask`;
  - hashes key from first non-empty hash column;
  - `NULL` and blank ignored;
  - ids excluded from key.

### Adapter Tests: `adapter-lookup-csv`

- `contains()` works for masks;
- `contains()` works for MD5/SHA1/SHA256;
- missing canonical artifacts are treated as empty;
- `maxId()` remains compatible for oneshot.

### Adapter Tests: `adapter-ingest`

- file ledger reads/writes `AGGREGATED`;
- `findReadyForAggregation()` returns only `SOURCE_ARCHIVED`;
- `findIncomplete()` does not return `AGGREGATED`.

### Bootstrap Tests

- daemon context wires aggregation scheduler/use case when enabled;
- oneshot context does not run scheduler;
- health indicator beans exist;
- config validation fails on unknown artifact/key config.

### E2E Test

`DaemonAggregationE2ETest` with `@TempDir`:

1. seed source or partition files;
2. seed ledger records as `SOURCE_ARCHIVED`;
3. run `AggregatePartitionsUseCase`;
4. assert canonical masks/hashes written;
5. assert stable id sidecar written;
6. rerun and assert identical canonical output/id index;
7. assert ledger records are `AGGREGATED`.

Avoid watcher timing. Use direct use case invocation.

## CI Gate

Keep root gate:

```bash
./mvnw -B -ntp -T 1C verify
```

Useful local gates:

```bash
./mvnw -B -ntp -pl core/ioc-application -am test
./mvnw -B -ntp -pl adapters/adapter-sink-csv -am test
./mvnw -B -ntp -pl adapters/adapter-lookup-csv -am test
./mvnw -B -ntp -pl adapters/adapter-ingest -am test
./mvnw -B -ntp -pl bootstrap/ioc-app -am verify
```

## Architecture Guardrails

Update or confirm ArchUnit rules:

- `ioc-application` does not depend on CSV, filesystem, Spring or adapters.
- Aggregation ports live under `application.port.in/out`.
- Aggregation service does not depend on `adapter-*`.
- CSV aggregation implementation lives in adapter module.
- `ioc-domain` remains unaware of artifacts, partitions, ledger, stable ids,
  health and scheduling.
- Actuator/Spring health lives in bootstrap/adapter boundary only.

## Documentation Updates

Before or with implementation:

- `roadmap.md`: mark stage 10 `done`, stage 11 `in progress`/scope updated.
- `ingestion.md`: update stage 10 status and stage 11 decisions:
  - health included in stage 11;
  - retention postponed;
  - sidecar CSV id index;
  - SQLite/JDBC migration debt;
  - aggregator is process manager, identity/upsert separate.
- `services.md`: update Aggregator, CanonicalArtifactRepository,
  StableIdIndex, ArtifactIdentityResolver, artifact-aware LookupRepository,
  Health indicators.
- `modularization.md`: include aggregation service/ports in `ioc-application`
  and CSV implementations in adapters.
- `output-mapping.md`: sync status, because configurable mapper is implemented.
- `logging-taxonomy.md`: add aggregation actions if implemented.
- `diagnostic-catalog.md`: regenerated if aggregation diagnostic codes are
  added.

## Implementation Order

1. Sync docs status/scope for stage 10/11.
2. Add aggregation config model to `IocProperties` and `application.yml`.
3. Extend `IngestionStatus` and `IngestionLedger` with `AGGREGATED` support.
4. Update file ledger implementation/tests.
5. Add application aggregation model, ports and `AggregationService`.
6. Add CSV partition/canonical readers and writer in adapter layer.
7. Add stable id sidecar CSV implementation.
8. Add artifact identity resolver and keep-first merge policy.
9. Add artifact-aware lookup repository.
10. Wire aggregation use case and scheduled daemon trigger.
11. Add minimal health indicators and Actuator dependency/config.
12. Add diagnostics/log actions if needed by implementation.
13. Add unit/adapter/bootstrap/e2e tests.
14. Run `mvn verify`.
15. Update docs/roadmap status.

## Acceptance Criteria

- `mvn verify` green.
- Default `oneshot` behavior remains compatible.
- Daemon mode can ingest partitions and aggregate them to canonical artifacts.
- Stable id sidecar preserves ids across repeated aggregation.
- Re-running aggregation is idempotent: canonical output and id index stay
  stable.
- Source records move from `SOURCE_ARCHIVED` to `AGGREGATED` only after
  successful canonical writes and id index persistence.
- Masks and hashes are both present in artifact-aware lookup.
- Aggregator does not contain CSV parsing/writing logic.
- Domain remains unaware of aggregation, ids, partitions and health.
- Retention remains disabled/unimplemented and documented as debt.
- SQLite/JDBC migration is documented as debt.
- Minimal health/readiness reflects storage accessibility and aggregation state.

## Risks

| Риск | Мера |
|---|---|
| Aggregator becomes a god object | Keep process orchestration in `AggregationService`; identity/upsert/storage behind ports |
| Stable ids drift on rerun | Sidecar index tests + deterministic partition order |
| Canonical artifact partially written | temp file + atomic move |
| Row conflict policy loses useful metadata | Stage 11 `keep-first`; richer merge model documented as debt |
| Hash lookup remains incomplete | Include artifact-aware lookup in stage 11 acceptance |
| Health drags web runtime into oneshot | Conditional/configurable Actuator exposure; no forced web server for all modes |
| Retention deletes unaggregated data | Do not implement retention; config disabled and documented |
