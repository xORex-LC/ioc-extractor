# Immutable artifact export

Artifact export формирует неделимый, проверяемый и неизменяемый profile slice из
canonical dataframe. Он не публикует данные во внешнее хранилище: remote
delivery начинается только после локального durable `COMPLETED`.

## Runtime flow

```text
profile validation + revision/plan pre-gate
  -> cross-process operation lease
  -> export_run STARTED
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

## Инварианты

1. **Profile — неделимая ordered единица.** В v1 поддерживается только полный
   (`COMPLETE`) output; artifacts и columns имеют значимый порядок.
2. **Public bytes детерминированы plan-ом.** Schema, identity, active mapping и
   CSV format входят в hashes/fingerprints; runtime timestamp/run id не меняют
   смысл plan.
3. **Snapshot согласован.** Rows, revisions и coverage читаются из одной WAL
   read transaction. Concurrent ingest попадает только в следующий export.
4. **Публикация локального slice атомарна.** Incomplete staging не считается
   completed; `_SUCCESS`, manifest и exact membership проверяются перед выдачей
   slice потребителю.
5. **Strict encoding.** Непредставимый символ завершает export ошибкой до
   публикации: lossy bytes нарушили бы checksum/manifest contract.
6. **Change detection двухступенчатый.** Revisions + `planHash` дают дешёвый
   pre-gate; окончательное решение принимает content hash созданного candidate.
   Byte-identical candidate получает `SKIPPED` без нового published slice.
7. **Event не является commit.** `SliceCompleted` появляется только после
   durable `COMPLETED`; потерянный event добирает publish reconcile.

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

- valid staging можно довести до `STAGED` или удалить как byte-identical;
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
