# platform/platform-concurrency

## Назначение

Framework-free примитивы конкуренции для платформенной координации: keyed
single-flight, admission и lifecycle/shutdown контракты.

**Правило слоя:** модуль не знает о событиях, IOC-предметке, Spring lifecycle,
transport adapters или durable delivery. Это общий concurrency toolkit, а не
часть event model.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/platform/concurrent/` | `KeyedSerialExecutor`, bounded implementation and admission value objects |
| `src/test/java/com/iocextractor/platform/concurrent/` | Concurrency tests |

## Зависимости

**Зависит от:** JDK.

**Не импортируется:** Spring, broker/queue libraries, `core`, `adapters`,
`bootstrap`.
