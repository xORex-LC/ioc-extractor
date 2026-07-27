---
title: "R030-DOC — Documentation"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-DOC — Documentation migration и consistency

## Outcome

Публикуемая документация English-first, структурно единообразна и соответствует
коду. Russian translations располагаются в определённых `ru/` paths. README
остаются только на английском.

Execution mode: **same-change module updates + global translation wave**.

Авторитетные conventions:

- [CONVENTIONS](../../../CONVENTIONS.md);
- [RELEASE-PROCESS](../../../RELEASE-PROCESS.md).

## Language layout

| English canonical | Russian translation |
|---|---|
| `docs/*.md` | `docs/ru/*.md` |
| `docs/dev/*.md` | `docs/dev/ru/*.md` |
| `docs/ADR/*.md` | `docs/ADR/ru/*.md` |
| `docs/guides/*.md` | `docs/guides/ru/*.md` |

README:

- root/module/directory/package `README.md` переводится на English на месте;
- Russian README copy не сохраняется.

Worknotes:

- остаются Russian-only;
- не имеют English pair;
- не являются published documentation;
- не становятся link targets из docs/README.

Новый документ или substantive rewrite создаётся на английском. Один документ
не смешивает prose languages.

## Documentation axes

- root docs — project-wide maps/law;
- `docs/dev/` — mechanics одной capability;
- `docs/ADR/` — immutable decision history;
- `docs/guides/` — operator/integrator usage;
- co-located README — module/directory reference.

Документ не дублируется между buckets: используется cross-link.

В 0.3.0 создаётся English canonical `docs/TESTING.md` как project-wide testing
law: test levels/lifecycle, naming/tags, local/CI commands, coverage thresholds,
flake/exclusion policy и evidence expectations. Capability-specific test
mechanics остаются в соответствующих `docs/dev/` или module README и
cross-link на общий contract.

## Same-change rule

Feature/refactor/retirement/public API change обновляет:

- affected capability doc;
- module/directory README;
- root architecture/module map, если затронута;
- guide/operator contract, если изменён;
- release notes/migration guidance при compatibility impact.

Stale docs считаются failing contract.

## ADR translation protocol

Перевод accepted ADR является controlled file-migration exception, но не
изменением решения:

1. существующий Russian ADR переносится в `docs/ADR/ru/` побайтово, без
   исправления prose, metadata или links;
2. English translation появляется в canonical `docs/ADR/` и явно помечается
   как перевод исторической записи, а не новая редакция решения;
3. status/context/decision/alternatives/consequences сохраняются;
4. English translation ссылается на Russian original, а `docs/ADR/README.md`
   сопоставляет обе версии; Russian original ради reciprocal link не меняется;
5. translation отделена от architecture reconsideration;
6. semantic parity проходит review;
7. English translation становится authoritative для canonical navigation, но
   не переписывает исторический Russian source;
8. дальнейшие изменения снова follow append-only/supersede policy.

Нумерация ADR не меняется.

## Generated documentation

Generated docs:

- не редактируются и не переводятся вручную;
- изменяются через code/generator;
- получают Russian variant только при поддержке генератора;
- проверяются consistency gate.

`docs/DIAGNOSTICS-CATALOG.md` и `docs/LOGGING-CATALOG.md` полностью исключены из
translation scope: они остаются canonical generated references, не получают
ручных или Russian copies и пинятся существующими doc-sync tests.

## Migration inventory

Inventory классифицирует каждый документ:

- canonical language;
- translation required/not-required;
- translated;
- stale;
- generated;
- README English-only;
- owner и related capability.

## Procedure

1. Построить translation/document inventory.
2. Перевести root maps.
3. Перевести capability docs.
4. Перевести guides.
5. Перевести ADR по protocol.
6. Перевести README in place.
7. Проверить generated disposition.
8. Исправить navigation/internal links.
9. Выполнить semantic parity review.
10. Запустить documentation convention/link checks.

## Definition of Ready

- source/target paths известны;
- document bucket/owner определены;
- ADR/generated/README policy применима;
- semantic-review criteria установлены;
- architecture change не смешивается с translation.

## Definition of Done

- English originals находятся canonical paths;
- `docs/TESTING.md` соответствует live Maven/Make/CI behavior;
- required Russian translations находятся в `ru/`;
- Russian ADR originals сохранены побайтово, English translations помечены и
  проверены по protocol;
- README English-only;
- generated docs не hand-edited, а diagnostic/logging catalogs не включены в
  translation scope;
- stale translations помечены;
- navigation и links проходят checks;
- affected code contracts отражены.

## Dependencies

Получает changes от всех goals. Global translation wave может выполняться
параллельно, но final completion блокирует `R030-REL`.
