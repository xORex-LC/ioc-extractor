# com.iocextractor.application.port.out.artifact

## Назначение

Driven-порты для canonical artifact storage, artifact identity, CSV projection
и run-ledger recovery.

## Структура

| Файл | Назначение |
|---|---|
| `CanonicalArtifactRepository.java` | Чтение/запись canonical artifacts |
| `ArtifactIdentityResolver.java` | Artifact-specific row key extraction |
| `ArtifactIdentityStore.java` | Guardrail-хранилище identity formula hash + epoch |
| `ArtifactProjection.java` | Projection-порт для производных артефактов |
| `RunLedger.java` | Durable checkpoint store для write→project recovery |

## Границы

Порты не задают SQL/CSV-диалект или пути файлов. Эти детали принадлежат adapter
layer.
