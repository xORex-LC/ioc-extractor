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
- `IocUnknownConfigurationPreflight` проверяет неизвестные keys по reflection
  shape typed model, включая YAML overlay, CLI, system properties и env;
- `IocConfigPreflight` собирает межсекционные инварианты: artifact identity,
  schemas, ids, export/sync references, durations и selectors;
- `ConfigRegistryPreflight` проверяет provider/transform/filter/predicate keys
  по тому же `ConfigRegistryCatalog`, который используется при wiring;
- `IocConfigurationFailureAnalyzer` переводит startup failures в стабильные
  `CONFIG.*` сообщения и добавляет известные migration hints.

Validation constructors не должны бросать на operator mistakes: иначе binder
остановится на первой ошибке и скроет collect-all report.

## Runtime modes и lazy boundary

`ioc.runtime.mode` выбирает `oneshot` или `daemon`; observability mode выбирает
профиль вывода, но не заменяет runtime mode. Lightweight root/subcommand help,
`--version`, health query и syntax errors обслуживаются до Spring startup.

Оставшийся oneshot graph использует lazy initialization: validation-only path
не должен открывать service DB или transport. Daemon включает web/actuator и
eagerly собирает необходимые migrations, recovery и schedulers до operational
work. Конкретные adapters связываются только в composition root (`AppConfig`,
`SyncConfig`, `EventCoordinationConfig`, config-preflight configuration).

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
  `ConfigRegistryPreflight`, `IocConfigurationFailureAnalyzer`.
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
