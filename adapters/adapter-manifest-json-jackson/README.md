# adapters/adapter-manifest-json-jackson

## Назначение

Outbound adapter для versioned JSON-представления `SliceManifest`.
Модуль владеет Jackson-маппингом, фиксированным порядком JSON-полей и
проверкой совместимости `manifest_version`.

**Правило слоя:** модуль реализует только `SliceManifestCodec`.
Он не знает filesystem paths, CSV, ledger/status transitions и remote delivery.

## Структура

| Путь | Назначение |
|---|---|
| `pom.xml` | Module dependencies: application port/model, platform errors, Jackson databind |
| `src/main/java/com/iocextractor/adapter/out/manifest/json/` | Deterministic codec и его package documentation |
| `src/test/java/com/iocextractor/adapter/out/manifest/json/` | Golden bytes, round-trip, Unicode/null/version tests |

## Инварианты

- output всегда UTF-8 без pretty-print и platform-dependent newline;
- порядок полей задан явно для root/format/artifact/coverage;
- `output_mode` кодируется lower-case;
- nullable `changed_at` при revision zero пишется явным JSON `null`;
- v1 decoder отклоняет любой другой `manifest_version` до построения model.

## Зависимости

**Зависит от:** `ioc-application`, `ioc-platform-errors`, Jackson.

**Не импортирует:** sibling adapters, Spring, JDBC, commons-csv, filesystem API.
