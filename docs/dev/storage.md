# Canonical storage и производные артефакты

Storage capability хранит business truth и durable service state, обеспечивает
stable identity, schema evolution и транзакционные checkpoints. Она не решает,
когда запускать ingest/export/sync, и не определяет CSV mapping или transport.

## Два storage role

```text
ioc-dataframe.db                    ioc-service.db
business rows                      ingestion / ingest_run
<artifact>_sources                 export run/progress
artifact identity/revision         remote fetch/publish ledgers
match aliases                      import delivery/transition ledger
lifecycle/history/receipts
ID allocators/projection work
export-slot registry
```

`dataframe` — источник истины для IOC-артефактов. `*_generated.csv` является
перезаписываемой проекцией, а immutable export slice — снимком для доставки.
`service` хранит coordination state и не является копией business data.

Application работает через storage-neutral ports. Spring JDBC, HikariCP,
Xerial SQLite, SQL, migrations и transaction mechanics принадлежат
`adapter-store-jdbc`; composition и datasource lifecycle — `bootstrap/ioc-app`.

## Canonical write

```text
prepared rows
  -> materialize deferred ids
  -> resolve versioned record key and usable match aliases
  -> one artifact transaction
       active match: zero / exact-one / multi at one effective asOf
       public rows: insert, renew, restart or policy-owned mutation
       aliases:     maintain canonical_match_alias with the lifecycle
       provenance:  upsert <artifact>_sources
       revision:    bump only for revision-significant public change
  -> full mutable projection from canonical table
```

Одна repository operation атомарна в пределах одного artifact. Pipeline пишет
настроенные artifacts последовательно; общей транзакции на весь extraction run
нет. Поэтому новая логика не должна предполагать cross-artifact rollback.

## Инварианты

1. **SQLite dataframe — canonical truth.** Oneshot и daemon накапливают данные
   одинаково; удаление CSV не удаляет business data.
2. **Keep-first отделён от provenance.** Повторный `row_key` не меняет public
   row/id, но новый source сохраняется в `<artifact>_sources`.
3. **Identity задаётся public output values.** Versioned record key определяет
   immutable `row_key`, а named match keys дают альтернативные active-only
   aliases. `key-columns`, `key-mode`, definition name и epoch компилируются до
   startup storage admission. Несовместимый drift или migration collision
   отклоняется до business write, а не молча создаёт другое значение
   `row_key`.
4. **Schema reconciliation только additive.** Missing table/column можно
   создать; drop, rename, reorder-sensitive type drift и конфликт внутренних
   колонок должны завершить startup failure до частичной мутации.
5. **Canonical и export identity разделены.** Compatibility writer использует
   schema-aware `max(id)+1`. Lifecycle-aware writer резервирует monotonic
   per-artifact canonical row ID и global lifecycle ID; они не gapless и не
   возвращаются после failed commit либо удаления active/history rows. Внешняя
   колонка export `id` после activation берётся из отдельного reusable registry
   с namespace `(profile, artifact)` и не является foreign key canonical data.
6. **Revision отражает изменение public content.** Duplicate-only observation
   не двигает `artifact_revision` и не продлевает export quiet period.
7. **Service и dataframe lifecycle раздельны.** Unrelated lightweight CLI
   paths не должны открывать service DB; daemon eagerly собирает требуемые
   storage/recovery контуры до operational work.

## Schema и migrations

Версионированные service/dataframe migrations применяются через SQLite
`user_version`. Config-driven public artifact tables дополнительно сверяются с
ordered column definitions и declared storage types. Internal columns имеют
отдельный namespace и не считаются частью public CSV schema.

Health проверяет открытие, schema version, необходимые PRAGMA и integrity
probe. Health сообщает состояние storage, но не заменяет transactional
guarantees и recovery ledgers.

Service schema v9 добавляет `import_delivery`, append-only transition audit,
global sequence authority и индексы ordered head/retry/recovery. Bulk mapped
values не попадают в service DB: каждый delivery использует отдельную private
SQLite workspace schema v1. Workspace хранит logical rows, artifact branches,
tri-state cells (`0=ABSENT`, `1=NULL`, `2=VALUE`), match keys и safe row errors;
после sealing он checkpoint-ится, atomically переименовывается и проверяется
read-only по pinned digest и metadata.

### Lifecycle-aware storage path (dataframe v4; explicit activation)

V4 размещает все lifecycle facts рядом с business rows именно в dataframe DB,
не в service DB. Статическая migration создаёт one-way activation/clock state,
resumable activation progress, durable ID allocators, projection generations,
observation/reconcile markers и normalized complete-receipt headers. Для каждого
configured artifact reconciler additively создаёт:

- nullable internal `_lifecycle_id`, first/last-confirmed epoch-ms и
  `_valid_until_epoch_ms` в active table;
- unique lifecycle identity и range index
  `(_valid_until_epoch_ms, _lifecycle_id)`;
- typed ordered `<artifact>_history` и compact
  `<artifact>_history_sources` с retention index;
- typed `<artifact>_receipt_rows` без service-owned public ID.

Upgrade сохраняет существующие rows и оставляет lifecycle columns `NULL` в
состоянии `DISABLED_COMPATIBLE`. В P5 mode `disabled` продолжает использовать
compatibility writer. Явный `fixed + existing-records: expire` переводит control
через `ACTIVATING`: bounded keyset batches переносят legacy rows и compact
provenance в history с причиной `LEGACY_ACTIVATION`, удаляют active rows и
создают projection work, не увеличивая insert-driven revision. Durable progress
делает restart идемпотентным; intake/export остаются за admission barrier.

Lifecycle-aware JDBC writer атомарно выполняет insert, renewal либо
archive/delete + recreate, обновляет compact provenance, observation marker,
insert-driven revision, projection generation и typed receipt. Один effective
UTC `asOf` читается только после получения write ownership. Canonical-row и
lifecycle ID резервируются отдельными committed transactions до canonical
write, поэтому ошибка создаёт допустимые gaps, но не позволяет переиспользовать
эти internal identities. Complete
receipt публикуется в той же canonical transaction только после проверки всех
artifact markers и typed row totals; marker обязателен и для zero-row artifact.
Complete receipt читается только при точном совпадении source content key и
processing-policy fingerprint и только до `purge_after`; иначе ingestion
выполняет обычный ETL. Observation identity отдельна от content key, поэтому
новая доставка тех же bytes создаёт новую попытку и подтверждает freshness,
тогда как retry/recovery той же попытки остаётся идемпотентным.

В `ACTIVE` все service-local dataframe reads используют точный предикат
`_valid_until_epoch_ms > asOf`. Mutable projection получает один clock sample на
load, а immutable multi-artifact snapshot использует один общий `asOf` и одну
SQLite read snapshot как для coverage, так и для rows. В `ACTIVATING` внешние
stateful entry points и immutable snapshots fail-closed; внутренний mutable
projection reader допускается только с active predicate, чтобы установить
пустую/очищенную проекцию до `ACTIVE`. Переход в `ACTIVE` защищён transactional
CAS и set-based проверкой полной ordered metadata.

P4 runtime поверх этих facts реализует:

- durable non-decreasing UTC high-water в `canonical_lifecycle_control`; small
  rollback clamp-ится, material/prolonged rollback fail-closed;
- constant-cardinality reconciliation checkpoint и indexed bounded
  archive/delete с одним неизменным `cycleAsOf`; five-second backstop сначала
  только перечитывает nearest deadline и не пишет idle cycles;
- независимый bounded history retention по
  `(closed_at_epoch_ms, history_id)`;
- durable required/projected generation и retryable projection failure state;
- read-only aggregate status без IOC/source identities.

Nearest deadline, history retention и mutable projection schedulers живут в
bootstrap, а SQL и transaction boundaries — в этом adapter. Expiry увеличивает только mutable
projection generation: insert-driven `artifact_revision` и immutable export
trigger не меняются. Незавершённый reconciliation checkpoint при следующем admission
помечается failed, после чего due rows безопасно перечитываются из canonical
truth; частично закрытые records не воскресают.

Dataframe v6 мигрирует последний v4 journal row в singleton
`lifecycle_reconcile_state`. Старый `lifecycle_reconcile_cycle` сохраняется
read-only для upgrade/rollback evidence, но больше не растёт.

Mutable CSV projection reads active canonical rows through one ordered JDBC
cursor and writes a sibling temporary file before `ATOMIC_MOVE`. It therefore
keeps constant row-memory, preserves the previously installed projection on a
cursor/encoding/write failure, and uses the same active snapshot boundary as
other lifecycle-aware reads.

### Versioned identity and active matching (dataframe v7)

V7 additively создаёт `canonical_match_definition` и
`canonical_match_alias`, а в `artifact_identity` сохраняет имя и fingerprint
record-key definition. Match lookup индексируется по
`(artifact, definition_id, key_hash, key_canonical)` и всегда проверяет как
SHA-256 digest, так и canonical material. Active candidate дополнительно обязан
иметь тот же lifecycle ID в canonical table и
`_valid_until_epoch_ms > asOf`; history не участвует.

`JdbcArtifactIdentityStore` выполняет epoch migration одной transaction для
всех pending artifacts. Сначала он рассчитывает новые record keys и aliases в
TEMP shadow tables с unique collision preflight. Только после успешной полной
стадии durable rows получают временные reserved keys, затем окончательные keys,
definitions и aliases. Ошибка любого artifact откатывает весь migration и не
разрешает создать writer через startup identity-validation dependency.

Default configuration переводит `address_blacklist` и `hashes` с
first-non-empty на compound v2 record keys. Миграция меняет только internal
`row_key`/alias metadata: public columns, canonical/lifecycle IDs, revisions и
export-slot ownership сохраняются. Named match definitions остаются
альтернативными ключами поиска; совпадение нескольких active lifecycles
является конфликтом и не склеивает records автоматически.

`JdbcCanonicalMatchPlanner` принимает batch requests, помещает key material в
connection-local TEMP table и одним set-based join возвращает zero/exact-one/
multi plan в исходном порядке. `JdbcCanonicalMutationEngine` использует этот
же connection-scoped механизм для ordinary lifecycle writer и managed-import
promotion. Insert/restart/renew/archive поддерживают aliases атомарно; public
mutation отдельно сообщает update, clear, no-op или TTL confirmation. Изменение
record key, включая очистку всех его значений, не допускается in-place и должно
создать новую canonical record.

### Export-slot storage path (dataframe v5 and v8)

V5 additively создал в dataframe DB три export-owned структуры:

- `export_slot_assignment` с unique ownership как lifecycle→slot, так и
  slot→lifecycle внутри `(profile, artifact)`;
- legacy `export_slot_free`, чей primary key одновременно являлся ordered index
  для smallest-free lookup;
- `export_slot_state` с policy version, durable `next_slot`, canonical
  `source_generation` и временем последнего reconciliation.

V8 атомарно преобразует legacy per-hole rows в coalesced
`export_slot_free_range(profile, artifact, range_start, range_end)`, не меняя
assignments и `export_slot_state`. Split/merge ranges, exact preferred request,
occupied fallback и survivor mismatch policy выполняются connection-scoped в
той же owning canonical transaction. Стоимость sparse request зависит от числа
затронутых ranges, а не от числовой величины slot.

Registry не хранится в service DB: reconciliation active membership и slot
state выполняется одной SQLite write transaction. После неё read snapshot
сверяет generation до выдачи строк. Первое обращение seed-ит active lifecycle
из текущих canonical IDs; положительные holes ниже seed high-water становятся
free. Дальнейший high-water продвигается только durable `next_slot`, а не
пересчитывается через `MAX(active id)`. Артефакты без public `id` не создают
namespace rows.

## Отказы и восстановление

| Ситуация | Поведение | Восстановление |
|---|---|---|
| Schema/identity drift | startup/preflight failure до business write | исправить config или оформить явную миграцию/epoch |
| Artifact transaction failure | текущая transaction rollback; run fails | повтор invocation безопасен по `row_key` |
| Crash после DB commit до projection | canonical truth уже сохранена | daemon `ingest_run=DB_COMMITTED` запускает reprojection |
| Crash в expiry drain | committed batches остаются history, незавершённый cycle остаётся `STARTED` | следующий admission помечает cycle interrupted и продолжает indexed reconcile |
| Mutable projection/ack failure | required generation остаётся больше projected | отдельный projection backstop повторяет полную atomic CSV replacement |
| System UTC rollback | small skew clamp-ится; material/prolonged skew отклоняет safe time | исправить clock; readiness отражает `DEGRADED`/`DOWN`, durable high-water не уменьшается |
| CSV потерян или повреждён | business data не потеряна | полная idempotent projection из dataframe |
| Service ledger недоступен | coordination operation не притворяется успешной | восстановить service DB/permissions и повторить recovery |
| Import workspace остался `.building.db` | файл не считается sealed evidence и не допускается к promotion | явно перестроить его из pinned snapshot по service-ledger checkpoint |
| Sealed import stage не совпадает с digest/metadata | fail-closed до dataframe transaction | восстановить pinned stage либо перестроить из snapshot |
| Crash после import dataframe commit до service checkpoint | canonical mutation уже завершена ровно один раз | `import_commit` receipt восстанавливает result и продолжает finalization без повторного apply |

## Как расширять

- Новый SQL backend реализует существующие ports отдельным storage adapter-ом;
  SQL types и transaction semantics не просачиваются в application.
- Новая public column требует additive schema reconciliation, mapping update и
  проверки export schema fingerprint.
- Изменение identity formula требует осознанного epoch/migration решения; это
  не обычная правка YAML.
- Новый durable workflow получает собственный ledger/state model, а не
  перегружает `ingest_run` или `export_run` чужими состояниями.

## Источники истины

- Storage ports/models: `application.port.out.artifact`,
  `application.port.out.ingest`, `application.artifact`.
- JDBC implementation: `adapter-store-jdbc` и его migration resources.
- Runtime schema/config: `IocProperties`, `application.yml`,
  `DataframeSchemaReconciler`.
- Identity/matching: `ArtifactIdentityDefinition`,
  `CanonicalArtifactKeyResolver`, `CanonicalMatchPlanner`,
  `ArtifactIdentityStore` contract tests.
- Canonical/provenance behavior: `JdbcCanonicalArtifactRepositoryTest` и
  dataframe recovery integration tests.
- Module boundary: `adapters/adapter-store-jdbc/README.md`.
- Managed import staging and recovery:
  [dataframe-import.md](dataframe-import.md), delivery-ledger TCK and
  `JdbcImportWorkspaceIT`.

## Когда обновлять документ

Обновить при смене source of truth, identity/epoch contract, transaction
boundary, migration policy, revision semantics или разделения service/dataframe.
SQL helper или pool implementation detail сюда переносить не нужно.

## Связанные документы

- [processing.md](processing.md) — подготовка write plans и policy checkpoint.
- [ingestion.md](ingestion.md) — write→project run ledger.
- [artifact-export.md](artifact-export.md) — consistent read snapshot.
- [ADR-0015](../ADR/0015-retire-legacy-csv-lookup-storage.md) — отказ от legacy
  CSV storage/lookup mode.
- [ADR-0020](../ADR/0020-canonical-record-expiration-lifecycle.md) — lifecycle
  semantics, history, identity and activation boundaries.
- [ADR-0024](../ADR/0024-managed-dataframe-import.md) — managed import delivery,
  active-only matching and shared canonical mutation decision.
