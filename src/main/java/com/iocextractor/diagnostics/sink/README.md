# src/main/java/com/iocextractor/diagnostics/sink

## Назначение

Пакет содержит порт приёма диагностик и базовые реализации без внешних side
effects.

**Правило слоя:** sinks принимают `Diagnostic` как данные. Логирование, ECS/MDC и
rolling files реализуются отдельным bridge-модулем, не здесь.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `DiagnosticSink.java` | Driven port для доставки диагностик |
| `CollectingDiagnosticSink.java` | Накопитель для тестов/отчётов |
| `NoopDiagnosticSink.java` | Пустая реализация |

## Зависимости

**Зависит от:** `diagnostics` core.

**Не импортируется:** SLF4J/Logback, Spring, filesystem, adapters.
