# com.iocextractor.application.tck.export

## Назначение

Переиспользуемый behavioral contract для durable `ExportRunLedger`.
Каждый storage adapter наследует контракт и предоставляет
изолированную fixture из ledger/progress read side на одном store.

## Покрываемый контракт

- полная success-цепочка и active/terminal visibility;
- global single-flight до terminal checkpoint;
- CAS и idempotent replay того же или более позднего status;
- diagnostic при incompatible terminal/manifest state;
- атомарность terminal status и per-artifact progress;
- продвижение revision при `SKIPPED`.

## Граница

TCK не знает JDBC/SQL и не проверяет adapter-specific миграции и
физическую concurrent race. Эти сценарии остаются в integration-тестах
конкретного адаптера.
