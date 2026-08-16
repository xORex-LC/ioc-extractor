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
lifecycle/history/receipts
ID allocators/projection work
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
  -> resolve row_key
  -> one artifact transaction
       public rows: INSERT ... ON CONFLICT(row_key) DO NOTHING
       provenance:  upsert <artifact>_sources
       revision:    bump only when public rows inserted
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
3. **Identity задаётся public output values.** `key-columns`, `key-mode` и epoch
   формируют deterministic identity hash. Несовместимый drift отклоняется до
   записи, а не молча создаёт другое значение `row_key`.
4. **Schema reconciliation только additive.** Missing table/column можно
   создать; drop, rename, reorder-sensitive type drift и конфликт внутренних
   колонок должны завершить startup failure до частичной мутации.
5. **ID-space независим по artifact.** Compatibility writer пока использует
   schema-aware `max(id)+1`. Dataframe format v4 уже хранит dormant durable
   per-artifact allocator и global lifecycle allocator; P3 сделает их
   canonical authority. Atomic reservations monotonic по стратегии, не gapless
   и не возвращаются после failed commit либо удаления active/history rows.
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

### Lifecycle-aware storage path (dataframe v4; runtime dormant)

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
состоянии `DISABLED_COMPATIBLE`. V4 и P3-классы сами по себе не включают TTL:
production composition до activation slices использует compatibility writer.
Он берёт SQLite write ownership в состоянии `DISABLED_COMPATIBLE` и перестаёт
принимать записи сразу после начала activation.

Lifecycle-aware JDBC writer атомарно выполняет insert, renewal либо
archive/delete + recreate, обновляет compact provenance, observation marker,
insert-driven revision, projection generation и typed receipt. Один effective
UTC `asOf` читается только после получения write ownership. Public и lifecycle
ID резервируются отдельными committed transactions до canonical write, поэтому
ошибка создаёт допустимые gaps, но не позволяет переиспользовать ID. Complete
receipt публикуется в той же canonical transaction только после проверки всех
artifact markers и typed row totals; marker обязателен и для zero-row artifact.

В `ACTIVE` все service-local dataframe reads используют точный предикат
`_valid_until_epoch_ms > asOf`. Mutable projection получает один clock sample на
load, а immutable multi-artifact snapshot использует один общий `asOf` и одну
SQLite read snapshot как для coverage, так и для rows. В `ACTIVATING` reads
fail-closed. Переход в `ACTIVE` по-прежнему защищён transactional CAS и
set-based проверкой полной ordered metadata. Scheduler, startup reconciliation,
retention cleanup, clock high-water, health и runtime wiring принадлежат P4/P5.

## Отказы и восстановление

| Ситуация | Поведение | Восстановление |
|---|---|---|
| Schema/identity drift | startup/preflight failure до business write | исправить config или оформить явную миграцию/epoch |
| Artifact transaction failure | текущая transaction rollback; run fails | повтор invocation безопасен по `row_key` |
| Crash после DB commit до projection | canonical truth уже сохранена | daemon `ingest_run=DB_COMMITTED` запускает reprojection |
| CSV потерян или повреждён | business data не потеряна | полная idempotent projection из dataframe |
| Service ledger недоступен | coordination operation не притворяется успешной | восстановить service DB/permissions и повторить recovery |

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
- Identity: `ArtifactIdentityDefinition`, `ArtifactIdentityResolver`,
  `ArtifactIdentityStore` contract tests.
- Canonical/provenance behavior: `JdbcCanonicalArtifactRepositoryTest` и
  dataframe recovery integration tests.
- Module boundary: `adapters/adapter-store-jdbc/README.md`.

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
