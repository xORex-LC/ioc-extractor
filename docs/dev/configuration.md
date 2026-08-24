# Конфигурация и startup boundary

Конфигурация `ioc.*` является строгим внешним контрактом приложения. Bootstrap
bind-ит её в typed model, отклоняет неизвестные и семантически несовместимые
значения до runtime work и собирает composition graph. Domain/application не
знают Spring property model и не читают environment напрямую.

## Каналы и precedence

```text
packaged application.yml
  < optional ./configs/application.yml
  < environment / system properties
  < command line
```

Фактический winner определяет Spring Environment. После refresh и до runners
`IocConfigurationOverrideReporter` один раз сообщает только
`ioc.key <- source`; значения никогда не логируются.

`IOC_*` зарезервирован для `ioc.*`. Environment adaptation теряет исходные
границы слов, поэтому schema-aware matcher восстанавливает только известную
форму `IocProperties`; неизвестный `IOC_*` является startup failure, а не
неиспользованной переменной.

## Startup validation

```text
Environment prepared
  -> YAML syntax parse / CONFIG.YAML_INVALID
  -> unknown-key shape preflight (all property sources)
  -> @ConfigurationProperties binding/conversion/JSR-380
  -> collect-all semantic config validation
  -> config-to-registry/catalog validation
  -> context refresh and concrete graph assembly
  -> value-free override report
  -> ApplicationRunner / daemon work
```

Границы разделены намеренно:

- Spring binder владеет shape, conversion и bean validation;
- `IocYamlSyntaxCheck` предоставляет side-effect-free синтаксическую проверку
  candidate-файла до Spring startup, SQLite и transport initialization;
- `IocYamlConfigurationFailureAnalyzer` переводит ранний SnakeYAML failure в
  value-free `CONFIG.YAML_INVALID` с line/column без вывода самой строки;
- `IocUnknownConfigurationPreflight` проверяет неизвестные keys по reflection
  shape typed model, включая YAML overlay, CLI, system properties и env;
- `IocConfigPreflight` собирает межсекционные инварианты: artifact identity,
  schemas, ids, export/sync references, durations и selectors; projection
  `ioc.sink.artifacts[].path` обязан называть файл, а не filesystem root;
- `ConfigRegistryPreflight` проверяет provider/transform/filter/predicate keys
  по тому же `ConfigRegistryCatalog`, который используется при wiring;
- `IocConfigurationFailureAnalyzer` переводит startup failures в стабильные
  `CONFIG.*` сообщения и добавляет известные migration hints.

Validation constructors не должны бросать на operator mistakes: иначе binder
остановится на первой ошибке и скроет collect-all report.

Для packaged systemd deployment unit запускает syntax check через
`ExecCondition`. При exit code `78` systemd пропускает activation и не входит в
`Restart=on-failure`, поэтому детерминированная YAML-ошибка не создаёт restart
storm. `RestartPreventExitStatus=78` остаётся дополнительной защитой для main
process. Операторский
`bin/ioc-config apply` принимает отдельный candidate, повторно проверяет staged
copy, атомарно заменяет live YAML и восстанавливает previous config, если
обычный typed/semantic startup не достигает `UP`. Прямое редактирование live
файла остаётся технически возможным, но не является поддерживаемым workflow.

## Runtime modes и lazy boundary

`ioc.runtime.mode` выбирает `oneshot` или `daemon`; observability mode выбирает
профиль вывода, но не заменяет runtime mode. Lightweight root/subcommand help,
`--version`, health query и syntax errors обслуживаются до Spring startup.
`--version` требует embedded build identity; при её отсутствии CLI возвращает
однострочную metadata error и exit code `1`, не придумывая fallback и не
показывая stack trace.

Оставшийся oneshot graph использует lazy initialization: validation-only path
не должен открывать service DB или transport. Daemon включает web/actuator и
eagerly собирает необходимые migrations, recovery и schedulers до operational
work. Конкретные adapters связываются только в composition root (`AppConfig`,
`SyncConfig`, `EventCoordinationConfig`, config-preflight configuration).

### Lifecycle validity and runtime safety

P5 добавляет явную one-way activation policy поверх operational envelope:

- `ioc.lifecycle.validity.mode` — closed selector `disabled|fixed`; classpath и
  upgrade template используют `disabled`;
- `ioc.lifecycle.validity.fixed-ttl` — positive duration (default `12h`),
  обязательный в `fixed` mode;
- `ioc.lifecycle.validity.existing-records` — `reject|expire`; только явно
  выбранный `expire` разрешает destructive archival legacy rows;
- `ioc.lifecycle.history-retention` и `receipt-retention` — positive сроки
  хранения history snapshots и complete confirmation receipts, оба default
  `30d`;
- `ioc.lifecycle.history-cleanup-interval` — positive cadence independent
  retention discovery, default `1h`; backlog продолжается bounded follow-up
  tasks без ожидания следующего часа;
- `ioc.lifecycle.reconcile.backstop-interval` — positive максимальный idle
  interval между read-only nearest-deadline/projection correctness checks,
  default `5s`;
- `ioc.lifecycle.reconcile.batch-size` — positive bound одной SQLite
  archive/delete либо retention transaction, default `1000`;
- `ioc.lifecycle.clock.max-backward-skew` и `max-clamp-duration` — positive
  system UTC safety limits, defaults `2s` и `30s`.

После начала activation durable DB state не допускает возврат в `disabled` и
выдаёт stable `LIFECYCLE.POLICY_MISMATCH`. Нулевое или отрицательное
duration/value отклоняется collect-all preflight и никогда не интерпретируется
как команда очистки.

### Managed dataframe import

`ioc.dataframe-import` — отдельная strict typed shape для принятого ADR-0024.
Classpath и production template задают `enabled: false` и пустые `sources`,
`authority-profiles`, `contracts`. При `enabled: true` daemon собирает полный
local/SMB intake runtime: ownership claim, private immutable snapshot, exact
recognition, sealed staging, atomic canonical promotion, recovery, terminal
report/disposition и retention. Stateful storage обязан быть JDBC; startup
recovery barrier открывает ordinary ingest и import только совместно.

При `enabled: true` bootstrap обязан до runtime собрать весь декларативный
catalog: source ссылается на существующие contract и authority profile, SMB
source — на общий `ioc.sync.endpoints`, mappings — на artifact columns,
versioned record/match keys и зарегистрированные transforms. Authority profile
задаёт верхнюю границу destructive merge, related routing и machine-only
formula preservation. Компилятор либо публикует immutable catalog с SHA-256
behavior fingerprint, либо возвращает все безопасные semantic violations;
частично разрешённого catalog нет.

`runtime.detect.poll-interval` остаётся correctness cadence полного перечисления.
`use-watch-service` для local и `use-change-notifications` для SMB включают
только latency hints; они не отключают polling/reconcile. SMB source использует
существующий endpoint из `ioc.sync.endpoints`, включая credentials и transport
timeouts, даже когда обычный `ioc.sync.enabled=false`. Обычный sync и import
делят lazy endpoint-keyed session pool, а long-poll `CHANGE_NOTIFY` держит
отдельные watch sessions.

`runtime.dirs.snapshots` содержит private immutable snapshots для обоих
transport-ов. Local-specific processing/terminal/quarantine roots применяются к
local source; SMB processing/terminal/quarantine namespaces создаются внутри
его remote inbox как `.ioc-managed-import/*`. Snapshot byte limit и stability
quiet period одинаково применяются к обоим transport-ам.

`ioc.artifact-identity.artifacts[]` в P0 дополнен декларативными `record-key` и
`match-keys[]`. Они именуют текущую row-key формулу и будущие альтернативные
active-record lookup paths, но сами по себе не меняют storage identity или
write path. Физическая миграция aliases/compound identity принадлежит P2.

## Неочевидные инварианты

1. **Unknown keys fail on every channel.** Нельзя добавлять «временно
   игнорируемый» `ioc.*` knob без typed model и semantics.
2. **Registry key имеет один catalog.** Preflight и runtime assembly не держат
   разные списки допустимых provider/transform/predicate names.
3. **List overlay следует binder semantics.** Index может прийти из более
   приоритетного source, но элемент bind-ится целиком из source, определившего
этот index; partial override не гарантирует сохранение остальных полей.
4. **Секреты не становятся diagnostics/log values.** Failure analysis и
   override report показывают key/source/reason, но не winning value.
5. **Legacy hints централизованы.** Удалённое свойство остаётся неизвестным key;
   analyzer лишь объясняет миграцию и не возвращает tombstone в typed model.

## Как расширять

- Новый knob: добавить typed field, validation/default semantics, packaged и
  deployment config, binding/preflight tests и затронутый capability guide.
- Новый selector: использовать typed enum/value object и fail-fast converter,
  а не строковые `if` по runtime graph.
- Новый registry-backed key: зарегистрировать component и key в одном catalog,
  затем добавить preflight/contract test.
- Новый external channel должен пройти тот же unknown-key и redaction contract;
  обход `Environment` напрямую не допускается.

## Источники истины

- External defaults/schema: `bootstrap/ioc-app/src/main/resources/application.yml`.
- Typed model: `IocProperties` и selector/value types.
- Operator contract: [configuration guide](../guides/configuration.md), полный
  production template и `ConfigurationDocumentationContractTest`.
- Strict boundary: `IocEnvironmentPropertyMatcher`,
  `IocUnknownConfigurationPreflight`, `IocConfigPreflight`,
  `ConfigRegistryPreflight`, `IocConfigurationFailureAnalyzer`,
  `IocYamlConfigurationFailureAnalyzer`, `IocYamlSyntaxCheck`.
- Dataframe import: `DataframeImportConfiguration`,
  `DataframeImportPropertyMapper`, framework-free
  `DataframeImportCatalogCompiler`, `DataframeImportRuntimeConfiguration`.
- Lifecycle: `ConfigPreflightConfiguration`, `EarlyCliLauncher`,
  `DaemonWebEnvironmentPostProcessor`.
- Contract tests: `IocPropertiesBindingTest`, unknown-key/preflight/analyzer/
  override reporter tests, `ConfigurationDocumentationContractTest` and
  application context tests.

## Когда обновлять документ

Обновить при изменении precedence, supported property channel, unknown-key
semantics, validation ownership, runtime-mode startup или lazy/eager boundary.

## Связанные документы

- [processing.md](processing.md) — rule/mapping registries.
- [storage.md](storage.md) — storage role selectors и migrations.
- [observability.md](observability.md) — value-free logging/redaction.
- [ADR-0016](../ADR/0016-config-preflight-strict-binding.md) — решение strict binding.
- [ADR-0024](../ADR/0024-managed-dataframe-import.md) — managed import contract и границы authority.
