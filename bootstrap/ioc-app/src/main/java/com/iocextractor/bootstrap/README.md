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
| `AppConfig.java` | Сборка beans: движок, рефанг, экстрактор, политика, sinks, storage и use cases |
| `ExportPlanCatalog.java` | IO-free cross-validation `ioc.export` и resolution immutable `ExportPlan` |
| `LoggingExportObserver.java` | `ExportObserver` → ECS actions/fields через observability helpers |
| `ConditionalOnServiceStorage.java` | Условие регистрации lazy service-storage graph независимо от runtime mode |
| `LazyServiceStorage.java` | Не-`DataSource` holder: открывает, мигрирует и закрывает service SQLite по первому явному запросу |
| `*HealthIndicator.java` | Actuator health contributors для ledger, artifact storage и JDBC storage |

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
`ExportCommand` разрешает use cases только внутри `call()`, поэтому построение
root CLI и unrelated commands не создаёт `var/ioc-service.db`.

Resolved profile строится из ordered sink columns, identity definition и CSV
format. `schemaHash` покрывает ordered `(column, declaredType)`, `identityHash`
переиспользует canonical identity formula, `mappingHash` — accepts/filters/id и
column providers/constants/predicates/transforms, а artifact file name берётся
из leaf configured sink path. Эти значения входят в `planHash`; schema/identity
затем проверяются snapshot reader-ом против canonical metadata.
