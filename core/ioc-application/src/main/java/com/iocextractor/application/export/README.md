# com.iocextractor.application.export

## Назначение

Framework-free модель bounded context **Artifact Emission**. Пакет
фиксирует словарь формирования неизменяемых локальных срезов:
профиль и детерминированный план, metadata единого snapshot,
versioned manifest, durable saga run/progress и recovery/retention descriptors.

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

## Границы ответственности

- JDBC, CSV, JSON, filesystem, Spring и абсолютные пути здесь запрещены.
- Адаптер выводит пути из configured root/profile/immutable slice name.
- `ExportRun` не описывает remote delivery. Его будущий владелец —
  `publish_ledger` из design 0011.
