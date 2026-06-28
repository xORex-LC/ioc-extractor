# com.iocextractor.application.port.in.export

## Назначение

Driving-порты bounded context **Artifact Emission**. Это единственная
точка входа для CLI, scheduler и startup recovery: входной адаптер
выбирает profile, а application service целиком исполняет или
восстанавливает formation saga.

**Правило слоя:** пакет содержит только primary interfaces и их
command/result DTO. Входной адаптер не зависит от concrete service и
не получает доступ к driven-портам.

## Структура

| Файл | Назначение |
|---|---|
| `ExportArtifactsUseCase` | Запуск одного configured profile до terminal result |
| `ExportArtifactsCommand` | Минимальная команда с именем profile; storage/layout settings в неё не передаются |
| `ExportArtifactsResult` | Terminal status и optional run/slice identity; pre-gate `SKIPPED` не создаёт run и возвращает `runId=null` |
| `RecoverExportUseCase` | Startup forward recovery всех durable incomplete runs |
| `RunSliceRetentionUseCase` | Один profile-scoped sweep завершённых immutable slices |
| `SliceRetentionResult` | Счётчики scanned/deleted/blocked и удалений по profile |

## Контракт вызова

- `export` возвращается только после terminal state: срез доступен,
  изменений нет (`SKIPPED`) или запуск завершился ошибкой.
- `SKIPPED` после materialization содержит run id; cheap pre-gate `SKIPPED`
  не содержит его, поскольку single-flight/ledger не затрагивались.
- `recoverIncomplete` возвращает число проверенных runs, а не число
  созданных срезов.
- Slice retention применяет `max-age`/`max-count` независимо к каждому profile;
  blocked candidates остаются в пуле, поэтому count limit при pins best-effort.
- CAS transitions, single-flight, filesystem inspection и retry decisions — ответственность
  application service за driven-портами, не CLI/scheduler.

## Зависимости

**Зависит от:** `application.export` DTO/state vocabulary.

**Не импортирует:** adapters, JDBC, filesystem, Spring, picocli и transport API.
