# dataframe

## Назначение

Табличные данные: эталонные схемы (заголовки) выходных артефактов и
сгенерированные приложением CSV-проекции. Источник истины бизнес-данных —
canonical SQLite (`var/db/ioc-dataframe.db`); файлы здесь — **не** runtime-вход:
CSV lookup/storage-режим выведен из эксплуатации
([ADR 0015](../docs/ADR/0015-retire-legacy-csv-lookup-storage.md)).

**Правило слоя:** только данные (CSV). `*_generated.csv` пишутся из БД
проекцией `CsvArtifactProjection` (`adapter-csv`); прямых зависимостей
кода на конкретные файлы нет (пути задаются конфигурацией
`ioc.sink.artifacts[].path`). Дедупликация и provenance живут в БД
(`row_key` + `<artifact>_sources`), не в этих файлах.

## Структура

| Файл | Назначение |
|---|---|
| `masks_list.csv` | эталонная схема артефакта сетевых масок (заголовок) |
| `ip_list.csv` | эталонная схема артефакта IP-репутации (заголовок) |
| `hashes_list.csv` | эталонная схема артефакта файловых хэшей (заголовок) |
| `address_blacklist.csv` | эталонная схема адресного блэклиста (заголовок) |
| `IOC_aggregate_generated.csv` | standalone-проекция `name;ip_address;url_match;host_match;hash`, генерируется прогоном |
| остальные `*_generated.csv` | проекции canonical БД, генерируются прогоном (в `.gitignore`) |

## Заметки

Диалект CSV: разделитель `;`, значения в кавычках, пустые — литерал `NULL`.
Схемы артефактов различаются (маски vs хэши) — см.
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md); декларативное заполнение
колонок — [docs/dev/processing.md](../docs/dev/processing.md). Публичный контракт
aggregate описан в [operator guide](../docs/guides/ioc-aggregate.md).
