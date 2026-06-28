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
| `RemoteFetchRecord` / `RemoteFetchStatus` | Durable состояние read-only fetch idempotency |
| `PublishRecord` / `PublishStatus` | Durable per-slice/per-target publish saga state |
| `PublishTarget` | Transport-neutral configured delivery target для export profile |
| `CompletedSlice` | Verified local slice descriptor для publish worklist |
| `PublishAtomicallyRequest` / `PublishReceipt` | Контракт толстого write-intent для публикации slice-каталога |
| `ArtifactPublishService` | Use case: reconcile completed slices × targets и publish через ledger |
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
  `ABANDONED` and `SUCCEEDED` are terminal for retention.
- Existing remote `_SUCCESS` with matching manifest hash is forward recovery to
  `SUCCEEDED`; mismatched marker emits `SYNC.PUBLISH_VERIFY_FAILED`.

## Зависимости

**Зависит от:** JDK, `application.export` manifest model.

**Не импортируется:** Spring, JDBC, smbj/transport libraries, adapters, bootstrap.
