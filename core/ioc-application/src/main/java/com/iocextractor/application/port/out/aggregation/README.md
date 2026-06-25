# com.iocextractor.application.port.out.aggregation

## Назначение

Driven-порты для canonical artifact storage, artifact identity, CSV projection,
run-ledger recovery и временного stable id index.

## Структура

| Файл | Назначение |
|---|---|
| `CanonicalArtifactRepository.java` | Чтение/запись canonical artifacts |
| `ArtifactIdentityResolver.java` | Artifact-specific row key extraction |
| `ArtifactIdentityStore.java` | Guardrail-хранилище identity formula hash + epoch |
| `ArtifactProjection.java` | Projection-порт для производных артефактов |
| `RunLedger.java` | Durable checkpoint store для write→project recovery |
| `StableIdIndex.java` | Stable id allocation/persistence |

## Границы

Порты не задают SQL/CSV-диалект, формат sidecar index или пути файлов. Эти
детали принадлежат adapter layer.
