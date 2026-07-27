---
title: "R030-RETIRE — Dead code and compatibility retirement"
version: "0.3.0"
goal_id: "R030-RETIRE"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-RETIRE — Dead code и compatibility retirement

## Outcome

Dead/unwired code и больше не требуемая compatibility не поддерживаются
бессрочно без owner и основания. Доказанные obsolete surfaces удаляются
целиком; migration/recovery/audit history сохраняется.

Execution mode: **global inventory + per-surface removal**.

Рабочий ledger:

- [retirement inventory](../evidence/retirement-inventory.md).

## Definitions

- **Dead code** — недостижим из поддерживаемых execution/build paths.
- **Unwired code** — компилируется, но не зарегистрирован runtime composition.
- **Obsolete compatibility** — surface находится вне принятого support window
  и не имеет подтверждённых consumers.
- **Historical evidence** — migrations, schemas, manifests, ledgers или docs,
  необходимые для upgrade, recovery, audit либо действующего контракта.

## Controlled-retirement contract

Removal разрешён, когда evidence подтверждает, что surface:

- не участвует в supported runtime/build/deployment/upgrade path;
- не требуется declared external consumers и supported source versions;
- не является необходимой migration/recovery/audit/rollback history;
- имеет owner и retirement disposition;
- удаляется вместе с exclusive wiring, tests, configuration, dependencies,
  packaging и documentation.

Static reference search является сигналом, но не доказательством.

## Required analysis

Проверяются:

- direct/transitive compile references и dependency tree;
- Spring beans, conditions, listeners и configuration metadata;
- `META-INF`, ServiceLoader, reflection и serialization;
- resource FQCN, включая Logback;
- CLI, env/config keys и automation output;
- SQL migrations, durable state, file formats, manifests и wire schemas;
- scripts, installers, systemd templates и runbooks;
- published Maven API и known external consumers;
- test fixtures, golden corpus и generated ownership;
- history context.

Compiler/IDE/static analysis, Maven dependency analysis, call graphs и
repository search MAY создавать candidates. Ни один tool отдельно не
авторизует removal.

## Dispositions

| Disposition | Значение |
|---|---|
| `remove-now` | Dead/obsolete status доказан |
| `keep-supported` | Surface входит в compatibility contract |
| `keep-history` | Runtime use отсутствует, history нужна |
| `deprecate-first` | Нужны transition window и migration guidance |
| `defer-uncertain` | Evidence недостаточно; removal запрещён |

## Removal slice

Нейтрализация доказанного dead code означает physical removal. Отключение
флагом или хранение «на всякий случай» не считается завершением.

Removal slice MUST:

- удалить production code и runtime wiring;
- удалить exclusive resources/configuration;
- удалить ставшие ненужными dependencies;
- удалить или переписать tests удалённого контракта;
- сохранить tests соседнего supported behavior;
- обновить docs, packaging и migration guidance;
- проверить dependency tree и rendered interfaces;
- выполнить targeted и clean reactor gates.

Accepted SQL migrations и append-only upgrade evidence не переписываются и не
удаляются. Изменение live schema выполняется новой migration.

## Procedure

1. Определить owner и первоначальную цель surface.
2. Проверить static dependency evidence.
3. Проверить runtime/resource/dynamic wiring.
4. Проверить public, operational и persistence contracts.
5. Определить supported compatibility obligation.
6. Назначить disposition.
7. Создать отдельный reviewable work item.
8. Выполнить removal либо transition plan.
9. Проверить supported paths и отсутствие retired surface.
10. Обновить release notes и matrix.

## Definition of Ready

Определены:

- owner и supported surface/source versions;
- static и dynamic usage evidence;
- external consumers;
- migration/history role;
- полный removal scope;
- tests, migration guidance и rollback implications.

При недостатке evidence используется `defer-uncertain`.

## Definition of Done

- все найденные candidates имеют disposition;
- все согласованные `remove-now` slices удалены целиком;
- `keep-history` evidence сохранено;
- `deprecate-first` имеет owner и transition plan;
- `defer-uncertain` перечисляет missing evidence;
- dependencies, CLI/config, packaging и upgrade paths проверены;
- intentional retirements отражены в release notes.

## Dependencies

Требует `R030-BASE` support inventory и связан с `R030-QUAL`, `R030-ARCH`,
`R030-TEST`, `R030-DOC` и `R030-REL`.
