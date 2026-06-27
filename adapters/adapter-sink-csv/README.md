# adapters/adapter-sink-csv

## Назначение

Outbound CSV adapter implementing `IocSink`, canonical CSV projection and
streaming immutable artifact slices.

**Правило слоя:** owns CSV writing, artifact row mapping and local atomic slice
publication; domain/application do not depend on Commons CSV or filesystem
mechanics. Export saga/ledger orchestration remains in application/storage ports.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/out/sink/csv/` | CSV sink and mapping components |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-domain`, platform
errors/diagnostics/observability, Commons CSV/IO, SLF4J API.

**Не импортируется:** bootstrap and sibling adapters.

## Контракты

- legacy/current projection path формирует CSV из canonical repository;
- `CsvArtifactSliceWriter` получает callback-stream из `SnapshotSliceReader`,
  пишет data/manifest/`_SUCCESS` в staging и публикует каталог одним
  `ATOMIC_MOVE`;
- JSON codec внедряется через `SliceManifestCodec`: compile-time зависимости на
  sibling Jackson adapter нет;
- service DB, export-run transitions и delivery в модуль не входят.
