---
title: "R030-BASE — Baseline"
version: "0.3.0"
goal_id: "R030-BASE"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-BASE — Воспроизводимый baseline

## Outcome

Текущее состояние проекта измерено до structural hardening. Все последующие
ratchets, regressions и improvements сравниваются с одним versioned baseline.

Execution mode: **global foundation**.

## Scope

Baseline MUST фиксировать:

- последний released tag и точный commit;
- Maven revision, JDK, Maven, OS и воспроизводимые команды;
- список Maven-модулей и dependency graph;
- состояние clean reactor build;
- unit/integration/e2e test inventory;
- line/branch coverage по модулям и aggregate;
- compiler/static/dependency warnings;
- flaky tests и durations;
- build/test duration;
- representative processing/runtime measurements;
- package/module hotspots;
- deprecated/legacy surfaces;
- supported compatibility и upgrade/source-version obligations;
- известные external consumers;
- состояние опубликованной документации;
- существующие security/build/release controls.

## Controls inventory

Каждый control классифицируется:

| State | Значение |
|---|---|
| `Existing` | Реализован и должен быть сохранён |
| `Strengthen` | Действует, но расширяется в 0.3.0 |
| `Introduce` | Отсутствует и входит в scope |
| `Evaluate` | Требует report/spike |
| `Deferred` | Явно не входит в 0.3.0 |

Maven Wrapper, Maven Enforcer, ArchUnit, reactor CI, documentation-link checks,
dependency security workflow и release workflow уже считаются `Existing`.
Нельзя планировать их как отсутствующие.

## Procedure

1. Выполнить `make context` и сохранить branch/commit/version evidence.
2. Проверить toolchain через `make doctor-core`.
3. Выполнить clean reactor verification.
4. Сформировать module/dependency inventory.
5. При отсутствии coverage tooling ввести только measurement-only JaCoCo
   report bootstrap без thresholds и test remediation.
6. Сохранить test, coverage, warning и duration reports.
7. Зафиксировать runtime/performance representative cases.
8. Создать initial review, retirement, shared-code и test-quality inventories.
9. Заполнить [status matrix](../status-matrix.md).
10. Не исправлять findings в baseline change.

Measurement-only bootstrap является технической частью baseline capture, а не
ранним enforcement `R030-BUILD`/`R030-TEST`: он не добавляет thresholds, exclusions
ради результата или behavior changes.

## Evidence

Основной ledger:

- [baseline evidence](../evidence/baseline.md).
- [test-quality evidence](../evidence/test-quality-ledger.md) для подробных
  per-module measurements и последующих ratchets.

Минимальное evidence:

- commit и toolchain;
- команды с exit status;
- machine-readable reports либо ссылки на CI artifacts;
- timestamps;
- список unavailable measurements с disposition.

Baseline не считается воспроизводимым, если содержит только итоговые числа без
команд и source revision.

## Non-goals

- исправление найденных дефектов;
- package relocation;
- введение thresholds до измерения;
- исправление coverage gaps в measurement-only bootstrap;
- удаление legacy/dead code;
- выбор publication candidate без admission review.

## Definition of Ready

- branch и release revision известны;
- environment проходит core doctor;
- определены representative workloads;
- известны пути сохранения reports;
- baseline change не содержит behavior changes.

## Definition of Done

- все перечисленные baseline dimensions имеют значение либо disposition;
- clean verification result сохранён;
- module graph и initial matrix заполнены;
- controls inventory классифицирован;
- evidence позволяет повторить измерение;
- другие goal contracts могут ссылаться на один baseline revision.

## Dependencies

`R030-BASE` не зависит от других goals и блокирует enforcement в `R030-BUILD`,
thresholds в `R030-TEST`, module hardening waves и финальный `R030-REL`.
