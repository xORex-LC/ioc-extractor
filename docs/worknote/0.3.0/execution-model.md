---
title: "Модель выполнения релиза 0.3.0"
version: "0.3.0"
status: "Accepted"
document_type: "Execution model"
source_of_truth: false
language: "ru"
---

# Модель выполнения релиза 0.3.0

## 1. Основной принцип

Работа организуется в двух измерениях:

- **goal contract** задаёт правила, границы и evidence;
- **Maven module или capability slice** является единицей исполнения.

[Status matrix](status-matrix.md) связывает эти измерения. Не применяется ни
чистый repository-wide проход по одной цели, ни один огромный PR «все цели для
одного модуля».

Активная задача всегда задаёт:

```text
release + goal + scope + stage + work-item
```

## 2. Applicability целей

| Goal | Execution mode |
|---|---|
| `R030-BASE` | Global foundation |
| `R030-DATA` | Capability slices P0–P6; no partial production activation |
| `R030-QUAL` | Per module/capability |
| `R030-RETIRE` | Global inventory + per-surface removal |
| `R030-ARCH` | Global rules + per-module package map |
| `R030-TEST` | Global instrumentation + per-module hardening |
| `R030-BUILD` | Global toolchain + ratchet |
| `R030-LIB` | Candidate modules + global publication |
| `R030-SEC` | Primarily global |
| `R030-DOC` | Same-change updates + global translation wave |
| `R030-REL` | Final global gate |

`N/A` определяется goal contract, а не предположением агента.

## 3. Состояния

Matrix cell использует одно состояние:

| State | Значение |
|---|---|
| `planned` | Scope ожидает анализа |
| `analyzing` | Выполняется read-only analysis |
| `analyzed` | Findings и evidence сохранены |
| `ready` | Work items прошли DoR |
| `in-progress` | Идёт реализация согласованного work item |
| `verified` | Scope DoD и evidence выполнены |
| `deferred` | Есть явный defer decision |
| `blocked` | Есть конкретный внешний blocker |
| `N/A` | Goal неприменим по контракту |

Одновременно в `in-progress` находится один work item на пересекающемся scope.

## 4. Execution waves

### Wave 0 — Baseline

До структурных изменений:

- фиксируются commit, toolchain и module graph;
- выполняется clean baseline build;
- сохраняются test inventory, warning, dependency и timing reports;
- measurement-only JaCoCo MAY быть введён как bootstrap-инструмент
  `R030-BUILD` до фиксации coverage baseline;
- после этого сохраняются per-module и aggregate line/branch reports без
  enforcement;
- создаются review, retirement и shared-code inventories;
- фиксируются supported compatibility/upgrade obligations;
- заполняется начальная status matrix.

Такой report-only bootstrap не является module hardening и не разрешает
параллельное исправление test gaps. Он устраняет зависимость «для baseline уже
нужен ещё не введённый измеритель».

### Wave 1 — Report mode и пилот

Новые static/coverage/dependency checks сначала работают в report mode.
Для `R030-BUILD` это означает reactor-wide SpotBugs report, PMD CPD aggregate
report и bounded Maven `dependency:analyze-only` evaluation. Их introduction
не смешивается с массовым исправлением findings.
Выбираются:

- один небольшой platform module;
- один ограниченный adapter module.

Пилот проверяет размеры work items, формат findings, evidence и thresholds.
После пилота conventions фиксируются до масштабирования.

SpotBugs после полного triage и узкого baseline переходит в blocking
no-new-findings check внутри Maven `verify`. PMD CPD остаётся repository-wide
report-only control; его существенные findings проходят semantic triage в
`R030-QUAL`. Tool thresholds не копируются из сторонних проектов и принимаются
только после измерения текущего repository signal.

Maven dependency analysis не получает `failOnWarning`, пока bytecode-only
signal и framework/reflection exclusions не подтверждены evidence.

Для `R030-TEST` в этой wave дополнительно:

- принимаются coverage universe/exclusions;
- проверяется Surefire/Failsafe discovery без потери tests;
- стабилизируются JaCoCo aggregate и best-effort Codecov reporting;
- запускаются отдельный Maven-profile PIT/domain и random-order pilots;
- PIT report сохраняется как artifact, survived mutants получают triage;
- полный PIT выполняется scheduled/manual и не добавляется в каждый PR;
- fixed release floors ещё не подменяют remediation plan, но per-module
  regressions уже запрещаются ratchet.

### Wave 2 — Module hardening

Предпочтительный dependency-aware порядок:

1. `platform/*`;
2. `core/ioc-domain`;
3. `core/ioc-application` и `ioc-application-tck`;
4. `adapters/*`;
5. `bootstrap/ioc-app`.

Risk evidence MAY изменить порядок. Analysis outer modules допускается раньше,
но structural implementation обычно следует после стабилизации inward
dependencies.

Крупные модули делятся на capability slices. Maven-модуль является planning
container, а не обязательным размером одного PR.

### Wave 3 — Cross-cutting closure

Параллельно с module waves закрываются:

- global build/CI controls;
- repository security;
- library publication infrastructure;
- documentation translation inventory.

Publication выполняется после hardening выбранной publication unit.

### Wave 4 — Release closure

Выполняются aggregate gates, compatibility/performance comparison, packaging,
upgrade, rollback, documentation и release notes.

## 5. Порядок внутри модуля

```text
1. Orientation and inventory
2. Characterize supported behavior
3. Retire proven dead/obsolete surfaces
4. Restructure packages and enforce boundaries
5. Refactor quality/duplication findings
6. Close risk-based test gaps and coverage ratchet
7. Update affected documentation
8. Targeted verification + module gate + reactor gate
```

Tests добавляются до рискованного изменения и повторно оцениваются после
структурного hardening.

Targeted test является первым feedback loop, module tests/report — вторым,
полный reactor `verify` — обязательным финальным gate. Coverage-only tests без
meaningful assertions не закрывают шаг 6.

## 6. Analysis и implementation

Analysis является отдельной стадией и по умолчанию read-only. Результат:

- findings;
- evidence;
- dispositions;
- предлагаемые work-item IDs;
- risks и зависимости;
- verification plan.

Implementation начинается только для work item со статусом `ready`. Агент не
расширяет реализацию на соседние findings без scope decision.

## 7. PR boundaries

Предпочтительная последовательность:

```text
Characterization tests
Package-independent dead-code retirement
Package relocation
Internal refactoring / duplication removal
Shared-component extraction
Architecture and quality enforcement
Documentation
```

Документация затронутого контракта обновляется в том же change. Не следует без
необходимости смешивать:

- package move и algorithm change;
- retirement и unrelated cleanup;
- public API rename и internal refactoring;
- library extraction и publication infrastructure;
- tool introduction и массовое исправление legacy findings.

PR классифицируется как `test`, `package-move`, `refactor`, `bugfix`,
`dead-code-removal`, `compatibility-retirement`, `architecture`,
`library-extraction`, `library-publication`, `messaging-contract`, `build`,
`ci`, `security`, `documentation` или `translation`.

## 8. Общий Definition of Ready

Work item готов, если:

- указаны goal ID, scope, stage и owner;
- описаны outcome и acceptance criteria;
- текущий contract понятен или characterization coverage достаточна;
- известны затронутые public API, dependencies и architecture constraints;
- определены verification commands и evidence;
- изменение помещается в reviewable scope.

Goal-specific DoR имеет приоритет над этим минимумом.

## 9. Общий Definition of Done

Work item завершён, если:

- acceptance criteria выполнены;
- поведение сохранено либо intentional change документирован;
- targeted tests и обязательные gates пройдены;
- evidence ledger и status matrix обновлены;
- affected documentation актуальна;
- пропущенные проверки и остаточные risks названы.

Зелёный build не компенсирует нарушение scope или удаление полезного
compatibility/history context.

## 10. Контракт задачи для агента

Рекомендуемый analysis prompt:

```text
Release: 0.3.0
Goal: R030-RETIRE
Scope: adapters/adapter-store-jdbc
Stage: analysis-only

Read:
- engineering-release.md
- execution-model.md
- goals/R030-RETIRE-retirement.md
- module README
- related dev docs and ADRs

Deliver:
- findings
- evidence
- dispositions
- proposed work items

Do not implement during this stage.
```

Implementation prompt:

```text
Release: 0.3.0
Goal: R030-RETIRE
Scope: adapters/adapter-store-jdbc
Stage: implementation
Work item: RETIRE-JDBC-3
```

## 11. Правила для агентов

До изменения агент:

1. проверяет live branch/context;
2. читает goal contract и scope documentation;
3. изучает tests, direct/transitive dependencies и runtime wiring;
4. проверяет влияние на supported/published contracts;
5. фиксирует assumptions.

Во время изменения агент:

- сохраняет узкий scope;
- не превращает similar code в shared abstraction без admission criteria;
- не считает отсутствие Java usages доказательством dead code;
- не изменяет algorithm вместе с package move;
- сохраняет dependency direction;
- обновляет tests, enforcement и docs вместе с контрактом.

Запрещено:

- repository-wide rewrite без разрешения;
- catch-all `commons`/`shared`/`utils`;
- удаление accepted migrations/history как cleanup;
- ослабление tests/thresholds ради зелёного CI;
- молчаливое изменение public API;
- локальная публикация release artifacts.

## 12. Обновление состояния

После каждого analysis/implementation work item обновляются:

1. соответствующий evidence ledger;
2. matrix cell;
3. goal-level status, если закрыт последний применимый scope;
4. release contract только при scope change.

Goal documents не используются как журналы выполнения.
