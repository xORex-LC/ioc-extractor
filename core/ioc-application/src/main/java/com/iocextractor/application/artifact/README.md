# com.iocextractor.application.artifact

## Назначение

Storage-neutral модель canonical artifacts, row identity и run-ledger recovery.
Daemon больше не пишет source-scoped партиции: ingestion идёт напрямую в
canonical storage, а CSV является проекцией из БД.

**Правило слоя:** пакет не знает CSV, filesystem, Spring scheduling или
Actuator. Он работает только через ports из `application.port.out.artifact`
и `application.port.out.ingest`.

## Структура

| Файл | Назначение |
|---|---|
| `ArtifactRow.java` / `ArtifactRowKey.java` | Storage-neutral row model и stable identity value |
| `CanonicalArtifact.java` | Storage-neutral snapshot для canonical artifact |
| `CanonicalWriteResult.java` | Фактические public inserts и canonical revision после атомарной записи |
| `StableArtifactId.java` | Результат stable id allocation |
| `IngestRun*.java` / `RunLedger`-model | Durable checkpoint model для write→project recovery |

## Границы

- не читает и не пишет CSV напрямую;
- не вычисляет ключ из конкретной CSV-схемы;
- не запускает scheduler и не публикует health;
- не управляет filesystem lifecycle source-файлов.
