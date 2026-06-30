# platform/platform-events

## Назначение

Framework-free модель control-событий приложения и publish-only порт для их
публикации.

**Правило слоя:** модуль задаёт только event model и publish contract. Он не
является message bus, broker, routing-framework, durable queue или delivery
механизмом.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/platform/events/` | Control-event contracts |
| `src/test/java/com/iocextractor/platform/events/` | Contract tests |

## Зависимости

**Зависит от:** JDK.

**Не импортируется:** Spring, broker/queue libraries, serialization/wire-format
libraries, `core`, `adapters`, `bootstrap`.
