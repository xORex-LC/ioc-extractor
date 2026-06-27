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
| `ExportArtifactsResult` | Терминальный run id/status и immutable slice name; для `SKIPPED` slice может отсутствовать |
| `RecoverExportUseCase` | Startup forward recovery всех durable incomplete runs |

## Контракт вызова

- `export` возвращается только после terminal state: срез доступен,
  изменений нет (`SKIPPED`) или запуск завершился ошибкой.
- `recoverIncomplete` возвращает число проверенных runs, а не число
  созданных срезов.
- CAS transitions, single-flight, filesystem inspection и retry decisions — ответственность
  application service за driven-портами, не CLI/scheduler.

## Зависимости

**Зависит от:** `application.export` DTO/state vocabulary.

**Не импортирует:** adapters, JDBC, filesystem, Spring, picocli и transport API.
