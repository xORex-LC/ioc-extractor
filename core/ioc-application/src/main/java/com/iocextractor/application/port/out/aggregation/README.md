# com.iocextractor.application.port.out.aggregation

## Назначение

Driven-порты, через которые `AggregationService` обращается к partition storage,
canonical artifacts, artifact identity и stable id index.

## Структура

| Файл | Назначение |
|---|---|
| `PartitionArtifactRepository.java` | Чтение source-scoped partition artifacts |
| `CanonicalArtifactRepository.java` | Чтение/запись canonical artifacts |
| `ArtifactIdentityResolver.java` | Artifact-specific row key extraction |
| `StableIdIndex.java` | Stable id allocation/persistence |

## Границы

Порты не задают CSV-диалект, формат sidecar index или пути файлов. Эти детали
принадлежат adapter layer.
