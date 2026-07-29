---
title: "R030-BUILD — Build quality"
version: "0.3.0"
goal_id: "R030-BUILD"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-BUILD — Build quality и CI signal

## Outcome

Compiler, static, dependency и architecture checks дают reproducible,
actionable signal. Legacy findings вводятся через baseline/ratchet, а не
массовое подавление или бесконтрольную rewrite.

Execution mode: **global toolchain + per-module ratchet**.

## Existing controls

Следующие controls уже существуют и MUST быть сохранены:

- Maven Wrapper;
- Maven Enforcer;
- ArchUnit;
- multi-module reactor CI;
- documentation link checks;
- dependency security workflow;
- release workflow.

JDK 21 обязателен. Maven plugin versions управляются централизованно.

## Accepted 0.3.0 controls

### Test controls

Следующие controls уже приняты в scope и не проходят повторный выбор как
необязательные candidates:

- JaCoCo agent, per-module reports/checks и reactor aggregate report/check;
- Maven Surefire для быстрых `*Test`;
- Maven Failsafe для integration `*IT`;
- best-effort Codecov upload из существующего CI build;
- сигнальные Codecov project/base-relative/patch reports;
- test-result, duration и coverage artifacts;
- JUnit tag vocabulary/convention check;
- scheduled report-only stability run.

Tool versions и точная Maven configuration определяются implementation review,
но outcome и gates заданы в
[R030-TEST](R030-TEST-test-quality.md). Coverage correctness MUST оставаться частью
Maven `verify`; Codecov не становится заменой локального gate и не получает
required branch-protection status.

### Code-quality analysis

В 0.3.0 принимаются два разных control с разной enforcement policy:

- SpotBugs — постоянный reactor-wide bytecode check;
- PMD CPD — постоянный repository-wide duplicate-code detector, в этом релизе
  работающий как diagnostic control.

Оба инструмента сначала вводятся в report-only mode. Tool-introduction change
не смешивается с массовым исправлением legacy findings. Для каждого control
фиксируются version, scope, runtime cost, output artifacts, configuration owner
и suppression/exclusion policy.

### SpotBugs operating contract

SpotBugs MUST:

- анализировать production bytecode всех применимых Java-модулей reactor;
- иметь pinned plugin/tool versions и общую конфигурацию в parent build;
- вызываться обычным Maven `verify`, а не отдельной несвязанной CI-командой;
- формировать machine-readable XML и удобный для человека HTML report;
- после baseline/triage блокировать новые findings принятой confidence/severity;
- падать при analyzer error или отсутствии ожидаемого report после
  stabilization.

Test bytecode MAY анализироваться отдельным execution, если пилот подтвердит
actionable signal и приемлемую стоимость. Отсутствие test-bytecode enforcement
не исключает production-модуль из reactor-wide scope.

До включения blocking check выполняются:

1. воспроизводимый полный report;
2. triage findings и false positives;
3. исправление immediate correctness/resource/concurrency risks;
4. фиксация принятого legacy baseline узкими filters;
5. повторный clean run, подтверждающий deterministic signal;
6. включение `spotbugs:check` в `verify` и required CI build.

Массовое устранение всего legacy baseline не является условием подключения
SpotBugs. Однако каждый оставленный finding MUST иметь точный selector,
rationale, owner и review/exit condition. Global package suppression и
blanket-ignore запрещены, если возможно описать исключение точнее.

### PMD CPD operating contract

PMD в 0.3.0 используется именно как CPD. Полный PMD ruleset не принимается
неявно вместе с duplicate analysis.

`BUILD-CPD-02` не запускает goals `pmd`, `check` или `aggregate-pmd` и не
принимает правила unused code, complexity, object creation/performance или
error-prone categories. По умолчанию отдельное обсуждение и evidence-based
evaluation полного PMD ruleset возвращается в очередь после
`BUILD-SPOTBUGS-04`, когда SpotBugs findings уже семантически разобраны и можно
оценить дополнительный, а не дублирующий signal. Раньше этот scope открывается
только по конкретному risk/gap evidence и явному изменению status matrix.

CPD MUST:

- анализировать production Java sources всех применимых Maven-модулей единым
  repository-wide report, чтобы видеть межмодульные совпадения;
- исключать generated/vendor sources по явным selectors;
- формировать сохраняемый CI report;
- использовать `minimumTokens`, выбранный после baseline на реальном
  репозитории, а не скопированный из стороннего проекта;
- передавать существенные findings в
  [R030-QUAL](R030-QUAL-code-health.md) для semantic triage.

В 0.3.0 CPD остаётся diagnostic/report-only control: сам факт token match не
блокирует merge или release. Каждый существенный finding получает
`deduplicate`, `retain` или `defer` на основании shared knowledge, semantics,
ownership и dependency direction. Нулевое дублирование, сырой процент или
совпадение tokens сами по себе не являются release требованием и не разрешают
shared abstraction.

После подтверждения deterministic report и устойчивого baseline MAY быть
принят отдельный no-new-duplication ratchet. Такой ratchet блокирует только
новое необъяснённое дублирование и не делает исторический backlog обязательным
для одного PR. Blocking CPD ratchet не является обязательным deliverable
0.3.0.

### Dependency hygiene evaluation

Maven Dependency Plugin `dependency:analyze-only` MUST пройти bounded
report-mode evaluation для `used-but-undeclared`, `declared-but-unused` и
ошибочного scope. Его постоянное включение и `failOnWarning` не являются
обязательными deliverables 0.3.0.

Evaluation завершается решением `Adopt`, `Adopt with exclusions` или `Defer`.
Bytecode-анализ не считается доказательством неиспользования dependency:
Spring, reflection, ServiceLoader, resources и plugin loading проверяются
отдельно. При adoption `used-but-undeclared` и `declared-but-unused` MAY иметь
разные enforcement policies.

Изменяемые отчёты и adoption decisions ведутся в
[build-quality ledger](../evidence/build-quality-ledger.md).

## Deferred controls

Следующие инструменты и контуры не входят в реализацию 0.3.0:

- Spotless и Checkstyle;
- japicmp и Revapi;
- Error Prone и NullAway;
- SonarQube и Qodana;
- GitHub CodeQL и более широкий SAST/SecOps-контур;
- полный PMD ruleset помимо принятого CPD evaluation; revisit по умолчанию
  после `BUILD-SPOTBUGS-04`, либо раньше только по concrete risk evidence.

Dependency convergence, compiler warning policy и generated-document
consistency остаются неразрешёнными candidates. Они входят в 0.3.0 только после
отдельного scope decision с конкретным риском и desired signal.

Отложенные инструменты не требуют повторной оценки или adoption disposition для
закрытия `R030-BUILD`.

## Ratchet model

Для каждого принятого check:

1. `report-only` — reproducible report, стоимость и signal/noise;
2. `triaged` — findings классифицированы, immediate risks исправлены;
3. `baselined` — оставленный legacy signal имеет versioned rationale;
4. `blocking` — запрещены новые violations согласованного scope;
5. `tightening` — baseline и suppressions сокращаются отдельными work items.

Suppression имеет узкий selector, rationale, owner и review condition.
Global suppression запрещён, если можно описать framework/reflection false
positive точечно. Переход между стадиями фиксируется в
[build-quality ledger](../evidence/build-quality-ledger.md) и подтверждается
локальной командой и CI evidence.

## Dead/unused signals

Compiler/IDE/static analysis, Maven dependency analysis и call graph являются
candidate generators для `R030-RETIRE`, но не разрешают automatic deletion.
Framework/resource/reflection usage проверяется отдельно.

## Parent POM и BOM

Parent POM владеет build configuration и plugin management.
`dependencyManagement` согласует версии, но не добавляет dependency в classpath.

BOM применяется только для нескольких independently consumed libraries.
Spring Boot repackage остаётся только в runnable bootstrap module.

## Pull-request pipeline

Required pipeline shape:

1. checkout;
2. JDK/Maven validation;
3. compile;
4. unit tests;
5. integration/contract tests;
6. architecture checks;
7. принятые static-analysis reports/checks;
8. dependency hygiene и существующие security checks;
9. JaCoCo per-module/aggregate checks;
10. packaging;
11. documentation;
12. проверка наличия test/coverage reports;
13. project-owned artifact/evidence upload;
14. best-effort Codecov reporting.

JaCoCo запускается внутри существующего reactor `verify`, а не отдельным
дублирующим workflow. Codecov uploader MUST быть pinned, получать только явно
указанные XML reports и не иметь избыточных repository permissions. Его
недоступность не меняет результат project-owned checks.

Required checks включаются после стабилизации report mode. К release candidate
MUST быть required:

- reactor build/verify;
- JaCoCo fixed floors и per-module ratchets;
- SpotBugs blocking check для принятого signal и его reports;
- PMD CPD repository-wide report с triaged существенными findings;
- Maven dependency-analysis report с adoption disposition;
- ratchets остальных code-quality checks, для которых принято `Adopt`;
- наличие ожидаемых CI artifacts.

Scheduled stability/PIT jobs не блокируют каждый PR в 0.3.0, но их отсутствие
или отсутствие adoption disposition блокирует закрытие `R030-TEST`.

## Procedure

1. Снять inventory existing plugins/workflows.
2. Сопоставить control с release risk.
3. Запустить report mode на clean baseline.
4. Оценить noise и false positives.
5. Исправить findings с непосредственным correctness/release risk.
6. Зафиксировать plugin/version/configuration и ownership.
7. Принять узкие baseline exclusions с rationale.
8. Для SpotBugs добавить no-new-findings ratchet без снижения качества.
9. Сделать стабильный SpotBugs check частью `verify` и required CI build.
10. Для CPD выполнить semantic triage и оставить report-only enforcement.
11. Защитить required statuses branch protection.
12. Сохранить reports и timing evidence.

## Non-goals

- одновременное внедрение всех analyzers;
- исправление всего legacy debt в tool-introduction PR;
- suppression ради зелёного CI;
- одинаковые thresholds без baseline;
- zero-duplication gate без semantic triage;
- перенос сторонних token/confidence thresholds без измерения на этом
  репозитории;
- автоматическое удаление кода или dependency по одному static finding;
- внедрение отложенных analyzers без отдельного scope decision;
- добавление runtime dependencies через parent POM.

## Definition of Ready

- risk и desired signal определены;
- baseline report получен;
- noise/cost измерены;
- suppression policy предложена;
- CI ownership понятен.

## Definition of Done

- accepted checks reproducible локально и в CI;
- required checks стабильны;
- JaCoCo является частью Maven `verify`;
- project-owned JaCoCo missing-report failure проверен на реальном PR;
- Codecov upload/reports имеют operational evidence либо external-unavailability
  disposition и остаются non-blocking signal;
- Surefire/Failsafe не теряют tests между lifecycle phases;
- SpotBugs и PMD CPD имеют воспроизводимые reports и принятые configuration
  decisions;
- SpotBugs анализирует все применимые production modules, вызывается Maven
  `verify` и блокирует новые findings принятого signal;
- SpotBugs legacy filters имеют точные selectors, rationale, owner и review
  condition;
- CPD формирует repository-wide production-source report, а существенные
  findings имеют semantic disposition в `R030-QUAL`;
- CPD остаётся report-only либо отдельное решение о ratchet содержит
  deterministic baseline и точную enforcement policy;
- Maven dependency analysis имеет report и adoption disposition без
  необоснованного `failOnWarning`;
- plugin versions pinned;
- new violations блокируются для checks с решением `Adopt`;
- legacy findings имеют baseline/disposition;
- suppressions узкие;
- build time regression имеет disposition;
- reports доступны как evidence.

## Dependencies

Требует `R030-BASE`. Предоставляет instrumentation для `R030-TEST`,
`R030-RETIRE`, `R030-SEC`, `R030-LIB` и финального `R030-REL`.
