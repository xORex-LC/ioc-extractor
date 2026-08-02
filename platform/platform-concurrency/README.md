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

`SynchronousKeyedExecutionGuard` решает другой случай: вызов остаётся в потоке
caller, сохраняет обычный return/throw contract и ждёт только ранее допущенную
работу с тем же ключом. Разные ключи не разделяют глобальную блокировку. Его
aggregate snapshot не раскрывает значения ключей и пригоден для health/telemetry.
Счётчик пользователей ключа меняется только внутри `ConcurrentHashMap.compute`
для этого ключа; `volatile` обеспечивает видимость snapshot, а не атомарность
compound mutation. Если внутренний release-инвариант нарушается после ошибки
пользовательской работы, release failure добавляется как suppressed и не
заменяет primary failure.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/platform/concurrent/` | Async `KeyedSerialExecutor`, synchronous keyed guard, snapshots, observer hook and admission value objects |
| `src/test/java/com/iocextractor/platform/concurrent/` | Concurrency tests |

## Зависимости

**Зависит от:** JDK.

**Не импортируется:** Spring, broker/queue libraries, `core`, `adapters`,
`bootstrap`.
