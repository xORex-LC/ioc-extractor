---
title: "R030-QUAL — Code health"
version: "0.3.0"
goal_id: "R030-QUAL"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-QUAL — Code-health review

## Outcome

Все production modules проходят конечный, evidence-driven review. Каждый
существенный finding имеет owner и disposition; согласованные refactoring
slices сохраняют поведение либо отдельно оформляют bug fix.

Execution mode: **per module/capability**.

Рабочий ledger:

- [review ledger](../evidence/review-ledger.md).

## Review dimensions

### Responsibility и cohesion

- назначение класса описывается одним предложением;
- отсутствуют смешанные reading/parsing/validation/persistence concerns;
- нет god classes, service locators и dumping-ground utilities;
- side effects и state ownership явны.

### State и contracts

- объект не может бесконтрольно существовать в invalid state;
- mutable collections не протекают наружу;
- `null`, equality и hashing contracts определены;
- public surface минимальна;
- exception wrapping сохраняет root cause.

### Behavior и resources

- методы имеют связный уровень abstraction;
- retry, timeout и partial-failure semantics явны;
- ресурсы закрываются;
- concurrent state имеет установленный thread-safety contract;
- regex, normalization и fallback paths проверены.

### Dependencies

- dependencies передаются явно;
- domain/application boundaries не протекают;
- package/module cycles отсутствуют;
- интерфейс представляет реальную boundary;
- infrastructure details не входят в inward public API.

## Duplication triage

DRY относится к knowledge, rule и invariant, а не ко всем одинаковым строкам.

| Вид | Default action |
|---|---|
| Одинаковый rule/invariant/policy/canonical data | Один authoritative source либо accepted rationale |
| Одинаковые implementation и semantics | Deduplicate на минимальной границе |
| Похожий код с разными semantics/owner/lifecycle | Сохранить раздельно |
| Framework boilerplate | Убирать только при измеримом упрощении |
| Repeated test setup | Extract только при сохранении читаемости |
| Предположительно dead copy | Передать в `R030-RETIRE` |

Duplicate finding фиксирует:

- occurrences и owners;
- общее knowledge/behavior и различия;
- reason to change;
- риск divergence;
- tests;
- минимальную границу решения;
- dependency/visibility/API impact;
- disposition и rationale.

Порядок выбора границы:

```text
private method
    ↓
package-private component / value object / policy
    ↓
internal subsystem package
    ↓
existing inward-facing module boundary
    ↓
new shared module only after R030-LIB admission
```

Rule of Three является сигналом анализа, а не автоматическим требованием.

### Роль PMD CPD

PMD CPD является candidate generator, а не автоматическим refactoring
authority. Token match не доказывает общее knowledge, одинаковую причину
изменения или допустимость новой dependency.

Для каждого существенного CPD cluster review MUST:

1. подтвердить production occurrences и отфильтровать generated/fixture noise;
2. определить, какое knowledge или behavior действительно повторяется;
3. сравнить owners, semantics, lifecycle и expected evolution;
4. выбрать минимальную допустимую границу из списка выше;
5. оценить dependency direction, visibility и public API impact;
6. назначить `deduplicate`, `retain` или `defer` с rationale;
7. только после этого создавать implementation work item.

`retain` является корректным результатом, когда похожий код представляет разные
concepts или меняется по разным причинам. `deduplicate` не означает
автоматического помещения кода в shared library: межмодульное извлечение
проходит admission criteria `R030-LIB`.

## Findings и dispositions

| Disposition | Значение |
|---|---|
| `fix-now` | Риск или release scope требует исправления |
| `debt` | Finding подтверждён, безопасно отложен |
| `accepted` | Текущее решение намеренно сохраняется |
| `not-applicable` | False positive или неприменимо |

Нахождение долга не делает его автоматически обязательством 0.3.0.

## Procedure

1. Прочитать module README, capability docs и related ADRs.
2. Зафиксировать public surface, dependencies и critical behavior.
3. Выполнить goal-specific analysis без реализации.
4. Записать findings и dispositions.
5. Сформировать reviewable work items.
6. Добавить characterization tests до risky refactoring.
7. Выполнять structural и behavioral changes отдельно.
8. Обновить enforcement и docs.
9. Сохранить verification evidence и matrix state.

## Definition of Ready

Work item имеет:

- module/capability scope;
- finding ID и disposition;
- current behavior;
- acceptance criteria;
- dependency/public API impact;
- tests и verification plan.

## Definition of Done

- весь согласованный production inventory рассмотрен;
- каждый finding имеет disposition;
- `fix-now` items выполнены или goal scope официально изменён;
- critical duplicated knowledge имеет authoritative source либо rationale;
- behavior-preserving changes подтверждены tests;
- bug fixes имеют regression tests;
- docs и matrix обновлены.

## Dependencies

Требует `R030-BASE`. Findings MAY создавать work items для `R030-RETIRE`,
`R030-ARCH`, `R030-TEST` и `R030-LIB`.
