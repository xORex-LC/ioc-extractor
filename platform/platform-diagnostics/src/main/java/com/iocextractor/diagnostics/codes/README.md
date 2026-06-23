# src/main/java/com/iocextractor/diagnostics/codes

## Назначение

Пакет содержит стартовые enum-каталоги диагностических кодов по подсистемам
обработки.

**Правило слоя:** enum-коды описывают только стабильные ids, category, default
severity и default message template. Они не знают о renderer, sinks, logging или
pipeline orchestration.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `ConfigDiagnosticCodes.java` | Ошибки конфигурации |
| `SourceDiagnosticCodes.java` | Ошибки чтения источника |
| `ExtractionDiagnosticCodes.java` | Ошибки извлечения IOC |
| `ClassificationDiagnosticCodes.java` | Ошибки классификации |
| `SinkDiagnosticCodes.java` | Ошибки записи артефактов |
| `StorageDiagnosticCodes.java` | Ошибки durable storage: миграции, импорт, identity |
| `SchemaDiagnosticCodes.java` | Guardrail-коды сверки схемы storage |
| `PipelineDiagnosticCodes.java` | Ошибки стадий pipeline |

## Зависимости

**Зависит от:** `diagnostics` core interfaces/enums.

**Не импортируется:** адаптерами доставки диагностик напрямую; consumer-ы должны
работать через `DiagnosticCode` и `DiagnosticCatalogs`.
