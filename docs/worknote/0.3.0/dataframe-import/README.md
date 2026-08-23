---
title: "DATA-IMPORT-01 — рабочий комплект"
version: "0.3.0"
status: "Architecture approved; P0-P3 implemented, P4 authorized"
document_type: "Worknote bundle index"
source_of_truth: false
language: "ru"
---

# DATA-IMPORT-01 — рабочий комплект

Этот каталог изолирует интервью, решения и проектирование срочного механизма
импорта dataframe-артефактов от принятого плана релиза 0.3.0. Материалы здесь
являются изменяемым рабочим контекстом и не заменяют ADR, capability docs,
operator guides или release contract.

`DATA-IMPORT-01` — предварительный ID work item. Его принадлежность к
существующему `R030-DATA` либо отдельной release goal будет определена только
после discovery и formal scope review.

## Текущий статус

- P0 contract/boundary baseline, P1 integration-family preparation, P2
  canonical identity/mutation foundation и P3 sparse preferred export slots
  реализованы 2026-08-23..2026-08-24;
  исполняющая delivery infrastructure начинается с P4, local intake — с P5,
  а canonical promotion — с P6;
- discovery interview завершено 2026-08-23: все I-01..I-41 имеют статус
  `DECIDED`;
- архитектурный проект, release contract, data/persistence/operations contracts,
  implementation plan и verification matrix подготовлены 2026-08-23;
- проектные решения и implementation go-ahead для последовательных P0-P4
  одобрены 2026-08-23..2026-08-24;
- исходные требования BR-01..BR-04 зафиксированы в
  [discovery.md](discovery.md);
- I-01 закрыт: каждый стабилизированный CSV является отдельной delivery;
- I-02 закрыт: файл распознаётся как один source contract, но configured routing
  policy может направить его observations только в primary artifact либо также
  в совместимые смежные artifacts;
- I-03 закрыт: merge работает только с active lifecycle, безопасный default
  заполняет missing values, а declarative authoritative policy может заменить
  либо явно очистить public field;
- I-04 закрыт: каждая delivery является patch/upsert только присутствующих
  rows; отсутствие row не удаляет, не изменяет и не подтверждает active record;
- I-05 закрыт: другое canonical identity-bearing value всегда является новыми
  данными; import не связывает его с прежней record по source `id`;
- I-06 закрыт: повторное помещение byte-identical CSV после terminal boundary
  является новой delivery; TTL renewal для unchanged rows конфигурируется;
- I-07 закрыт: выделенные local и SMB import inbox являются consumer-managed;
  общий lifecycle переиспользует transport-specific claim/archive/quarantine;
- I-08 закрыт: critical file errors отклоняют delivery целиком, а default
  `accept-valid` изолирует ошибочные rows; strict behavior конфигурируется;
- I-09 закрыт: одна accepted/rejected input row атомарна относительно всех
  branches её deterministic multi-artifact fan-out;
- I-10 закрыт: accepted write set всей delivery commit-ится одной ACID
  transaction во всех затронутых dataframe artifacts;
- I-11 закрыт: `as-is` валидирует final canonical row и не выполняет
  неявных transforms; normalization объявляется source mapping явно;
- I-12 закрыт: `as-is` строит row из imported values, а `processed` отдаёт
  ownership derived fields действующему pipeline;
- I-13 закрыт: processing mode выбирает incoming values, а отдельная merge
  policy определяет overwrite active DB; default остаётся `fill-missing`;
- I-14 закрыт: public `time_first_seen`/`time_last_seen` импортируются как
  обычные mapped business fields без изменения lifecycle/TTL behavior;
- I-15 закрыт: source contract выбирает `coalesce` либо `keep-first`, default —
  deterministic `coalesce`; order-dependent sequential updates запрещены;
- I-16 закрыт: импортируемый requested export slot действует только внутри
  явно указанной пары `(profile, primary-artifact)`; `address_blacklist`
  остаётся без external ID;
- I-17 закрыт: свободный requested slot сохраняется точно, а при занятом slot
  запись использует действующий smallest-free-positive allocator; отдельный
  nearest-search не вводится;
- I-18 закрыт: разные rows с одним requested slot отклоняются общей conflict
  group; остальные valid rows продолжаются при `accept-valid`;
- I-19 закрыт: принимается только exact-one source-contract match; zero и
  ambiguous match являются critical file errors без priority/scoring fallback;
- I-20 закрыт: retry/recovery продолжает delivery по первоначально принятым
  правилам; новая конфигурация применяется к тем же bytes только как новая
  delivery;
- I-21 закрыт: отсутствующая/unmapped колонка означает `ABSENT`, а empty cell и
  configured null literal по умолчанию означают `NULL`; очистка всё равно
  контролируется merge policy;
- I-22 закрыт: source contract декларативно задаёт dialect/charset с
  export-compatible defaults; unrestricted auto-detection и silent byte
  replacement не используются;
- I-23 закрыт: import deliveries применяются строго по durable claim sequence;
  retry удерживает своё место до terminal outcome;
- I-24 закрыт: import не получает implicit priority; replace/clear authority
  задаётся только source merge policy;
- I-25 закрыт: manual replay создаёт новую полную delivery с causal link;
  terminal outcome прежней occurrence не переоткрывается;
- I-26 закрыт: три terminal outcomes имеют protected original+report unit;
  per-row report использует row numbers/codes без raw IOC payload;
- I-27 закрыт: defaults 30d/90d используют существующий общий retention
  contract `max-age|max-count|delete|archive`; отдельная модель не вводится;
- I-28 закрыт: большие CSV обрабатываются streaming через per-delivery
  disk-backed staging и atomic promotion; backpressure является normal capacity
  control, hard limits — последняя safety boundary;
- I-29 закрыт: import contract catalog валидируется/активируется только при
  restart через существующий config apply/health/rollback workflow;
- I-30 закрыт: несколько IOC-bearing columns одного list образуют одну compound
  artifact record и остаются одной canonical/export row; cross-list relation и
  отдельный correlation storage/export не требуются;
- I-31 закрыт: declarative match keys задаются per list/source contract;
  zero/exact-one matches создают либо обновляют record, а multi-record match
  отклоняется без автоматического склеивания;
- I-32 закрыт: конфликт stable identifying values отклоняет logical row без
  автоматического/manual resolution в v1; declared mutable fields продолжают
  использовать обычную merge policy;
- I-33 закрыт: one-to-many данные представлены повторяющимися scalar rows с
  declarative composite identity; неявные collections внутри cell запрещены;
- I-34 закрыт: write access к source/inbox даёт authority только внутри его
  contract allowlist/ceiling; разные trust levels разделяются sources и
  credentials, per-file signatures не входят в v1;
- I-35 закрыт: spreadsheet-dangerous free text по умолчанию отклоняется без
  silent mutation; exact preservation требует explicit machine-only policy;
- I-36 закрыт: public mutation продвигает revision batch-scoped на delivery и
  использует существующий post-commit quiet/max-cap export scheduler без
  per-row slices; no-op/TTL-only export не запускают;
- I-37 закрыт: order незначим, renames требуют aliases, ignored columns —
  allowlist; unexpected/duplicate/ambiguous headers отклоняют file;
- I-38 закрыт: advisory read-only validate/plan входит в v1 без approval,
  reservation или гарантии результата; real import revalidate-ит live state;
- I-39 закрыт: aggregate health/status показывает ordered backlog и recovery,
  но v1 не разрешает manual queue/ledger mutation; terminal retry — новая
  replay delivery;
- I-40 закрыт: import применяет только private immutable hashed snapshot после
  доказанного local/SMB claim; невозможность ownership/consistency оставляет
  delivery retryable/degraded без canonical write;
- итоговая round-trip сверка I-01..I-40 выявила одну последнюю product-развилку
  I-41: requested source slot против stable slot уже совпавшей active record;
- I-41 закрыт: default `preserve-existing` сохраняет survivor slot и отдельно
  применяет business-field merge с явным mismatch report; source contract может
  выбрать strict `reject-mismatch`, автоматическая renumber policy запрещена;
- известных незакрытых business choices не осталось; formal scope, architecture
  project и P0-P3 foundation завершены, следующий авторизованный implementation
  slice — P4;
- принятый ADR-0015 не редактируется: если новый import contract будет принят,
  потребуется отдельный superseding ADR.

## Навигация

| Документ | Назначение |
|---|---|
| [discovery.md](discovery.md) | Журнал интервью: требования, варианты, риски, рекомендации и подтверждённые ответы |
| [release-contract.md](release-contract.md) | Formal scope, обязательные invariants, DoR/DoD и compatibility boundary |
| [architecture-project.md](architecture-project.md) | Target component/module/transaction/event architecture и approval gates |
| [data-contract.md](data-contract.md) | Declarative source contract, recognition, tri-state merge, matching и routing |
| [persistence-and-recovery.md](persistence-and-recovery.md) | Service/dataframe schema, disk staging, promotion transaction и crash recovery |
| [operations-and-security.md](operations-and-security.md) | Local/SMB claim, backpressure, health, observability и threat controls |
| [implementation-plan.md](implementation-plan.md) | Implementation slices P0–P9, dependencies, gates и stop conditions |
| [verification-matrix.md](verification-matrix.md) | Traceability I-01..I-41, tests, qualification и performance evidence |
| [p0-evidence.md](p0-evidence.md) | Проверяемые границы, команды и результат реализации P0 |
| [p1-evidence.md](p1-evidence.md) | Preparatory refactors, focused gates and compatibility evidence for P1 |
| [p2-evidence.md](p2-evidence.md) | Versioned identity, alias migration, mutation-kernel and focused gate evidence for P2 |
| [p3-evidence.md](p3-evidence.md) | Coalesced sparse-slot registry, preferred-slot policy and focused gate evidence for P3 |

## Правила работы

1. После каждого вопроса его контекст, варианты и текущая рекомендация
   фиксируются в `discovery.md`.
2. Ответ заказчика дописывается к вопросу как принятое решение или как новая
   развилка; предыдущая аргументация не удаляется.
3. Технический механизм выбирается после выяснения business outcome, а не
   подменяет его.
4. `CHANGE_NOTIFY`, polling и local filesystem events рассматриваются как
   latency/correctness-механика после определения business delivery boundary.
5. P0-P4 выполняются по выданному implementation go-ahead; переход к каждому
   следующему slice допускается только после полного закрытия предыдущего.

## Граница authority

- этот bundle хранит незавершённый проектный диалог;
- принятые долгоживущие решения публикуются новым ADR;
- фактическая механика после реализации переносится в `docs/dev/*`, module
  README и operator guide;
- изменение release scope отдельно регистрируется в release worknote и
  `status-matrix.md`.
