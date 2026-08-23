# com.iocextractor.application.port.out.artifact

## Назначение

Driven-порты для canonical artifact storage, artifact identity, CSV projection
и run-ledger recovery.

## Структура

| Файл | Назначение |
|---|---|
| `ArtifactPreparer.java` | Side-effect-free routing/mapping: classified indicators → write plan + diagnostics |
| `CanonicalArtifactRepository.java` | Чтение/запись canonical artifacts; write возвращает actual inserts и revision |
| `ArtifactIdentityResolver.java` | Artifact-specific row key extraction |
| `ArtifactIdentityStore.java` | Guardrail-хранилище identity formula hash + epoch |
| `CanonicalMatchPlanner.java` | Set-based active-only zero/one/multiple alias matching |
| `ArtifactProjection.java` | Projection-порт для производных артефактов |
| `ArtifactProjectionCommand.java` | Обязательные run/artifact identity одной projection operation |
| `ArtifactProjectionResult.java` | Immutable row count + advisory diagnostics успешно установленной проекции |
| `RunLedger.java` | Durable checkpoint store для write→project recovery |
| `lifecycle/` | Lifecycle-aware canonical write/read, expiry, activation and projection-work ports |

## Границы

Порты не задают SQL/CSV-диалект или пути файлов. Эти детали принадлежат adapter
layer. Projection hard failure выражается исключением; outcome допускает только
advisory diagnostics, потому что canonical commit к этому моменту уже выполнен.
