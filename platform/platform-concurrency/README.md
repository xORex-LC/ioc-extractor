# platform/platform-concurrency

## Назначение

Framework-free примитивы конкуренции для платформенной координации: keyed
single-flight, admission и lifecycle/shutdown контракты.

**Правило слоя:** модуль не знает о событиях, IOC-предметке, Spring lifecycle,
transport adapters или durable delivery. Это общий concurrency toolkit, а не
часть event model.

`KeyedSerialExecutor` гарантирует no-overlap/FIFO только для принятой in-memory
работы. Он не является durable queue: вызывающий код обязан обрабатывать ошибки
самой работы, переводить `REJECTED` admission в reconcile/backstop путь и
использовать `KeyedSerialExecutorObserver` как telemetry hook для деградаций
(`rejected`, `failed`, `dispatchRejected`). High/low-water hysteresis остаётся
расширением поверх этого seam, когда появится реальная нагрузочная политика.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/platform/concurrent/` | `KeyedSerialExecutor`, bounded implementation, observer hook and admission value objects |
| `src/test/java/com/iocextractor/platform/concurrent/` | Concurrency tests |

## Зависимости

**Зависит от:** JDK.

**Не импортируется:** Spring, broker/queue libraries, `core`, `adapters`,
`bootstrap`.
