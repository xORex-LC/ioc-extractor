# com.iocextractor.application.port.out.export

## Назначение

Driven-порты bounded context **Artifact Emission**. Они разделяют
canonical snapshot, streaming row hand-off, формирование/публикацию
локальных bytes, manifest codec, durable saga state, cheap change detection
и delivery-aware retention veto. Такое разбиение не позволяет JDBC,
CSV/JSON и filesystem-механикам смешиваться в одном адаптере.

**Правило слоя:** здесь только interfaces и application-model DTO. Каждый
порт владеет одной infrastructure capability; cross-port orchestration и
state transitions принадлежат use case.

## Структура

| Файл | Назначение |
|---|---|
| `SnapshotSliceReader` | Открывает один strict multi-artifact read snapshot и потоково отдаёт metadata/rows |
| `SnapshotRowConsumer` | Синхронный callback protocol `begin -> artifact* -> end` без JDBC-типов |
| `ArtifactSliceWriter` | Формирует deterministic bytes, staging manifest, inspection/recovery и atomic visibility |
| `SliceManifestCodec` | Единственная serialization boundary versioned manifest |
| `ArtifactRevisionReader` | Дешёво читает canonical revisions в запрошенном порядке, не сканируя rows |
| `ExportProgressStore` | Read side последнего terminal progress для revision/hash change detection |
| `ExportRunLedger` | Durable CAS state machine, global single-flight и атомарная фиксация terminal progress |
| `ExportObserver` | Lifecycle callbacks в точках фактических durable checkpoints; конкретный ECS logger остаётся снаружи application |
| `SliceRetentionGuard` | Чистый delivery-aware veto, вызываемый непосредственно перед delete |

## Протоколы и владение ресурсами

- Reader владеет connection, read transaction и cursor на всём callback
  sequence. Consumer вызывается синхронно, не удерживает rows и не
  запускает asynchronous callbacks.
- Callback order для каждого artifact строг: metadata перед строками,
  artifacts и rows в порядке resolved plan.
- Writer владеет staging/final filesystem protocol и integrity validation, но
  никогда не меняет ledger. Ledger не выполняет filesystem IO.
- `tryStart` — единственная точка global single-flight; `transition` применяет
  expected-state CAS; `finish` атомарно сохраняет progress и terminal status.
- `ExportObserver` не управляет flow и не должен бросать исключения: producer
  вызывает его после соответствующего durable действия/ledger checkpoint.
- Retention guard не удаляет срез. Он только даёт актуальный veto
  непосредственно перед delete; в C0–C8 default policy fail-open.

## Границы адаптеров

- JDBC adapter реализует snapshot/revision/run/progress ports, но не форматирует CSV.
- CSV/filesystem adapter реализует writer и manifest codec, но не открывает JDBC connection.
- Delivery adapter позже реализует retention guard; formation saga не зависит
  от remote transport.
