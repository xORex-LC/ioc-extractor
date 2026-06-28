# com.iocextractor.bootstrap

## Назначение

Composition root и конфигурация. Единственное место, где фреймворк (Spring)
связывает агностичное ядро с конкретными адаптерами и где известна форма внешней
конфигурации.

**Правило слоя:** зависит на всё (собирает граф). Ни один внутренний слой не
зависит на `bootstrap`. Смена реализации порта — изменение только здесь.

## Структура

| Файл | Назначение |
|---|---|
| `IocProperties.java` | Типобезопасная привязка дерева `ioc.*` (`@ConfigurationProperties`) |
| `AppConfig.java` | Сборка основного extraction/export/storage graph |
| `SyncConfig.java` | Изолированная composition remote sync: transport, ledgers, use cases, guards и schedulers |
| `SyncSelectionCatalog.java` | IO-free CLI preflight enabled/source/target/profile/endpoint до lazy graph resolution |
| `ExportPlanCatalog.java` | IO-free cross-validation `ioc.export` и resolution immutable `ExportPlan` |
| `LoggingExportObserver.java` | `ExportObserver` → ECS actions/fields через observability helpers |
| `ConditionalOnServiceStorage.java` | Условие регистрации lazy service-storage graph независимо от runtime mode |
| `LazyServiceStorage.java` | Не-`DataSource` holder: открывает, мигрирует и закрывает service SQLite по первому явному запросу |
| `DaemonExportScheduler.java` | Recovery-before-start, cadence polling, sequential profiles и overlap guard |
| `TransportRegistry.java` | Lazy endpoint → transport dispatch и adapter lifecycle без утечки transport types в core |
| `DaemonFetchScheduler.java` | Fixed-delay source-isolated remote fetch, overlap guard и controlled shutdown |
| `DaemonPublishScheduler.java` | Startup reconcile, target-isolated publish и lifecycle phase 150 |
| `SyncHealthState.java` | Thread-safe latest scheduler outcomes per source/target |
| `SyncHealthIndicator.java` | Actuator view: last runs, durable publish backlog, endpoint summary и pinned slices |
| `DaemonSliceRetentionScheduler.java` | Отдельный late-phase lifecycle для profile-scoped slice retention |
| `*HealthIndicator.java` | Actuator contributors, включая export freshness/failure/revision lag |

## Заметки

Артефакты собираются из конфигурации (`buildSinks`): маппер + id-стратегия +
диалект CSV. В daemon mode здесь же связываются ingest flow, JDBC storage,
projection и health contributors. Доменные объекты остаются framework-free —
Spring живёт здесь.

## Lazy export composition

`LazyServiceStorage` владеет service datasource/migrations и не открывает их в
конструкторе; export infrastructure/use-case beans помечены `@Lazy`. Holder не
зависит от `ioc.runtime.mode`: daemon может использовать тот же DB-backed
single-flight, что и ручной CLI. При этом
`ExportCommand` выполняет eager catalog-backed profile preflight и разрешает
storage-backed use case только внутри `call()`, поэтому построение root CLI,
unrelated commands и unknown profile не создают `var/db/ioc-service.db`.
`ExportService` после IO-free profile validation удерживает общий NIO operation
lease вокруг recovery и formation; standalone recovery использует тот же guard.

Resolved profile строится из ordered sink columns, identity definition и CSV
format. `schemaHash` покрывает ordered `(column, declaredType)`, `identityHash`
переиспользует canonical identity formula, `mappingHash` — accepts/filters/id и
column providers/constants/predicates/transforms, а artifact file name берётся
из leaf configured sink path. Эти значения входят в `planHash`; schema/identity
затем проверяются snapshot reader-ом против canonical metadata. Charset,
односивольная форма delimiter/quote, их различие и reserved slice names
валидируются catalog-ом до разрешения lazy storage graph и захвата single-flight.

## Daemon cadence

`DaemonExportScheduler` активен только при `runtime.mode=daemon` и
`export.enabled=true`. Он синхронно завершает forward recovery до запуска
executor, затем последовательно проверяет profiles в конфигурационном порядке.
Cadence policy живёт в application: `interval` использует processing time,
`quiet-period` — canonical `artifact_revision.changed_at` и обязательный
`max-cap`. Локальный overlap guard не заменяет DB-backed global single-flight.
Отсутствующий progress или несовпадение `planHash` обходят cadence gate: initial
export после upgrade и deterministic re-emission конфигурационного drift не
зависят от появления новой canonical activity.

## Slice retention lifecycle

`DaemonSliceRetentionScheduler` не переиспользует leaf-file
`DaemonMaintenanceScheduler`: оба scheduler берут operational
`maintenance.retention.interval/initial-delay`, но вызывают разные use cases и
stores. Slice scheduler стартует в более поздней lifecycle phase, чем formation;
это оставляет seam для publish reconciliation из 0011. По умолчанию composition
ставит `StandaloneSliceRetentionGuard`; delivery adapter может заменить bean и
pin-ить ещё не доставленные срезы.

## Remote sync lifecycle

Sync graph регистрируется только при `ioc.sync.enabled=true`; fetch/publish use cases
и JDBC ledgers остаются lazy для oneshot CLI. `TransportRegistry` строит adapter settings,
но SMB-соединение открывается только первой file operation. Daemon fetch идёт в phase 50,
export formation — 100, publish — 150, slice retention — 200. Publish `start()` синхронно
создаёт недостающие `(slice,target)` ledger pairs без remote writes до запуска periodic loop.
Оба sync scheduler используют fixed delay, локальный overlap guard, последовательный обход
и изоляцию ошибок per source/target; следующий tick является macro retry.

При активном publish standalone retention guard заменяется
`PublishLedgerSliceRetentionGuard`. При выключенном sync никаких transport/scheduler beans
не создаётся, а retention остаётся fail-open standalone.

`SyncHealthState` получает immutable latest-result snapshots из scheduler threads через
`ConcurrentHashMap`; durable pending/failed counts читаются отдельно из `PublishLedger`.
Actuator contributor дополнительно считает integrity-valid slices, которые удерживает
delivery guard. Runtime snapshots после restart имеют `NEVER_RUN`, ledger backlog сохраняется.
