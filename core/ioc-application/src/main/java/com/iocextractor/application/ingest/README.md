# com.iocextractor.application.ingest

## Назначение

Framework-free модель bounded context **Whole-file Ingest**. Пакет описывает
прикладной lifecycle одного входного файла: claim source, durable ledger status,
запуск extraction pipeline, canonical write/project saga и архивирование
обработанного source.

**Правило слоя:** ingest не знает о Spring Integration, filesystem polling,
SQLite/JDBC, CSV projection implementation или logging. Все внешние механики
приходят через application ports и собираются в `bootstrap`.

## Структура

| Файл / группа | Назначение |
|---|---|
| `IngestionService` | Use case orchestration для normal ingest, recovery `CLAIMED` records и reject |
| `IngestionRecord`, `IngestionStatus` | Durable source-ledger read model и terminal/active statuses |
| `SourceKey`, `SourceUnit`, `ArchivedSourceUnit` | Stable source identity и перемещение файла между lifecycle зонами |
| `SourcePreparers` | Per-source preparer bundle и список затронутых artifact names |
| `CanonicalArtifactsChanged` | Control event после durable completed ingest-run |

## Инварианты

- Повтор source-key не запускает extraction заново: duplicate source архивируется
  отдельно, существующий `FAILED` остаётся terminal.
- `CLAIMED` recovery идёт тем же `processClaimed()` путём, что и normal ingest,
  поэтому durable write/project/complete семантика едина.
- `RunLedger` фиксирует write -> projection -> completed checkpoints для
  canonical artifacts; сбой до DB commit помечает run как `FAILED`, сбой после DB
  commit оставляет работу для startup recovery.
- Успешная projection может вернуть advisory diagnostics: use case доставляет
  каждую occurrence один раз, объединяет её с extraction summary и пересчитывает
  completion до terminal driving result.
- `CanonicalArtifactsChanged` публикуется только после `runLedger.markCompleted`
  и source archive. Событие несёт `runId` и artifact names, но не revision:
  consumers делают claim-check и читают durable revision сами.
- Failure `ControlEventPublisher` не влияет на итог ingest. Событие является
  latency hint; correctness остаётся за durable ledgers и downstream poll/backstop.
- Claim, ledger и dead-letter failures возвращаются как typed `INGEST.*` carriers;
  final retry boundary эмитит occurrence ровно один раз.
- Recovery сохраняет точный `INGEST.STATE_TRANSITION_CONFLICT`, когда ledger
  возвращает неожиданный result, и создаёт `INGEST.RECOVERY_FAILED` только для
  ещё не типизированного сбоя. Application recovery сразу доставляет созданный
  recovery diagnostic; adapter startup boundary не эмитит его повторно.
- Diagnostic не подменяет ledger/file transition и не меняет fail-closed
  startup contract.

## Границы ответственности

- Spring Integration file discovery/stability, physical move/archive and JDBC
  ledgers are adapter/bootstrap concerns.
- `IngestionService` оркестрирует ports, но не выбирает storage/projection
  implementation.
- Operational control facts уходят через framework-free `ControlEventPublisher`;
  application не импортирует Spring и не решает, кто слушает событие.
