# com.iocextractor.application.aggregation

## Назначение

Application-level orchestration для daemon aggregation: готовые source-scoped
партиции сводятся в канонические артефакты, после чего ledger получает статус
`AGGREGATED`.

**Правило слоя:** пакет не знает CSV, filesystem, Spring scheduling или
Actuator. Он работает только через ports из `application.port.out.aggregation`
и `application.port.out.ingest`.

## Структура

| Файл | Назначение |
|---|---|
| `AggregationService.java` | Process manager: ready records → partition read → canonical write → mark `AGGREGATED` |
| `ArtifactRow.java` / `ArtifactRowKey.java` | Storage-neutral row model и stable identity value |
| `CanonicalArtifact.java` / `PartitionArtifact.java` | Storage-neutral snapshots для canonical и partition artifacts |
| `StableArtifactId.java` | Результат stable id allocation |
| `ArtifactMergePolicy.java` / `KeepFirstMergePolicy.java` | Conflict policy seam; stage 11 реализует `keep-first` |

## Границы

- не читает и не пишет CSV напрямую;
- не вычисляет ключ из конкретной CSV-схемы;
- не запускает scheduler и не публикует health;
- не удаляет partition files.
