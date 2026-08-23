# application.port.out.sync

## Назначение

Outbound-порты синхронизации. Application use cases зависят только от этих
интерфейсов; SMB/SFTP/S3 и их connection/session lifecycle остаются во внешних
adapter-модулях.

## Структура

| Файл | Ответственность |
|---|---|
| `FileTransport` | Stateless remote file operations и atomic publish intent |
| `RemoteChangeSignalSource` | Optional push capability over narrow transport-neutral `RemoteWatchTarget` |
| `RemoteChangeWatch` / `RemoteChangeSignalHandler` | Lifecycle handle and callback sink for optional remote change watches |
| `RemoteFetchLedger` | Durable idempotency ledger для read-only fetch |
| `PublishLedger` | Durable per-slice/per-target publish saga ledger |
| `CompletedSliceCatalog` | Read-only worklist verified local export slices для remote publish |

## Инварианты

- Порт не раскрывает `RemoteSession`, stream ownership или transport-specific exceptions.
- Raw `put`/`rename` не являются application API.
- `publishAtomically` — единственная write-side операция с multi-file инвариантом.
- `RemoteChangeSignalSource` не расширяет `FileTransport`: push-сигнал есть не
  у каждого транспорта и остаётся optional capability.
- Watch adapter получает только `sourceId`, endpoint и remote path; fetch
  include/exclude policy не пересекает transport boundary.
- `RemoteChangeSignalHandler.signal()` — doorbell без payload. Adapter не
  передаёт file facts; application после сигнала запускает обычный detection.
- `delete` существует только как seam для opt-in remote retention/cleanup, не для fetch claim.
- `CompletedSliceCatalog` не является retention API: staging, incomplete и corrupt final
  каталоги не превращаются в publish work; discovery сообщает corruption diagnostic,
  а точечный `find` остаётся strict.
- `CompletedSliceCatalog.find(profile, sliceName)` проверяет один immutable slice
  без полного scan профиля; `sliceName` — filesystem lookup key, `sliceId` остаётся
  durable delivery key.
- `CompletedSliceCatalog.listCompletedSliceNames(profile)` — lightweight discovery
  seam: имена completed-candidate каталогов без payload verification; перед delivery work
  обязателен `find`, а мусорные children должны быть operator-visible через diagnostics.
- `PublishLedger` — единственное durable состояние delivery saga; export run ledger
  не меняется при remote publish/recovery.
- `PublishLedger.findRetryable(staleBefore)` возвращает `PENDING`/`FAILED` и stale
  `IN_PROGRESS`; aggregate status counts не требуют `findAll`.
- Полный `findAll` является read-only health/ops view; state transitions остаются CAS-командами.

## Зависимости

**Зависит от:** `application.sync` value objects, `application.export` manifest model, JDK `Path`.

**Не импортируется:** smbj, Spring, JDBC, adapters, bootstrap.
