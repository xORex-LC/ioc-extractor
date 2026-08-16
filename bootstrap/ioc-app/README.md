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
| `src/main/resources/` | Runtime `application.yml`, Logback config and Spring configuration metadata/hints |
| `src/test/java/com/iocextractor/` | Context, architecture, configuration/documentation contracts and golden e2e tests |

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

`EarlyCliLauncher` завершает root/subcommand help, `-V`/`--version`, `health` и
синтаксические ошибки до `SpringApplication.run()`, поэтому эти пути вообще не
создают Spring context. Оставшийся oneshot context использует
`spring.main.lazy-initialization=true`: validation-only ветки не создают
dataframe datasource, миграции или тяжёлые use cases.
В daemon mode `DaemonWebEnvironmentPostProcessor` принудительно возвращает eager initialization,
чтобы schema migration/recovery завершились до запуска poller-ов и scheduler-ов.
Spring Boot 4 подключён через focused starters. JDBC auto-configuration module отсутствует в
runtime graph: storage, migrations и transactions по-прежнему собираются явно в composition
root/JDBC adapter, а `DaemonWebEnvironmentPostProcessor` больше не поддерживает строковый список
несуществующих JDBC exclusions.

Executable jar содержит стандартный `META-INF/build-info.properties`, который
создаётся goal `spring-boot:build-info` только в этом runnable module. Обычная
локальная сборка включает Maven group/artifact/name/version и UTC build time.
Release/CI build дополнительно передаёт уже разрешённый полный Git SHA через
`-Dbuild.commit=<sha>`; профиль `embed-build-commit` добавляет его как
`build.commit`, проверяя full hexadecimal object ID, не выполняя `git` и не
требуя `.git` во время runtime.
Actuator публикует доступные значения в `/actuator/info`. Production beans не
требуют наличия `BuildProperties`, поэтому запуск из IDE без generated metadata
остаётся допустимым.
CLI `ioc --version` читает этот resource напрямую через JDK-only reader и потому
завершается до Spring startup; version обязательна, commit/time остаются optional.

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

## Canonical lifecycle runtime

P4 собирает framework-free lifecycle use cases с SQLite adapters через common
`CanonicalDataAdmissionState`. Stateful oneshot extract/export вызывают
admission defensively; daemon export, deadline и mutable-projection schedulers
остаются инертны, пока ingestion startup coordinator не завершит run/source
recovery и admission. Deadline и projection workers владеют отдельными
single-thread `ScheduledExecutorService`, coalesce-ят lossy events и опираются
на durable nearest deadline/generation плюс `5s` backstop.

`LifecycleHealthIndicator` только читает aggregate durable state и отображает
safe `UP`, recoverable `DEGRADED` или fail-closed `DOWN`. Typed `ioc.lifecycle`
P4 settings ограничивают batch/backstop/history и clock rollback, но production
preset остаётся `DISABLED_COMPATIBLE` до explicit P5 activation. Spring
`@Scheduled`, ShedLock, Spring Batch, новый module и новая runtime library не
используются.

## Зависимости

**Зависит от:** selected platform/core/adapters modules, Spring Boot and its
Logback `StructuredLogEncoder`; `IocEcsStructuredLogEncoder` сохраняет static
`event.dataset` внутри nested ECS context-pair object Boot 4.

**Не импортируется:** no inner module depends on `ioc-app`.
