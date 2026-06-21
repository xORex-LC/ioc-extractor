# src/main/java/com/iocextractor/diagnostics/catalog

## Назначение

Пакет агрегирует enum-каталоги diagnostic codes и предоставляет entries для
проверок и генерации карты ошибок.

**Правило слоя:** каталог перечисляет коды вручную и не использует runtime
reflection. Полнота регистрации защищается тестом/ArchUnit guardrail.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `DiagnosticCatalog.java` | Контракт перечислимого каталога |
| `DiagnosticCatalogEntry.java` | Плоская запись каталога для docs/report |
| `DiagnosticCatalogs.java` | Агрегатор всех стартовых каталогов |

## Зависимости

**Зависит от:** `diagnostics` core и `diagnostics.codes`.

**Не импортируется:** logging/observability подсистемами напрямую в обход портов
renderer/sink.
