# Immutable artifact export

Artifact export формирует неделимый, проверяемый и неизменяемый profile slice из
canonical dataframe. Он не публикует данные во внешнее хранилище: remote
delivery начинается только после локального durable `COMPLETED`.

## Runtime flow

```text
profile validation + revision/plan pre-gate
  -> cross-process operation lease
  -> export_run STARTED
  -> reconcile stable export slots at canonical generation G
  -> one consistent dataframe snapshot
  -> stream CSV + manifest into .staging
  -> durable _SUCCESS
  -> STAGED
  -> atomic staging-to-final rename
  -> AVAILABLE
  -> export_progress + COMPLETED
  -> SliceCompleted latency hint
```

`core/ioc-application` владеет plan/model/change policy/saga; JDBC adapter
читает snapshot и хранит ledgers; CSV adapter формирует filesystem slice;
Jackson adapter кодирует manifest; bootstrap собирает CLI, daemon cadence,
recovery, health и retention.

Stateful export проходит common canonical-data admission до pre-gate и snapshot.
Daemon export scheduler до открытия этого barrier не выполняет recovery и не
планирует cadence; после admission он сохраняет прежний insert-driven trigger.

## Инварианты

1. **Profile — неделимая ordered единица.** В v1 поддерживается только полный
   (`COMPLETE`) output; artifacts и columns имеют значимый порядок.
2. **Public bytes детерминированы plan-ом.** Schema, identity, active mapping и
   CSV format входят в hashes/fingerprints; runtime timestamp/run id не меняют
   смысл plan.
3. **Snapshot согласован.** Rows, revisions и coverage читаются из одной WAL
   read transaction. При active lifecycle один captured `asOf` применяется к
   coverage и строкам всех artifacts; `valid_until == asOf` уже исключается.
   Concurrent ingest попадает только в следующий export.
4. **Публикация локального slice атомарна.** Incomplete staging не считается
   completed; `_SUCCESS`, manifest и exact membership проверяются перед выдачей
   slice потребителю.
5. **Strict encoding.** Непредставимый символ завершает export ошибкой до
   публикации: lossy bytes нарушили бы checksum/manifest contract.
6. **Change detection двухступенчатый.** Revisions + `planHash` дают дешёвый
   pre-gate; окончательное решение принимает content hash созданного candidate.
   `SKIPPED` допустим только при совпадении plan, bytes **и manifest coverage
   revisions** с durable progress. Более новая revision означает новую public
   lifecycle и создаёт новый published slice даже при byte-identical CSV.
7. **Event не является commit.** `SliceCompleted` появляется только после
   durable `COMPLETED`; потерянный event добирает publish reconcile.
8. **Expiry не создаёт slice.** Истечение или renewal не меняют insert-driven
   artifact revision и не запускают lifecycle-specific export event. Следующий
   разрешённый export читает только active membership на своём общем `asOf`.
9. **Внешний `id` — export slot.** Для artifact с колонкой `id` active survivor
   сохраняет свой slot между slices. Reconciliation освобождает slots исчезнувших
   lifecycle, назначает новым lifecycle минимальные положительные holes, а при
   их отсутствии продвигает durable high-water. Canonical row id и
   `_lifecycle_id` не публикуются как этот slot и никогда не переиспользуются.

## Stable reusable export slots

`SnapshotSliceReader` остаётся application output port для всей операции
consistent snapshot. Его JDBC-реализация при `ACTIVE` lifecycle выполняет
короткую write transaction в той же dataframe DB:

1. захватывает SQLite write ownership и тем самым сериализуется с
   confirmation/expiry;
2. при первом export seed-ит `(profile, artifact, lifecycle_id) -> slot` из
   текущего canonical `id`, сохраняя upgrade mapping;
3. set-based освобождает assignments, отсутствующие в active set, сопоставляет
   минимальные free slots новым lifecycle и выдаёт остаток из `next_slot`;
4. фиксирует `artifact_projection_state.required_generation` как
   `source_generation` registry.

После commit reader открывает отдельную read transaction и до первого callback
проверяет generation и полноту assignments. Изменение canonical generation в
этом окне приводит к bounded retry; mixed mapping не попадает в staging.
Streaming query выдаёт `export_slot AS id` в порядке slot и использует
covering assignment index вместе с unique lifecycle index. Артефакты без
внешней `id` обходят registry и сохраняют прежний путь.
Manifest coverage `upperId` для такого artifact равен максимальному active
slot; это coverage fact complete-slice, а не high-water и не canonical ID.

Registry принадлежит export capability, но физически хранится рядом с canonical
rows в `ioc-dataframe.db`: это позволяет избежать неатомарной координации двух
SQLite-файлов. Expiry/history/provenance path не зависит от registry, а старые
immutable slices уже материализуют своё историческое соответствие. Поэтому один
slot может относиться к разным lifecycle в разных slices; диагностический ключ
должен включать slice, profile и artifact.

## Durable state и recovery

```text
STARTED -> STAGED -> AVAILABLE -> COMPLETED
   |          |          |
   +-------> FAILED <-----+
STARTED -> SKIPPED
```

Recovery является forward-only и не перечитывает mutable canonical truth.
Она принимает решения только по `export_run`, staging/final filesystem evidence
и manifest coverage:

- valid staging можно довести до `STAGED` или удалить только при совпадении
  plan, bytes и coverage revisions с durable progress;
- `STAGED` можно атомарно опубликовать;
- `AVAILABLE` можно завершить с progress из уже сохранённого manifest;
- missing/corrupt/conflicting evidence получает typed failure, а не тихую
  повторную materialization.

DB-backed active-run constraint и operation lease не позволяют двум процессам
одновременно формировать или восстанавливать один contour.

## Cadence и retention

CLI запускает named profile вручную. Daemon поддерживает interval и
quiet-period/max-cap policy; ingest event только вызывает coalesced `nudge()`, а
periodic cadence остаётся backstop.

Lifecycle expiry отдельно восстанавливает mutable `*_generated.csv`, но не
вызывает `DaemonExportScheduler.nudge()`. Поэтому истечение само по себе не
создаёт immutable slice; только следующий разрешённый new-data export зафиксирует
актуальное active membership. Если new-data commit создал новую lifecycle после
TTL и продвинул `artifact_revision`, такой export завершается новым slice и
delivery даже тогда, когда его CSV bytes совпали с историческим slice.

Retention считает каждый final slice одной единицей и применяет age/count per
profile. Guard проверяется непосредственно перед delete. При активном remote
publish `PublishLedgerSliceRetentionGuard` pin-ит slice, пока каждая настроенная
`slice × target` pair не terminal; поэтому `max-count` намеренно best effort.

## Отказы

| Граница | Результат |
|---|---|
| Unknown/invalid profile | IO-free rejection до lazy storage resolution |
| Snapshot read / strict encoding / manifest write | `FAILED`, final slice не появляется |
| Crash после side effect до ledger checkpoint | startup recovery сверяет durable evidence и двигается вперёд |
| Event publication failure | completed slice сохраняется; publish reconcile добирает |
| Retention guard failure/pin | slice остаётся на диске |

## Как расширять

- Новый profile добавляется конфигурацией поверх enabled artifacts и identity.
- Новый output mode требует нового application contract и writer semantics;
  неизвестный mode не должен silently fallback-ить к `COMPLETE`.
- Новый manifest format реализует `SliceManifestCodec` отдельным adapter-ом.
- Remote transport не добавляется в export: он принадлежит sync/publish.

## Источники истины

- Model/saga: `application.export` и co-located package `README.md`.
- Ports: `application.port.in.export`, `application.port.out.export`.
- Plan resolution: `ExportPlanCatalog` tests.
- Snapshot/ledger: `JdbcSnapshotSliceReader`, `JdbcExportRunLedger` и TCK.
- Filesystem slice: `CsvArtifactSliceWriter` contract/integration tests.
- Recovery/cadence: `ExportRunRecoveryServiceTest`,
  `DaemonExportSchedulerTest`, export recovery integration tests.

## Когда обновлять документ

Обновить при изменении state machine, snapshot/atomic publish boundary,
plan/hash contract, recovery evidence, cadence ownership или retention guard.

## Связанные документы

- [storage.md](storage.md) — revisions и snapshot source.
- [sync.md](sync.md) — remote publish completed slices.
- [event-coordination.md](event-coordination.md) — fast-path + reconcile.
- [ADR-0012](../ADR/0012-streaming-dataframe-emission.md) — решение Artifact Emission.
