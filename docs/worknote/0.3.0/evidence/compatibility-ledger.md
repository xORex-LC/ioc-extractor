---
title: "0.3.0 compatibility and consumer evidence"
version: "0.3.0"
goal_id: "R030-BASE"
status: "Baseline captured"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# BASE-CONTRACTS-08 — Compatibility и consumer baseline

Contract: [R030-BASE](../goals/R030-BASE-baseline.md).

Этот ledger фиксирует совместимые поверхности, которые последующие hardening
changes обязаны либо сохранить, либо изменить через явное versioning,
migration и release-note решение. Он не объявляет любой `public` Java type
внешним API и не заменяет authoritative capability docs, generated catalogs или
release evidence.

## Объект и метод

| Поле | Значение |
|---|---|
| Baseline subject | `ba0252d637f22e464b2a59ca5c8116c6ab248acd` |
| Product revision | `0.3.0-SNAPSHOT` |
| Released source version | `v0.2.0` (`ad255040e73f589cb0b1fcab3581d836699e1888`) |
| Comparison basis | Выбранные implementation/authority paths текущего контракта против `v0.2.0` |
| Capture date | `2026-07-28` |

Проверены:

- фактический Picocli help для root, `extract`, `export`, `sync` leaves и
  `health`;
- typed configuration authority и strict-preflight правила;
- применённые SQLite `user_version` в существующем local fixture;
- фактическая v1 export manifest shape;
- installer/deployer/uninstaller help и documented rollback protocol;
- versioning, deprecation и release-gate policy;
- наличие внешних и только reactor-local consumers;
- diff выбранных CLI/config/migration/manifest/packaging implementation paths
  против released `v0.2.0`.

Команды:

```bash
java -jar bootstrap/ioc-app/target/ioc-app-0.3.0-SNAPSHOT.jar <command> --help
packaging/install.sh --help
packaging/deploy-local.sh --help
packaging/uninstall.sh --help
sqlite3 <dataframe-db> 'PRAGMA user_version;'
sqlite3 <service-db> 'PRAGMA user_version;'
jq '{manifest_version, root_keys:(keys), artifact_keys:(.artifacts[0] | keys)}' \
  <completed-slice>/manifest.json
git diff --quiet v0.2.0..HEAD -- <selected-contract-implementation-paths>
```

Последняя команда вернула `0` для CLI adapter, `IocProperties`,
`application.yml`, JDBC migration resources, manifest codec и `packaging/`.
Это доказывает неизменность только перечисленных contract implementation paths
на baseline subject. Оно не является преждевременным утверждением, что весь
будущий `0.3.0` совместим с `0.2.0`.

## Классификация

| Класс | Значение для hardening |
|---|---|
| `supported-external` | Наблюдаемая поверхность, которую release сохраняет либо меняет только с compatibility decision и migration guidance |
| `internal` | Reactor-local implementation/API; может меняться при сохранении внешнего поведения и architecture boundaries |
| `planned-external` | Предполагаемый будущий consumer; до публикации и standalone consumer evidence это не действующий контракт |
| `unknown-consumer` | Contract role известна, но именованный deployment/system не зарегистрирован; отсутствие имени не разрешает тихо ломать поверхность |

## Compatibility matrix

| Surface | Baseline/version authority | Consumer status | Обязательство 0.3.0 | Upgrade | Rollback | Evidence owner |
|---|---|---|---|---|---|---|
| CLI commands, options, automation output и exit codes | Picocli command model; boot artifact version `0.3.0-SNAPSHOT` | `supported-external`; operators и scripts, именованные automation consumers не зарегистрированы | Сохранить command names/options, documented machine-readable summaries и exit semantics либо оформить observable change | Обновить automation до активации changed CLI; release notes дают exact before/after invocation/output | Активировать предыдущий immutable jar и matching config; automation должно соответствовать выбранной версии | `R030-QUAL`, `R030-TEST`, `R030-REL` |
| `ioc.*` configuration, defaults, validation и override precedence | `application.yml` + `IocProperties`; отдельного schema number нет | `supported-external`; operator-owned config | Сохранять accepted shape/default semantics; additions/default changes проходят config compatibility review; removed key hard-fails с migration hint | Existing config сохраняется; changed template пишется как `*.new` и вручную reconcile-ится | Использовать config, совместимый с предыдущим binary; не считать новый config автоматически обратно совместимым | `R030-QUAL`, `R030-DOC`, `R030-REL` |
| Diagnostics и ECS logging wire | Generated diagnostic/logging catalogs; nested ECS JSON, `ecs.version=8.11` | `supported-external`, `unknown-consumer`; operators/log collectors, именованный backend не зарегистрирован | Diagnostic codes, `event.action`, documented field paths и JSON scalar types являются machine contracts | Для wire change дать exact before/after JSON/query и migration action | Вернуть предыдущий binary и соответствующие collector queries/dashboards | `R030-TEST`, `R030-DOC`, `R030-REL` |
| Daemon health и build info | Loopback Actuator `/actuator/health`, components, `/actuator/info`; CLI health exit `0/1/2` | `supported-external`; deploy scripts, systemd/operator probes | Сохранять endpoint exposure, status/exit semantics и distinction между local readiness и optional remote degradation | Обновить probes/gates вместе с observable health change | Предыдущий binary + matching health-gate assumptions | `R030-TEST`, `R030-REL` |
| Canonical dataframe SQLite | `PRAGMA user_version=3`; artifact schema/identity reconciliation | `supported-external` как durable operator state, не как произвольный SQL API | Append-only migration, fail-fast на newer schema и identity drift; row identity/public ID/dedup semantics не менять неявно | Сделать consistent snapshot обеих DB; migration должна объявить source versions и post-migration state | Down migrations отсутствуют: восстановить matching pre-upgrade snapshot, а не только symlink | `R030-QUAL`, `R030-TEST`, `R030-REL` |
| Service SQLite ledgers | `PRAGMA user_version=7` | `supported-external` как durable recovery state; таблицы не объявлены third-party SQL API | Сохранять recovery/idempotency semantics; schema evolution только versioned migration | Обе DB backup-ятся и рассматриваются как один upgrade point | Восстановить обе DB из matching snapshot | `R030-QUAL`, `R030-TEST`, `R030-REL` |
| Mutable CSV projections | Configured artifact schemas: `masks`, `ip_list`, `address_blacklist`, `hashes`; identity epoch/schema fingerprints | `supported-external`, `unknown-consumer`; downstream reputation-list consumers не названы | Сохранять columns/order/types/normalization/routing, row identity, public IDs и keep-first semantics либо version/migrate contract | Reprojection допустим только из canonical DB и под новой принятой schema/identity policy | Восстановить previous binary/config/DB snapshot и заново project; не использовать hand-filled CSV как truth | `R030-QUAL`, `R030-TEST`, `R030-DOC`, `R030-REL` |
| Immutable export slice и JSON manifest | Manifest `SUPPORTED_VERSION=1`, `output_mode=complete`, strict fields/hashes, `_SUCCESS` marker | `supported-external`, `unknown-consumer`; publish targets и downstream readers не названы | Version/strict-decode policy, exact membership, SHA-256 binding и marker-last visibility сохраняются; incompatible shape требует нового manifest version/consumer migration | Consumers должны принять новую version до publish; незавершённая staging slice не становится видимой | Сохранять уже completed immutable slices; previous consumer читает только поддерживаемую version | `R030-TEST`, `R030-DOC`, `R030-REL` |
| Remote SMB fetch/publish | Configured endpoint/source/target names; ledger idempotency; completed-slice publication | `supported-external`; producer/publish roles подтверждены, endpoints operator-specific | Preserve polling/reconcile correctness, path/filter semantics, idempotency и marker-last delivery; push остаётся latency hint | Проверить against provisioned endpoint и reconcile pending ledgers | Остановить operations, вернуть binary/config/DB snapshot и явно reconcile внешние side effects | `R030-TEST`, `R030-REL` |
| Installer/deployment interfaces | `install.sh`, `deploy-local.sh`, `uninstall.sh`, installed `bin/ioc`, systemd unit и marked prefix layout | `supported-external`; Debian 11/12 operators | Сохранять documented flags/layout/config ownership/immutable activation/health gate либо дать operator migration | Verified artifact, DB backup, config `*.new` reconciliation, atomic activation | Previous release symlink + matching two-DB snapshot; input moves, projections, slices и remote effects отдельно reconcile-ятся | `R030-DOC`, `R030-REL` |
| Reactor Java/Maven types | One lockstep reactor; modules не публикуются отдельно | `internal`; только reactor-local dependencies и TCK consumers | `public` visibility сама по себе не создаёт external API; refactoring допустим при сохранении boundaries и внешних surfaces | Не применимо как published-library upgrade | Не применимо | `R030-ARCH`, `R030-QUAL` |
| Candidate shared libraries | Coordinates/repository/API/lifecycle ещё не приняты | `planned-external`; будущий `feeds-collector`, не существующий current consumer | До admission не обещать compatibility. Publication требует selected API, owner, versioning, flattened consumer POM и standalone out-of-reactor test | Определяется отдельным publication ADR/contract | Определяется отдельным publication ADR/contract | `R030-LIB`, `R030-TEST`, `R030-REL` |
| Local control events | `platform-events` publish-only in-process contracts, без broker/wire schema | `internal`; bootstrap/application listeners внутри reactor | Не превращать в broker/public wire API без реального external transport и отдельного решения | Не применимо | Не применимо | `R030-ARCH`, `R030-LIB` |

## Exact baseline details

### CLI

Поддерживаемая command tree:

```text
ioc
├── extract --source <file> [--dry-run]
├── export --profile <configured-name>
├── sync
│   ├── fetch [--source <name>] [--endpoint <name>] [--dry-run]
│   ├── publish [--profile <name>] [--target <name>] [--endpoint <name>] [--dry-run]
│   └── all [fetch/publish filters] [--dry-run]
├── health [--component <name>] [--json] [endpoint options]
└── --help / --version
```

Зафиксированные exit semantics:

| Path | Exit |
|---|---|
| Успешная command | `0` |
| Picocli software/runtime failure | `1` |
| Picocli usage error | `2` |
| `extract` завершён с accepted row/stage errors | `3` |
| `sync` summary имеет `failed > 0` | `1` |
| `health`: healthy / down / endpoint unavailable | `0` / `1` / `2` |
| Invalid/missing build version metadata | `1` |

Human-readable `message` не считается parser API. Детерминированные completion,
diagnostic и sync counters, raw health JSON, version/build identity и exit codes
считаются automation surfaces там, где они документированы.

### Configuration

Authority order:

```text
classpath application.yml
  < ./configs/application.yml
  < environment
  < JVM system properties
  < CLI
```

Unknown `ioc.*` keys на YAML/CLI/system-property channels и reserved `IOC_*`
environment keys отклоняются strict preflight. Semantic validation собирает все
ошибки, registry references проверяются до operational work. Удалённый
`read-timeout` не является alias или tombstone: он отклоняется с
`CONFIG.LEGACY_SYNC_TIMEOUT` migration hint к `request-timeout`.

Поскольку независимой config schema version нет, compatibility определяется
точной typed/reflection shape, defaults, precedence и migration hints,
выпущенными вместе с product version.

### Durable state и artifact identity

Наблюдаемые migration levels:

| DB | Current `user_version` | Migration chain |
|---|---:|---|
| Dataframe | `3` | dataframe format → artifact identity → artifact revision |
| Service | `7` | service schema → run ledger → drop legacy child table → ingest run ledger → export state → sync ledgers → publish reconcile index |

SQLite является truth; mutable CSV — восстановимая projection. Internal DB
columns/tables не обещаны как third-party query API, но durable state,
transaction/recovery semantics и supported migration/rollback являются внешним
операционным контрактом.

Изменение `key-columns`, `key-mode`, identity formula или epoch не является
обычным YAML refactoring. В baseline v0.2/P6 exported IDs monotonic, но не
gapless; failed reserved range не переиспользуется. I-22 переоткрывает только
эту внешнюю projection-семантику для P7: internal/lifecycle identities остаются
non-reusable, а внешний `id` становится отдельным reusable `export_slot`.
Keep-first dedup по-прежнему не разрешает повторной active строке менять
business row, сохраняя новый provenance отдельно.

### Export manifest v1

Фактический complete-slice manifest имеет root fields:

```text
manifest_version, slice_id, run_id, profile, created_at,
output_mode, plan_hash, format, artifacts
```

Каждый artifact содержит:

```text
artifact, file, rows, coverage, identity_epoch,
identity_hash, schema_hash, sha256
```

Decoder strict: unknown root/artifact fields, duplicate JSON properties и
неподдерживаемая manifest version отклоняются. `_SUCCESS` создаётся последним и
содержит exact manifest SHA-256; staging без marker не является complete
consumer-visible slice.

## Consumer register

| Consumer/role | State | Что подтверждено | Чего baseline не утверждает |
|---|---|---|---|
| Operator/admin | `confirmed` | CLI, configuration, health, packaging, SQLite backup/restore и runbooks имеют operator contract | Конкретная production installation не инвентаризирована |
| CLI automation | `role-confirmed`, `unknown-consumer` | Документированы automation-suitable exit codes/output | Нет списка scripts/jobs и owner |
| CSV/export reader | `role-confirmed`, `unknown-consumer` | Artifacts и immutable slices предназначены downstream consumers | Нет имени, версии или acceptance fixture реального reader |
| SMB producer/publish target | `role-confirmed`, `environment-specific` | Fetch/publish contracts и endpoint configuration существуют | Нет provisioned fixture в baseline; live `CHANGE_NOTIFY` cases skipped |
| Log/diagnostic collector | `role-confirmed`, `unknown-consumer` | Stable diagnostic/action/field/scalar contracts опубликованы | Нет имени SIEM/Elasticsearch/dashboard и сохранённого consumer query corpus |
| In-reactor module/TCK consumer | `confirmed-internal` | Maven graph и reusable TCK выполняются внутри reactor | Это не внешний published Maven consumer |
| Future `feeds-collector` | `planned` | Ближайший отдельный service рассматривается как consumer shared platform libraries | Service, coordinates и standalone dependency resolution ещё не существуют |

Consumer `unknown` означает missing evidence, а не отсутствие compatibility
obligation. Retiring such a surface requires либо зарегистрировать/мигрировать
consumer, либо принять явное release-level решение об unsupported contract.

## Source-version, upgrade и rollback obligations

| Transition | Baseline disposition |
|---|---|
| `v0.1.0 → v0.2.0` | In-place upgrade не поддержан; clean side-by-side prefix, old prefix остаётся rollback point |
| `v0.2.0 → current 0.3.0 candidate` | Packaged stand подтвердил compatibility start со schema `4/8`, неизменными 246 active rows/config/projections, explicit TTL activation и matching-state rollback; final RC всё равно повторяет admission после последующих changes |
| `v0.2.0 → v0.3.0 final` | Должен быть явно declared supported/unsupported после всех changes и проверен на representative durable state |
| Binary rollback после DB migration | Предыдущий symlink недостаточен; требуется matching pre-upgrade snapshot обеих SQLite DB |
| Config rollback | Previous binary получает matching previous config; `*.new` не merge-ится автоматически |
| External side effects | Inbox file moves, projections, immutable slices и remote operations не отменяются symlink/DB rollback автоматически и требуют reconcile |

Для каждого observable или breaking change release evidence обязано ответить:
что изменилось, кто затронут, есть ли automatic migration, что делает operator,
какой rollback поддержан.

## DATA-TTL-01 candidate delta

DATA-TTL-01 является принятым observable scope change относительно baseline.
Текущее candidate состояние имеет dataframe schema v4 и service schema v8.
Миграции additive, но включение validity для существующей dataframe DB является
явной one-way activation, а не automatic upgrade side effect.

| Surface | Candidate disposition |
|---|---|
| Configuration | Добавлен strict `ioc.lifecycle.*`; classpath/upgrade default `disabled`, fresh packaging template `fixed/12h`; изменившийся template сохраняется как `application.yml.new` |
| Durable state | Dataframe DB хранит lifecycle/history/receipt/control state; service DB получает observation-oriented ingest ledger migration; rollback после activation требует matching pre-activation config и обе DB |
| Mutable CSV | Column order/types сохраняются; expired rows исключаются, `time_first_seen`/`time_last_seen` остаются `NULL`, `valid_until` не публикуется |
| Immutable export | Expiry не меняет insert-driven revision и не создаёт slice; следующий new-row export читает только active membership |
| Internal и export identities | Internal row/lifecycle identities не переиспользуются. Внешний `id` трактуется как `(profile, artifact)` export slot: survivors сохраняют mapping, vanished rows освобождают slots при eligible export, новые lifecycle получают минимальные holes без compaction; source-owned ID остаётся business field. P7 pending |
| Health | Добавлен aggregate lifecycle component без IOC/source identifiers; clock failure может перевести readiness в `DEGRADED`/`DOWN` |

Operator migration и rollback опубликованы в
[canonical lifecycle guide](../../../guides/canonical-record-lifecycle.md), а
curated observable changes подготовлены в
[release-note input](../data-ttl-01/release-note-input.md). P6 privileged stand
на commit `e089ae6a3fe8592eb896878398b04088021f238f` подтвердил candidate
`v0.2.0 → v0.3.0` admission: compatibility start сохранил `246` active rows и
byte-exact projections, activation архивировал их с `LEGACY_ACTIVATION`, а
consistent activation rollback и полный release rollback восстановили
соответственно compatible schema `4/8` и исходное v0.2 schema `3/7` состояния.
Отдельная fresh installation завершилась healthy `ACTIVE` состоянием с
production `fixed/12h`. Final `R030-REL` admission повторяет этот сценарий, если
release candidate изменится после зафиксированного commit. Эти результаты не
проверяли исправленный reusable-slot contract: после P7 обязательны upgrade
seeding, survivor/no-compaction, smallest-hole и rollback assertions.

## Missing evidence и handoff

| Gap | Impact | Owner/exit condition |
|---|---|---|
| Именованные automation, artifact и log consumers не зарегистрированы | Нельзя доказать consumer acceptance только repository tests | `R030-DOC`/`R030-REL`: consumer/owner и representative contract fixture либо explicit unsupported disposition |
| Standalone published-library consumer отсутствует | Нельзя заявить external Maven compatibility | `R030-LIB` + `R030-TEST`: admitted coordinates, flattened POM и out-of-reactor consumer test |
| Live SMB fixture отсутствует | Два `SmbChangeNotifyContractTest` cases недоступны; live endpoint contract не подтверждён | `R030-TEST`/`R030-REL`: provisioned fixture либо explicit external-evidence disposition |
| Representative real consumer payload/query corpus отсутствует | Wire/schema regression может пройти только producer-side tests | `R030-TEST`: exact golden payload/query/CSV/manifest consumer contracts для принятых surfaces |

## Gate conclusion

`BASE-CONTRACTS-08` не обнаружил действующего external Maven API и поэтому не
замораживает все Java `public` types. Он обнаружил широкий non-Java external
contract: CLI, config, durable state, CSV/export, diagnostics/logging, health и
deployment. Эти surfaces должны участвовать в каждом module-wave
non-regression review.

P6 stand подтвердил TTL lifecycle compatibility `v0.2.0 → v0.3.0` для
зафиксированного commit и representative two-DB state. После I-22 он не является
полным candidate admission: P7 изменит dataframe migration/export mapping и
требует повторного stand. Это evidence также не закрывает отдельное
external-consumer evidence.
