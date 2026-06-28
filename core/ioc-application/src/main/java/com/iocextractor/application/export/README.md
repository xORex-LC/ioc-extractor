# com.iocextractor.application.export

## Назначение

Framework-free модель bounded context **Artifact Emission**. Пакет
фиксирует словарь формирования неизменяемых локальных срезов:
профиль и детерминированный план, metadata единого snapshot,
versioned manifest, durable saga run/progress, change detection и forward recovery.

**Правило слоя:** модель описывает смысл и инварианты, но не
знает, как строки читаются из JDBC, кодируются в CSV/JSON или
атомарно публикуются в filesystem. Эти механики принадлежат driven
adapters за портами `application.port.out.export`.

## Структура

| Файл / группа | Назначение |
|---|---|
| `ExportProfile`, `ExportMode` | Именованный, упорядоченный и неделимый набор артефактов; v1 исполняет только `COMPLETE` |
| `ExportFormat`, `ExportArtifactSpec`, `ExportPlan` | Полностью resolved-контракт публичных bytes и его детерминированный `planHash` |
| `SnapshotRequest`, `SnapshotMetadata`, `SnapshotArtifactMetadata`, `ArtifactCoverage` | Запрос и факты, захваченные в одном consistent read snapshot |
| `SliceManifest`, `SliceArtifactManifest` | Versioned integrity root всего среза и checksums/coverage его файлов |
| `ExportRun`, `ExportRunStatus` | Durable state machine только для formation saga |
| `ExportProgress`, `ArtifactRevision` | Маркеры change detection между каноническим storage и последним успешным export |
| `StagedSlice`, `AvailableSlice` | Проверенный результат до и после атомарной локальной публикации |
| `SliceInspection`, `SliceInspectionState` | Типизированный результат inspection для forward recovery |
| `SliceDescriptor` | Неделимая retention-единица, которую delivery-layer может запретить удалять |
| `ExportChangeDetector` | Чистая policy: revision/plan pre-gate, authoritative content-hash post-check и terminal progress |
| `ExportService` | Оркестрация одного run от global single-flight до `COMPLETED`/`SKIPPED` |
| `ExportRunRecoveryService` | Forward recovery активных checkpoints только из ledger и filesystem evidence |
| `NoopExportObserver` | Default implementation operational event boundary без framework/logging зависимости |

## Инварианты

- В v1 `sliceId == runId`; имена среза и файлов — один path segment, не путь.
- Порядок artifacts и columns значим. Все byte-affecting поля входят в
  length-prefixed SHA-256 `planHash`; runtime id/timestamp исключены.
- `identityHash`, `schemaHash`, file checksum, manifest checksum и `planHash` —
  lower-case SHA-256; identity epoch положителен.
- `ArtifactCoverage` сохраняет `(revision, changedAt, upperId)` из того же
  snapshot, что и rows. Recovery не подменяет его новым чтением БД.
- State machine: `STARTED -> STAGED -> AVAILABLE -> COMPLETED`; из
  `STARTED` допустим `SKIPPED`, из active states — `FAILED`.
  `COMPLETED`, `SKIPPED`, `FAILED` terminal; states от `STAGED` требуют manifest hash.
- Cheap pre-gate сравнивает exact ordered revisions и `planHash`; при полном
  совпадении ledger row не создаётся. После materialization решение принимает
  только per-artifact content hash из manifest.
- При post-hash `SKIPPED` новые snapshot revisions сохраняются атомарно с
  terminal status, но `lastSha256/lastSliceId` остаются от опубликованного среза.

## Formation saga

`ExportService` получает resolved `ExportPlan`, но не знает его config-origin.
Он дешёво читает revisions/progress, захватывает DB-backed single-flight через
`tryStart`, передаёт callback-stream напрямую от `SnapshotSliceReader` к
`ArtifactSliceWriter` и фиксирует checkpoints строго после durable side effect:

1. валидный staging + `_SUCCESS` → `STARTED -> STAGED`;
2. atomic staging-to-final rename → `STAGED -> AVAILABLE`;
3. progress из manifest → атомарный `AVAILABLE -> COMPLETED`.

Если candidate byte-identical предыдущему срезу, staging удаляется и
`STARTED -> SKIPPED` вместе с revision progress. Неизменившийся pre-gate
возвращает `SKIPPED` без run id, потому что durable run не создавался.

## Crash recovery

`ExportRunRecoveryService` не имеет зависимости на `SnapshotSliceReader`,
поэтому технически не может перечитать mutable canonical truth. Он проверяет
`SliceInspection` и продвигает только подтверждённые durable факты:

- `STARTED` + recoverable manifest дописывает marker и фиксирует `STAGED`;
- `STAGED` + staging выполняет atomic publish; final после crash rename
  распознаётся идемпотентно;
- `AVAILABLE` восстанавливает progress из manifest coverage/hash и завершает run;
- missing/partial staging удаляется и получает `FAILED`; corrupt/conflicting
  evidence не перезаписывается и также получает `FAILED` + `RECOVERY_FAILED`.

## Границы ответственности

- JDBC, CSV, JSON, filesystem, Spring и абсолютные пути здесь запрещены.
- Адаптер выводит пути из configured root/profile/immutable slice name.
- `ExportRun` не описывает remote delivery. Его будущий владелец —
  `publish_ledger` из design 0011.
- Operational events уходят через `ExportObserver`; application не импортирует
  SLF4J/ECS и не выбирает logging adapter.
