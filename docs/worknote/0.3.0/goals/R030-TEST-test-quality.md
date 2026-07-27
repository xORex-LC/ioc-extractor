---
title: "R030-TEST — Test quality"
version: "0.3.0"
goal_id: "R030-TEST"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-TEST — Test effectiveness и coverage

## Outcome

Критичное supported behavior защищено meaningful tests. Line/branch coverage
измеряется по модулям и для reactor aggregate, regressions блокируются
risk-based thresholds, flaky tests имеют owner и disposition. Coverage
воспроизводим локально и является обязательной частью Maven `verify`, а не
только внешним CI signal.

Execution mode: **global instrumentation + per-module hardening**.

## Scope 0.3.0

### Обязательные release deliverables

В релиз MUST входят:

- JaCoCo instrumentation для unit и integration executions;
- per-module и aggregate HTML/XML reports;
- локальные coverage checks, привязанные к Maven `verify`;
- best-effort загрузка JaCoCo XML в Codecov из protected CI;
- сигнальные Codecov project/base-relative/patch reports без required status;
- разделение unit и integration lifecycle через Surefire/Failsafe;
- минимальная контролируемая JUnit tag taxonomy;
- локальные команды через Maven Wrapper/Makefile для targeted, fast,
  integration и full verification;
- inventory и классификация существующих tests;
- risk-based закрытие negative, boundary, error, recovery, concurrency и
  compatibility gaps;
- bounded-wait/timeout policy и disposition существующих sleep/flaky findings;
- test-duration evidence и список наиболее медленных tests;
- published English `docs/TESTING.md` с правилами, taxonomy, командами,
  coverage и flake policy.

### Ограниченные diagnostic pilots

В scope входят, но не получают обязательного числового release threshold:

- PIT mutation-testing pilot только для `core/ioc-domain`;
- воспроизводимый Maven profile/command и сохраняемый PIT report;
- scheduled random-order/repeat stability run с воспроизводимым seed;
- invariant-oriented tests для refang, normalization, classification,
  deduplication и identity на существующем JUnit stack;
- оценка общих fixtures/builders только там, где review подтвердил test-code
  duplication или readability problem.

Pilot завершается воспроизводимым report, оценкой signal/noise/cost и решением
`Adopt`, `Extend`, `Defer` или `Reject`. Неуспешный pilot не превращается
автоматически в repository-wide remediation.

### Вне scope 0.3.0

По умолчанию не входят:

- repository-wide mutation threshold или обязательный PIT в каждом PR;
- новый property-based testing framework и массовая генерация inputs;
- fuzzing, chaos, soak и load-test platforms;
- Testcontainers без реальной integration family, требующей внешнего runtime;
- RabbitMQ tests до появления RabbitMQ adapter;
- постоянная лаборатория с реальным SMB server;
- cross-platform test matrix, не обоснованная поддерживаемым deployment
  contract;
- массовая перепись существующих tests ради единого naming/AAA style;
- введение Mockito или другого mocking framework без конкретной потребности;
- стремление к 100% coverage;
- автоматические retries, скрывающие flaky failure.

Найденный за этими границами риск получает evidence и disposition. Он входит в
релиз только по отдельному scope decision либо если блокирует обязательный
supported contract.

## Test taxonomy

Проект различает:

- unit tests;
- integration tests;
- architecture tests;
- contract/TCK tests;
- publication/consumer tests;
- end-to-end/golden tests.

Unit tests быстрые, deterministic и ориентированы на behavior. Integration
tests проверяют DB, filesystem, parsing, serialization, transport и
transactions. E2E покрывает ограниченное число critical workflows и не заменяет
узкие tests.

RabbitMQ integration tests становятся обязательными только при создании
RabbitMQ adapter и не вводят broker runtime в IOC Extractor.

## Maven lifecycle и JUnit tags

Lifecycle и классификация разделяются:

- Surefire запускает быстрые `*Test`;
- Failsafe запускает настоящие integration tests с именами `IT*`, `*IT` или
  `*ITCase` на фазах `integration-test`/`verify`;
- полный `verify` запускает все deterministic offline tests;
- environment-dependent `external` tests запускаются только на provisioned
  environment и получают явный release disposition.

Минимальный разрешённый tag vocabulary:

- `integration`;
- `contract`;
- `architecture`;
- `e2e`;
- `slow`;
- `external`.

Обычный unit/component `*Test` MAY оставаться без `unit` tag: отсутствие
аннотации не должно незаметно исключать его из fast suite. Для повторяющихся
class-level semantics SHOULD использоваться composed annotations, например
`@IntegrationTest` или `@ContractTest`.

Feature, module, author и временные issue tags запрещены без изменения
taxonomy. Convention check MUST обнаруживать неизвестные tags и
integration/E2E tests, не попадающие в ожидаемый lifecycle.

Tags являются удобным selection mechanism для developer и scheduled runs, но
не заменяют полный release gate.

## Risk areas

Приоритет имеют:

- validators и strict configuration;
- IOC extraction/classification/normalization;
- retry, timeout и recovery;
- event routing и idempotency;
- transactional/partial-failure behavior;
- concurrency;
- resource lifecycle;
- serialization/wire/schema compatibility;
- public IDs, row identity и deduplication;
- upgrade/rollback;
- error handling и fallback.

Каждый исправленный defect получает regression test.

## Coverage policy

Coverage является diagnostic/ratchet tool, а не оценкой качества.

JaCoCo является локальным и CI-authoritative measurement/gate. Codecov
принимает JaCoCo XML, хранит history, показывает trend и формирует PR
annotations, но не заменяет локальный Maven check и никогда не является
required branch-protection status.

### Release thresholds

| Scope | Line coverage | Branch coverage | Gate |
|---|---:|---:|---|
| Reactor production aggregate | `>= 75%` | `>= 80%` | JaCoCo `verify` |
| `core/ioc-domain` | `>= 85%` | `>= 90%` | JaCoCo `verify` |
| `core/ioc-application` | `>= 85%` | `>= 90%` | JaCoCo `verify` |
| Changed lines in PR | target `>= 90%` | meaningful outcomes MUST be tested | advisory Codecov + required review |
| Каждый production module | не ниже accepted baseline | не ниже accepted baseline | per-module ratchet |

`core/ioc-application-tck` является test-support/publication artifact и
оценивается через выполнение его contracts consumers, а не через обычный
production-module percentage.

Repository-wide branch coverage `90%` является целевым направлением после
0.3.0, но не release blocker этого релиза. Если baseline любого scope уже выше
указанного минимума, baseline становится threshold: введение общего floor не
разрешает снизить существующее качество.

Для decision-heavy core branch coverage является основным risk-oriented
threshold. Line coverage остаётся независимым минимальным hygiene floor и может
отдельно выявлять большие неисполняемые straight-line regions.

Codecov SHOULD показывать следующие сигнальные targets:

- absolute project floor `75%`;
- base-relative project ratchet с максимальным допуском `0.1%`;
- changed-lines patch target `90%` без допуска;
- наличие head report и связь результата с соответствующим CI build.

Эти targets не являются Maven/release gates. Для changed lines числовой target
является advisory, а обязательным контрактом остаётся review meaningful
positive/negative/boundary outcomes. Project-owned local diff-coverage gate не
входит в 0.3.0 и может оцениваться отдельно после стабилизации основного JaCoCo
контура.

Branch-heavy changed logic MUST иметь explicit positive, negative и boundary
tests независимо от aggregate percentage. Error/exception paths проверяются
отдельно: JaCoCo branch counter не является доказательством их покрытия.

### Rollout

Порядок:

1. подключить JaCoCo в report-only mode;
2. измерить line/branch baseline;
3. подтвердить coverage universe и justified exclusions;
4. включить per-module no-regression ratchets;
5. устранить gaps до release floors;
6. включить fixed JaCoCo checks в `verify`;
7. стабилизировать best-effort Codecov upload и сигнальные reports;
8. подтвердить, что Codecov statuses не входят в required branch protection;
9. пересматривать exclusions и thresholds.

Если baseline ниже release floor, report/ratchet сначала запрещает дальнейшее
ухудшение, но release candidate блокируется до достижения floor. Coverage drop
не скрывается снижением threshold, округлением, broad exclusion или удалением
tests.

### Coverage universe и exclusions

Aggregate MUST учитывать production bytecode из `platform/*`, `core/ioc-domain`,
`core/ioc-application`, `adapters/*` и `bootstrap/ioc-app`, включая code,
исполняемый downstream integration/E2E tests.

Exclusion допускается только для доказанного generated/test-support bytecode
или технического artifact, для которого показатель не отражает production
risk. Каждый exclusion фиксирует selector, rationale, owner, review condition и
влияние на denominator. Package-wide exclusion ради достижения threshold
запрещён.

Модули с малым denominator оцениваются одновременно по ratio и absolute missed
branches/instructions: один процент не должен давать ложную уверенность или
создавать случайный blocker.

## Codecov и CI evidence

Coverage generation является частью существующего reactor build, а не
дублирующим test workflow:

1. `tools/ci/build.sh` выполняет Maven `verify`;
2. JaCoCo checks блокируют build локально;
3. CI проверяет наличие ожидаемых XML reports;
4. pinned Codecov uploader передаёт только явно указанные reports;
5. HTML/XML reports и test results сохраняются как CI artifacts;
6. Codecov project/patch results используются как review signal.

Отсутствие project-owned JaCoCo XML или failed Maven coverage check является CI
failure. Codecov outage, upload error, rate limit или отсутствующий status
фиксируются как внешний reporting failure, но не блокируют PR/release и не
могут превратить failed local JaCoCo check в success.

## Characterization и structural changes

До risky refactoring фиксируется supported behavior. Tests не должны
цементировать private implementation без необходимости.

Package relocation переносит related tests вместе с production code. После
structural work проверяется, что tests продолжают защищать contract, а не старую
package shape.

## Retirement verification

`R030-RETIRE` подтверждается подходящим negative/absence evidence:

- dependency tree;
- context/startup test;
- rendered CLI/help/config;
- upgrade test;
- external consumer contract.

Test, единственный защищающий соседний supported behavior, не удаляется вместе
с obsolete surface. Test исключительно retired contract удаляется или
переписывается.

## Publication consumer

Standalone consumer:

- находится вне основного reactor resolution;
- использует опубликованные coordinates/repository;
- компилирует минимальный real usage;
- выполняет contract test;
- не использует source checkout, `relativePath` или local `mvn install`.

## Flake policy

Timing/concurrency/recovery tests используют bounded waits и не скрывают
необъяснённые retries. Quarantine допускается временно с owner, reason и exit
condition.

`Thread.sleep` не запрещён синтаксически, но каждый такой wait MUST быть
обоснован невозможностью наблюдать condition/latch/future. Long fixed sleeps
заменяются deterministic coordination или bounded polling.

Async, concurrency, filesystem и remote-transport tests MUST иметь
диагностируемую верхнюю границу выполнения. Retry разрешён только как временный
flake mitigation с сохранением failed-attempt evidence; новый flaky test не
принимается под retry по умолчанию.

Scheduled stability run:

- использует random order или selective repetition;
- публикует воспроизводимый seed;
- сначала является report-only;
- создаёт finding при failure;
- не смешивает failure с функциональным PR gate без triage.

## Performance и resources

Baseline MAY включать:

- build/test time;
- representative processing time;
- daemon startup;
- heap/thread/file-descriptor behavior;
- DB/output size.

Regression получает investigation и disposition; optimization выполняется
после measurement.

Build/test duration не является coverage threshold, но MUST сохраняться для
обнаружения резкого роста стоимости full verification. Самые медленные tests
получают classification, а не автоматически переносятся из обязательного gate.

## Test design rules

- Проверяется observable behavior и contract, а не private implementation.
- Каждый defect fix получает regression test.
- Decision-heavy logic покрывает happy, negative, boundary и failure cases.
- Parameterized/invariant-oriented tests предпочтительнее копирования похожих
  methods.
- Время, randomness, executors и environment контролируются явно.
- Ports проверяются reusable contract/TCK там, где существует несколько
  implementations.
- Реальная external integration используется только там, где fake не может
  проверить protocol/transaction/runtime semantics.
- Test fixture/helper не становится catch-all test commons.
- Mocking framework не вводится, если небольшой fake/stub выражает contract
  яснее.

Для supported SQLite migrations, run-ledger recovery и partial-commit windows
MUST существовать upgrade/recovery/fault-path evidence. Новые tests создаются
по найденному риску; требование не означает комбинаторную перепись всех
исторических сценариев.

## Mutation pilot

PIT pilot ограничен `core/ioc-domain`:

- не блокирует обычный PR и release числовым mutation score;
- использует существующий unit-test set;
- запускается отдельным Maven profile/явной локальной командой;
- публикует HTML/XML report как CI artifact при scheduled/manual run;
- фиксирует survived mutants по critical rules и связывает actionable случаи с
  test work items;
- классифицирует survived mutants как `test-gap`, `equivalent/noise`,
  `non-critical` или `investigate`;
- измеряет runtime cost;
- завершает adoption decision для следующего release: `Adopt`, `Extend`,
  `Defer` или `Reject`.

Первый pilot выполняется единым scope. Разделение по packages/capabilities
вводится только если измеренная стоимость или диагностируемость полного запуска
это оправдывают. Package profiles при этом должны быть непересекающимися и
вместе покрывать принятый mutation universe.

Полный PIT MAY выполняться вручную и по расписанию. Differential mutation
analysis только изменённого PR-кода является кандидатом следующего этапа после
стабилизации baseline и проверки используемого plugin/licensing model; он не
является обязательным deliverable 0.3.0.

Исправление очевидного critical survived mutant MAY войти в текущий
module-hardening work item. Массовое повышение mutation score, repository-wide
PIT и постоянный PIT job на каждый PR в scope не входят.

## Procedure per scope

1. Определить critical behavior и existing tests.
2. Классифицировать lifecycle/tags и снять line/branch report.
3. Найти negative/boundary/error/flake gaps.
4. Добавить characterization/regression tests до risky implementation.
5. Выполнить work item.
6. Повторить targeted tests, module report и full reactor gate.
7. Установить/поднять ratchet после evidence.
8. Обновить matrix и [test-quality ledger](../evidence/test-quality-ledger.md).

## Definition of Ready

- scope и behavior risk определены;
- current tests/coverage известны;
- test level выбран;
- применимые lifecycle, tags и threshold известны;
- flaky/time/resource constraints учтены;
- threshold change основан на baseline.

## Definition of Done

- per-module и aggregate reports воспроизводимы;
- JaCoCo checks проходят локально через Maven `verify`;
- aggregate/core/per-module blocking policies соблюдены;
- Codecov configuration и non-required policy проверены; доступный run имеет
  evidence, а внешняя недоступность — явную non-blocking disposition;
- changed-code target используется при наличии Codecov signal, а meaningful
  changed behavior независимо подтверждено review/tests;
- Surefire/Failsafe lifecycle и tag taxonomy документированы и проверяются;
- critical changed paths имеют meaningful tests;
- flaky findings имеют disposition;
- exclusions узкие и документированы;
- publication consumer test проходит;
- PIT/domain имеет воспроизводимый command, сохранённый report, triage
  survived mutants, runtime evidence и adoption disposition;
- scheduled-stability pilot имеет adoption disposition;
- `docs/TESTING.md` опубликован и соответствует build commands;
- performance regressions имеют disposition.

## Dependencies

Требует `R030-BASE` и build instrumentation из `R030-BUILD`; поддерживает все
module-level goals и `R030-REL`.
