---
title: "Модель выполнения 0.3.0"
version: "0.3.0"
status: "Accepted"
source_of_truth: false
language: "ru"
---

# Модель выполнения 0.3.0

Рабочая единица: `release + goal + scope + stage + work-item`.
[Release contract](engineering-release.md) задаёт границы;
[status matrix](status-matrix.md) — фактическую очередь. Политики качества,
публикации и документации определены в опубликованных документах, а не здесь.

## Состояния и ownership

| State | Значение |
|---|---|
| planned | Scope ожидает проверки applicability/DoD |
| analyzing | Выполняется анализ |
| analyzed | Findings/evidence и dispositions готовы |
| ready | Acceptance criteria и verification plan приняты |
| in-progress | Реализуется согласованный item |
| verified | Scoped DoD выполнен и evidence сохранена |
| deferred | Принят явный defer с exit condition |
| blocked | Указан конкретный внешний blocker |
| N/A | Неприменимость подтверждена goal contract |

На пересекающемся scope реализуется один item. Модуль — planning container,
крупную capability можно делить на slices. Глобальное закрытие TEST/BUILD/LIB
не закрывает QUAL/RETIRE/ARCH/TEST/DOC cells модуля автоматически.

## Оставшийся порядок

1. Сверить незакрытые cells и intake с текущим кодом/контрактами; не открывать
   заново уже устранённые regex/POM/dependency findings.
2. Выполнять module hardening от platform/core к adapters/bootstrap. Анализ
   внешнего модуля допустим раньше, изменение dependency direction — только
   после архитектурного решения. Risk evidence может изменить порядок.
3. Параллельно независимым scopes закрывать SEC, DOC и publication follow-ups.
4. Выполнить final compatibility/performance/packaging/upgrade/rollback,
   security и release gate R030-REL на выбранном candidate.

BASE, instrumentation/pilots и DATA implementation не повторяются как новые
проекты. Нужен re-baseline только при явном изменении comparison contract;
новый HEAD всё равно получает fresh обязательные gates.

## Порядок одного item

1. Прочитать goal, module README, capability docs, ADR; проверить Git/context,
   live callers/config/resources/tests и published API implications.
2. Зафиксировать supported behavior, invariant, findings, owner/disposition и
   критерии приёмки. Analysis-only запрос не разрешает implementation.
3. Добавить characterization перед рискованным изменением. Удалять только
   доказанно dead/obsolete surface с compatibility disposition.
4. Разделять package moves, algorithm changes и dependency swaps; сохранять
   inward boundaries, durable authority и failure/recovery semantics.
5. Обновить affected docs/Javadoc в том же изменении.
6. Пройти smallest relevant tests, затем обязательный reactor gate; сохранить
   source/artifact identity, unavailable checks и остаточные risks.
7. Обновить owning matrix cell/очередь. Goal-level статус меняется только при
   закрытии всех применимых scopes.

До реализации должны быть понятны scope/non-goals, acceptance criteria,
compatibility и test/data/environment needs. Нет silent API/threshold changes,
catch-all abstractions, удаления accepted migration history или release
publication без отдельной авторизации.

## Где сохранять результат

Новые временные журналы в этот комплект не добавляются. Постоянные outcomes
переходят в owning capability/guide, Javadoc/README, ADR, KNOWN-ISSUES либо
release record. Roadmap хранит только оставшуюся работу и ссылки на outcomes.
Для DOC используется [очередь](documentation/remaining-work.tsv), а не старый
полный census файлов и хешей. Accepted ADR original provenance сохраняется
по протоколу R030-DOC.

Evidence содержит source/commit, дату, команду/fixture, границы измерения,
результат и disposition. PASS другой source identity не fresh evidence для
кандидата. Named external skip не равен pass, merged PR не заменяет scoped DoD.
