> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0024-managed-dataframe-import.md).

<a id="0024--managed-contract-driven-dataframe-import"></a>
# 0024 — Управляемый импорт dataframe по контрактам

<a id="status"></a>
## Статус

**Принято 2026-08-23. P0 contract и boundary baseline реализованы в candidate
0.3.0; runtime intake и canonical mutation остаются отключёнными и зависят
от последующих implementation gates.**

Этот ADR заменяет ADR-0015 только там, где его можно прочитать как запрет
любого CSV input. Hand-maintained CSV lookup/seed storage остаётся выведенным.
Единственное исключение — новый managed delivery workflow с versioned contract,
который распознаётся, проверяется, помещается в staging и коммитится в SQLite
truth. CSV никогда не становится вторым источником истины.

<a id="context"></a>
## Контекст

Операторы должны иметь возможность поместить CSV delivery экспортной формы
в выделенный local или SMB source и получить import с малой detection latency.
Имя файла и column order не являются стабильными identifiers. Одна delivery
может содержать compound rows и отображать logical row в несколько fields
одного artifact. Existing active rows могут требовать enrichment, replacement
или явного clearing по source-specific policy. Отсутствующие source rows
не должны иметь эффекта.

Workflow пересекает несколько существующих authorities: ingestion ownership,
configuration, artifact identity, lifecycle confirmation, canonical storage,
immutable export slots, mutable projection и remote transport. Переиспользование
существующего one-phase CSV или per-row write path допустило бы ambiguous
contracts, partial cross-artifact effects, unbounded memory или crash windows,
не поддающиеся reconciliation.

<a id="decision"></a>
## Решение

<a id="1-treat-every-file-occurrence-as-one-managed-delivery"></a>
### 1. Каждый случай поступления файла — одна managed delivery

Каждый стабильный source object принимается как отдельное occurrence с
non-reusable delivery ID и durable monotonic global sequence. Повторно
доставленные identical bytes — новое occurrence. Retry продолжает исходную
delivery, закрепляя immutable snapshot, contract ID, explicit version и
behavior fingerprint. Operator replay создаёт причинно связанную новую delivery.

Local и SMB sources имеют отдельные ownership adapters, реализующие один
source-lifecycle port. Atomic claim/rename — default ownership boundary.
WatchService и SMB change notifications — latency hints; complete listing
и ledger reconciliation остаются correctness paths.

<a id="2-compile-a-closed-declarative-contract-catalog-at-startup"></a>
### 2. Компилировать закрытый декларативный catalog contracts при startup

`ioc.dataframe-import` отделяет source trust от data shape:

- Sources объявляют transport/location, разрешённый contract set и один authority profile.
- Contracts объявляют strict charset и CSV dialect, точное structural recognition,
  column mappings, ordered registered transforms и policies.
- Authority profiles ограничивают artifacts, related routing, formula preservation
  и destructive merge behavior.

Framework-free catalog compiler разрешает все references, сообщает semantic
errors collect-all и выдаёт deterministic SHA-256 behavior fingerprints.
Catalog либо полностью исполним, либо отсутствует; partially resolved catalog
не публикуется. Unknown `ioc.*` keys по-прежнему отклоняются существующим
strict configuration preflight. Import отключён по умолчанию.

<a id="3-preserve-tri-state-cells-and-patch-only-present-logical-rows"></a>
### 3. Сохранять tri-state cells и менять только присутствующие logical rows

Mapped cells до merge сохраняют `ABSENT`, explicit `NULL` или concrete `VALUE`.
`ABSENT` никогда не меняет field. Authoritative `NULL` может очистить existing
nullable public field. Другие declared merge policies могут preserve, fill,
replace non-null values или reject conflicts.

В matching участвуют только active records на transaction `asOf`. Historical
records игнорируются. Изменённое identifying value — новое observation,
а не in-place identity rewrite. Rows, отсутствующие в delivery, не удаляются,
не меняются, не продлеваются и никак иначе не наблюдаются.

Contracts по умолчанию используют processing `as-is` и routing `target-only`.
Processed и related-artifact modes явны. Compound fields одной artifact row
остаются одной logical row; cross-artifact relationship graph не вводится.

<a id="4-separate-canonical-row-identity-from-active-match-alternatives"></a>
### 4. Отделить canonical row identity от active match alternatives

Artifact identity получает именованные versionable match-key definitions.
Contract ссылается на эти names вместо встраивания SQL или Java behavior.
Match keys — альтернативные active-record lookup paths, сами по себе не
меняющие canonical row-key formula. Любая physical identity/alias migration
требует collision preflight и сохранения existing canonical/lifecycle IDs.

Ноль matches означает insert, ровно один — policy-controlled patch,
несколько — conflict. V1 не пытается автоматически разрешать conflict.
Поведение дубликатов внутри delivery объявлено и детерминированно.

<a id="5-stage-off-heap-and-promote-atomically-across-artifacts"></a>
### 5. Staging вне heap и атомарная promotion через artifacts

Parsing и mapping потоково пишут в защищённый per-delivery staging store.
Sealed stage — immutable input одной dataframe-owned canonical promotion
transaction. Она охватывает все accepted artifact branches вместе с
lifecycle/provenance, match aliases, export-slot effects, artifact revisions
и import receipt. Branches logical row и accepted set всей delivery на этой
границе применяются целиком или никак.

Service DB владеет delivery coordination; dataframe DB — canonical commit
authority и его idempotency receipt. Recovery разрешает cross-database saga
по durable evidence. Оно никогда не пытается использовать cross-database transaction.

<a id="6-extend-lifecycle-and-export-slot-behavior-without-changing-their-authority"></a>
### 6. Расширить lifecycle и export-slot behavior без смены authority

Accepted observations используют lifecycle boundary ADR-0020. Настроенная
unchanged-row policy решает, продлевает ли validity точный no-op. Публичные
time-like fields можно импортировать как обычные public values, но они никогда
не становятся lifecycle authority. Missing rows не влияют на lifecycle.

Для artifacts с external IDs положительный imported ID — запрошенный export
slot ADR-0021 в объявленном profile, а не canonical identity. Свободный request
предпочитается; занятый приводит к выбору наименьшего available slot.
Existing survivors сохраняют assigned slot, если declared mismatch policy
не отклоняет row. Allocation обязана использовать sparse free ranges,
а не работу пропорционально наибольшему requested value. Artifacts без
external ID, включая `address_blacklist`, отклоняют slot mapping.

Public insert/update/clear mutations продвигают affected artifact revision
один раз на delivery. TTL-only renewal и true no-op — нет. Revision-significant
immutable delivery ADR-0022 и bounded reconciliation ADR-0023 остаются authority.

<a id="7-keep-framework-and-integration-mechanisms-at-adapter-boundaries"></a>
### 7. Сохранить framework и integration mechanisms на границах адаптеров

Contract, policies, use-case ports и orchestration остаются в `ioc-application`.
Spring configuration принадлежит `ioc-app`; Commons CSV — CSV adapter;
SQLite/JDBC — storage adapter; local file watching — ingest adapter;
SMB operations — SMB transport adapter. Решение не вводит новый Maven module
или broker.

Control events могут подталкивать detection, drain, projection, lifecycle
scheduling или publish. Они не несут row data и никогда не являются ordering,
idempotency или recovery authority. Delivery ledger и periodic reconcile
остаются авторитетными.

<a id="consequences"></a>
## Последствия

Положительные последствия:

- Операторы могут объявлять несколько доверенных dataframe shapes без зависимости
  от filenames или column order.
- Authority ceilings не дают contract молча повысить mutation rights.
- Bounded staging и set-oriented promotion могут поддерживать большие deliveries
  без heap или transaction work на каждую source row.
- Active-only patch semantics сохраняет не связанные canonical data.
- Dataframe receipt закрывает crash window между commit и service update.
- Local и SMB intake разделяют business behavior, не разделяя transport code.

Стоимость и ограничения:

- Runtime требует новых service-ledger, staging, alias и receipt schemas
  в последующих slices.
- Atomic cross-artifact promotion увеличивает размер и значимость одной
  SQLite writer transaction.
- Conflicting active matches отклоняются, а не исправляются автоматически.
- Source ownership guarantees зависят от filesystem/server capabilities
  и требуют adapter contract qualification.
- P0 публикует только отключённую configuration и framework-free contracts;
  файлы не принимает и не изменяет.

<a id="rejected-alternatives"></a>
## Отклонённые альтернативы

- **CSV как lookup или seed truth:** восстанавливает dual-authority model,
  выведенную ADR-0015.
- **Идентифицировать contracts по filename:** не выдерживает operator naming
  или изменений upstream export.
- **Связать contracts напрямую с Commons CSV или Spring types:** протаскивает
  adapter family в application core.
- **Обновлять каждую row/artifact независимо:** допускает partial delivery
  effects и per-row transaction overhead.
- **Загрузить всю delivery в heap:** делает память пропорциональной operator input size.
- **Сопоставлять historical rows:** нарушает active lifecycle boundary и
  может воскресить stale identity.
- **Считать файл replacement snapshot:** удаляло бы или продлевало records,
  отсутствующие в delivery, вопреки patch/upsert contract.
- **Content digest как delivery identity:** подавляет законную identical
  re-delivery и lifecycle confirmation.
- **Координация через in-process event bus или external broker:** ни один
  не предоставляет требуемые SQLite commit evidence или ordering authority.

<a id="verification"></a>
## Проверка

Реализация принимается по slices. Требуемые evidence включают:

- Strict unknown-key и collect-all semantic configuration failures.
- Deterministic catalog fingerprints и полные registry/reference checks.
- Исчерпывающие tri-state merge и authority-ceiling tests.
- ArchUnit и Maven guards, удерживающие Spring, Commons CSV, JDBC и SMB
  вне application core.
- Reusable delivery-ledger и atomic-writer TCKs.
- Migration, active-match, slot-range, failure-injection и crash-recovery
  integration suites.
- Local и real-server SMB ownership contract corpora.
- Bounded-resource evidence для 100,000 и 1,000,000 rows.
- Packaged upgrade/rollback и fresh full-reactor release gate.
