# bootstrap/ioc-app

## Назначение

Spring Boot executable jar and composition root for the IOC extractor.

**Правило слоя:** owns runtime configuration, Spring wiring and executable
packaging. Business rules stay in domain/application; IO details stay in
adapters.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Executable app Maven module |
| `src/main/java/com/iocextractor/` | Application entrypoint and bootstrap config |
| `src/main/resources/` | Runtime `application.yml` and `logback-spring.xml` |
| `src/test/java/com/iocextractor/` | Context, architecture and golden e2e tests |

## Artifact export graph

`ExportPlanCatalog` eagerly и без IO преобразует `ioc.export.profiles` в
resolved application plans. На этой границе проверяются уникальность profiles,
ссылки только на enabled sink artifacts, наличие identity policy, file-name
коллизии и единственный поддержанный v1 mode `complete`. `planHash` включает
CSV format, ordered schema, identity и весь active mapping артефакта.

Service datasource/migrations скрыты за `LazyServiceStorage`, который не
реализует `DataSource` и поэтому не активируется type-discovery механизмами
Spring/Actuator. Export use cases зарегистрированы как lazy beans.
`ExportCommand` держит только `ObjectProvider`, поэтому `--help`, `extract` и
`health` не открывают service SQLite. Первый фактический
`ioc export` связывает `JdbcArtifactRevisionReader`, `JdbcSnapshotSliceReader`,
`JdbcExportRunLedger`/progress store, `CsvArtifactSliceWriter` и Jackson codec.

`EarlyCliLauncher` завершает root/subcommand help, `health` и синтаксические ошибки до
`SpringApplication.run()`, поэтому эти пути вообще не создают Spring context. Оставшийся oneshot
context использует `spring.main.lazy-initialization=true`: validation-only ветки не создают
dataframe datasource, миграции или тяжёлые use cases.
В daemon mode `DaemonWebEnvironmentPostProcessor` принудительно возвращает eager initialization,
чтобы schema migration/recovery завершились до запуска poller-ов и scheduler-ов.
Spring Boot 4 подключён через focused starters. JDBC auto-configuration module отсутствует в
runtime graph: storage, migrations и transactions по-прежнему собираются явно в composition
root/JDBC adapter, а `DaemonWebEnvironmentPostProcessor` больше не поддерживает строковый список
несуществующих JDBC exclusions.

`LoggingExportObserver` — bootstrap adapter application-порта: он переводит
durable checkpoints saga в ECS actions/fields, не добавляя SLF4J-зависимость в
application core.

`LoggingPipelineDecisionTracer` — второй outbound adapter: он реализует двойной
затвор `per-item-trace-enabled × logger TRACE`, строит ECS event только после
обоих проверок, пишет safe short identity и маскирует URL query. Application
stages передают ему только уже вычисленные решения.

Composition root выбирает typed `PipelineFailurePolicy`, diagnostic budget и
non-throwing diagnostics bridge. Default application config — `fail-fast`; production
daemon template явно задаёт `collect-and-continue` и budget 10 000.

## Зависимости

**Зависит от:** selected platform/core/adapters modules, Spring Boot and its
Logback `StructuredLogEncoder`; `IocEcsStructuredLogEncoder` сохраняет static
`event.dataset` внутри nested ECS context-pair object Boot 4.

**Не импортируется:** no inner module depends on `ioc-app`.
