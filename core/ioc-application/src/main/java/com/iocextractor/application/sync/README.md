# application.sync

## Назначение

Framework-free модель синхронизации с внешними хранилищами. Пакет содержит только
value objects и политики, которые одинаковы для SMB/SFTP/S3 и не знают о Spring,
планировщиках, JDBC или конкретных transport-библиотеках.

## Структура

| Файл | Ответственность |
|---|---|
| `RemoteErrorKind` / `RemoteErrorDisposition` | Закрытая transport-neutral error axis и retry decision |
| `RemoteTransportException` | Исключение, через которое adapters сообщают нейтральный remote error |
| `RetryPolicy` / `Retrier` | Micro-retry executor для `RETRY_NOW` ошибок |
| `RemoteObject` / `RemoteObjectIdentity` | Metadata и fetch-ledger identity удалённого файла |
| `RemoteFetchSource` | Transport-neutral configured read-only source |
| `RemoteFetchRecord` / `RemoteFetchStatus` | Durable состояние read-only fetch idempotency |
| `PublishRecord` / `PublishStatus` | Durable per-slice/per-target publish saga state |
| `PublishLedgerStatusCounts` | Aggregated delivery state counts для hot publish loop |
| `PublishTarget` | Transport-neutral configured delivery target для export profile |
| `CompletedSlice` | Verified local slice descriptor для publish worklist |
| `PublishAtomicallyRequest` / `PublishReceipt` | Контракт толстого write-intent для публикации slice-каталога |
| `RemoteFetchService` | Use case: read-only remote list/get → atomic local inbox landing |
| `ArtifactPublishService` | Use case: reconcile completed slices × targets, publish и slice-specific event fast-path через ledger |
| `PublishLedgerSliceRetentionGuard` | Delivery-aware veto для slice retention |

## Инварианты

- Retryability не настраивается оператором: `RemoteErrorKind` сам несёт disposition.
- `Retrier` повторяет только `RETRY_NOW`; `RETRY_LATER` остаётся macro-retry задачей scheduler.
- Payload остаётся файловым (`Path`), но lifecycle потоков/соединений не попадает в application.
- Commit marker name — один безопасный leaf segment; remote path остаётся transport path.
- `PublishRecord` key = `(sliceId, targetId)`; profile/sliceName/manifest/endpoint/path — immutable binding.
- `RemoteObjectIdentity` key = remote `path + size + modifiedAt`; источник остаётся read-only.
- `CompletedSlice` уже прошёл локальную integrity-chain проверку и содержит normalized
  physical path только как payload для transport adapter.
- `ArtifactPublishService` не мутирует `export_run`: состояние доставки живёт только в
  `PublishLedger`, а local slice catalog остаётся read-only.
- Missing publish pairs materialize before remote write; `FAILED` remains retryable,
  stale `IN_PROGRESS` is recoverable, `ABANDONED` and `SUCCEEDED` are terminal for retention.
- Slice-specific publish validates event `sliceId` against verified catalog evidence
  before touching the remote target.
- Отдельная `reconcile`-операция materializes missing pairs без remote I/O: сначала
  делает lightweight listing имён срезов, затем anti-join с `publish_ledger`, и
  верифицирует только missing slice names. Dry-run только считает hypothetical/
  существующие состояния и не пишет ledger.
- Source/target filters позволяют daemon изолировать ошибку одного endpoint и продолжить
  остальные элементы конфигурационного порядка.
- Existing remote `_SUCCESS` with matching manifest hash is forward recovery to
  `SUCCEEDED`; mismatched marker emits `SYNC.PUBLISH_VERIFY_FAILED`.
- `RemoteFetchService` не делает remote claim/move/delete: источник read-only,
  deduplication строится на identity `path + size + modifiedAt`.
- Local landing выполняется через hidden `.sync-staging/*.part`, fsync best-effort
  и atomic move в inbox; ledger `FETCHED` ставится только после final move.
- Collision local file name решается stable suffix из remote identity, без overwrite.

## Зависимости

**Зависит от:** JDK, `application.export` manifest model.

**Не импортируется:** Spring, JDBC, smbj/transport libraries, adapters, bootstrap.
