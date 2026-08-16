---
title: "R030-DATA / DATA-TTL-01 — canonical record validity lifecycle"
version: "0.3.0"
goal_id: "R030-DATA"
work_item_id: "DATA-TTL-01"
status: "Implementation"
document_type: "Release goal and work-item contract"
source_of_truth: false
language: "ru"
---

# R030-DATA / DATA-TTL-01 — canonical record validity lifecycle

## Outcome

Каждая canonical artifact record имеет внутренний, устойчивый к restart срок
активности. Истёкшая запись немедленно исключается из новых canonical reads,
mutable dataframe и immutable export slices, затем ограниченно переносится в
history и физически удаляется из active storage. Повторное успешное наблюдение
после expiry создаёт новую lifecycle с новым service-owned public ID.

`R030-DATA` является **MUST** goal и release blocker 0.3.0. Единственный текущий
work item — `DATA-TTL-01`; он выполняется по
[implementation plan](implementation-plan.md).

## Scope

### Входит в V1

- один storage-neutral `FixedRecordValidityPolicy` для всех canonical records;
- atomic confirmation после parsing и failure-policy checkpoint, внутри
  successful canonical transaction;
- internal UTC lifecycle timestamps и exact active predicate
  `valid_until > asOf`;
- expiry, history, durable ID allocation и lifecycle projection recovery;
- bounded deadline reconciliation, startup recovery и history cleanup;
- `disabled` compatibility mode, explicit one-way activation и named
  `existing-records: expire` migration для legacy rows;
- identical-source confirmation через bounded complete receipt с processing
  policy fingerprint и safe ETL fallback;
- existing export revision semantics: only new public rows advance
  `artifact_revision`; expiry uses separate lifecycle projection-work state;
- daemon и stateful oneshot/export admission;
- aggregate read-only health, typed diagnostics и operational logs;
- fresh-install production preset fixed `12h` и history/receipt retention `30d`;
- upgrade, rollback, crash/race и 100k simultaneous-expiry evidence.

### Не входит

- выбор разных validity policies или TTL-значений по source, IOC type,
  artifact либо отдельной record; V1 применяет одну fixed policy, но сохраняет
  отдельный `valid_until` для каждой lifecycle;
- отсутствие IOC в неполном feed как немедленный revoke;
- downstream DNS/firewall lifecycle management или acknowledgement protocol;
- изменение существующих public schemas, заполнение public `time_*` либо
  передача `valid_until`;
- manual `reconcile-now`, `expire-all`, `extend-all` или `disable-now` CLI/API;
- retroactive duration recompute, mass extension или deactivation migration;
- per-record history search UX;
- бессрочная гарантия no-ETL duplicate path;
- STIX `revoked`, OpenCTI `detection` или public `score` как состояние обычного
  expiry;
- per-read decay-score calculation вместо indexed active predicate;
- Spring `@Scheduled`, ShedLock, Spring Batch, multi-daemon lease/fencing либо
  новая database/scheduler library в V1.

## Обязательные invariants

1. TTL принадлежит canonical record lifecycle, а provenance только доказывает
   observations; ни source, ни IOC taxonomy не владеют deadline.
2. Подтверждением считается только successful canonical commit после parsing и
   failure-policy checkpoint. Detection, parsing start и failed transaction не
   продлевают lifecycle.
3. Любое принятое observation продлевает active lifecycle до `asOf + fixedTtl`.
   Физическое coalescing renewal writes допустимо только после отдельного
   доказательства эквивалентности и не входит в V1.
4. Граница активности half-open: при `asOf == valid_until` запись уже expired.
5. Confirmation после deadline не воскрешает старую history: прежняя lifecycle
   закрывается, новая получает новые lifecycle/public IDs.
6. Service-owned public ID никогда не переиспользуется; gaps допустимы.
   Source IDs остаются отдельными namespaced provenance identities.
7. `artifact_revision` сохраняет текущую insert-driven семантику: её двигает
   commit хотя бы одной новой public active row. Expiry, renewal и duplicate
   confirmation revision не меняют и новый immutable slice не создают.
8. Expiry записывает отдельный durable lifecycle projection-work/cycle state для
   mutable dataframe и recovery; export scheduler этот state не использует.
9. Ошибка projection/export после canonical expiry не возвращает record в
   active set; convergence восстанавливается из durable state.
10. Downtime не приостанавливает absolute TTL. Lifecycle использует injected UTC
   clock, durable non-decreasing high-water и fail-closed clock policy.
11. После persisted activation запуск той же БД с `disabled` запрещён. Обычный
    rollback возможен только согласованным restore application/config/обеих DB.
12. Content/source identity не является identity конкретной доставки. Каждая
    новая доставка получает durable observation identity, а recovery одного и
    того же observation после canonical commit не продлевает TTL повторно.

## Compatibility and operator contract

### Fresh installation

Production template явно включает fixed `12h`. Пустой active set и окна между
feeds являются допустимым бизнес-состоянием. Classpath default при этом остаётся
`disabled`, чтобы не менять upgrade behavior молча.

### Existing installation

Rollout обязателен в два шага:

1. установить TTL-capable binary и успешно запустить его с effective
   `mode=disabled`;
2. остановить stateful work, сохранить exact config и согласованный snapshot
   canonical/service SQLite DB, затем явно включить fixed policy вместе с
   `existing-records: expire` и перезапустить.

Activation выполняется до readiness/intake/export, идемпотентно закрывает все
legacy records с history/activation-cycle evidence и mutable projection work,
не двигает insert-driven revision и может оставить active dataframe полностью
пустым. Архивные source documents автоматически не replay-ятся.
Успешная readiness является границей обычного rollback; после неё основной путь
— roll-forward/retry, а restore старого snapshot является disaster recovery с
возможной потерей новых confirmations.

### Policy changes

Изменение fixed duration действует prospective: уже записанные deadlines
сохраняются до следующего accepted observation. Нулевые и отрицательные
duration запрещены. `ttl=0` никогда не является migration command.

## Operational contract

- Logical exclusion происходит непосредственно на deadline независимо от
  physical cleanup.
- Healthy idle daemon начинает reconciliation не позднее `5s` после deadline.
- До `100 000` records могут стать due одновременно; cleanup использует
  indexed keyset batches и bounded transactions, не materialize-ит весь set.
- `DEGRADED` не закрывает intake, пока active-read invariant доказуем;
  `DOWN` закрывает readiness и stateful work при потере policy/clock/read safety.
- Health показывает только aggregate mode/state/backlog/cycle/history/projection
  statistics без IOC, row keys и source names.
- History хранит typed full business-row snapshot и compact source summary
  `30d` по умолчанию; cleanup history не меняет active revisions.
- Latest immutable slice может оставаться stale после expiry до появления новых
  public rows. Полностью пустой active set без новых данных не создаёт empty
  slice автоматически; следующий new-data export включает только active rows.

## Affected technical surface

| Surface | Responsibility |
|---|---|
| `core/ioc-application` | `RecordValidityPolicy`, lifecycle values, use cases and inward ports |
| `core/ioc-application-tck` | Reusable lifecycle/repository contracts |
| `adapters/adapter-store-jdbc` | SQLite schema, atomic SQL, indexes, history, durable recovery |
| `adapters/adapter-sink-csv` | Active-only projection through existing projection boundary |
| `adapters/adapter-ingest` | Complete receipt duplicate fast path and ETL fallback |
| `bootstrap/ioc-app` | Typed config, startup/admission, scheduler, health, composition |
| `packaging` | Fresh preset, two-step upgrade and rollback procedure |
| affected docs | ADR, capability docs, module README, operator guide, release notes |

`core/ioc-domain` не меняется без отдельно доказанной domain necessity.

## Definition of Ready

- [x] Architecture project, ADR и этот contract прошли review и получили
  явное принятие.
- [x] I-20 contract закреплён тестами: expiry не продвигает
  `artifact_revision`, mutable projection всё равно сходится, а следующий
  new-row export исключает накопившиеся expired rows.
- [x] I-21 vocabulary/framework disposition закреплён в ADR: `valid_until`
  является durable boundary, expiry не означает `revoked`, а V1 использует
  explicit lifecycle scheduling без нового framework.
- [x] P0 characterization inventory подтверждает все current ID/read/projection/
  startup paths, которые должны измениться.
- [x] Exact configuration shape, clock rollback tolerance и reference
  performance environment задокументированы до соответствующих slices.
- [x] Для каждого P1–P6 указаны owner, affected paths, tests и rollback boundary.
- [x] Получен отдельный implementation go-ahead; принятие интервью его не
  заменяет.

## Definition of Done

- [ ] Все P0–P6 закрыты; capability не оставлена частично активируемой.
- [x] Deterministic lifecycle, SQLite race, migration/fault-injection, every-read
  and both-runtime-mode tests проходят.
- [x] 100k simultaneous-expiry scenario доказывает exact logical exclusion,
  start within `5s`, bounded drain, no starvation и projection coalescing.
- [x] Reference-environment baseline и обоснованный drain regression threshold
  сохранены в bundle evidence.
- [ ] Fresh install, two-step upgrade, one-way activation и consistent rollback
  проверены на packaged artifact и описаны оператору.
- [x] Public schemas/order и `time_first_seen`/`time_last_seen == NULL` сохранены.
- [ ] Affected published docs, generated catalogs, module README, release notes,
  status matrix и compatibility/performance ledgers актуальны.
- [x] Targeted tests, documentation checks и fresh full-reactor `make verify`
  проходят на финальном `HEAD`.

## Dependencies

Требует `R030-BASE`. Получает regression gates от `R030-TEST`, documentation
closure от `R030-DOC`, packaging/compatibility/performance closure от
`R030-REL`. Architecture boundary changes проходят существующие `R030-ARCH` и
build gates. До закрытия `R030-DATA` релиз 0.3.0 не готов.

## Current disposition

`implementation`: P0–P5 are complete. P6 has enabled the guarded fresh-install
preset and completed documentation, repository packaging contracts and a
rootless 100k lifecycle profile. The goal remains in progress until the
privileged disposable-host systemd fresh/upgrade/rollback stand is recorded;
the local release-candidate gates are complete.
