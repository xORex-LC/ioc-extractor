---
title: "R030-BUILD — Build quality"
version: "0.3.0"
goal_id: "R030-BUILD"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-BUILD — Качество сборки и сигнал CI

## Результат и состояние

`verified`: принятые compiler, architecture, analyzer, dependency и test controls
воспроизводимы; активация required project-owned statuses подтверждена в
[release evidence](../../../../.github/release-evidence/0.3.0.md#build-and-test-adoption).
Закрыты `BUILD-SPOTBUGS-01/04/05`, `BUILD-CPD-02`, `BUILD-DEPS-03` и `BUILD-PMD-06`.
Это завершение общего контура, а не модульного code-health review.

Текущая конфигурация, scope, версии, report-integrity и правила обновления
ratchets принадлежат [build quality](../../../dev/build-quality.md),
[TESTING](../../../TESTING.md), POM и versioned build-support inputs.

## Сохранённый результат

- Wrapper/Enforcer, JDK 21, ArchUnit, reactor CI, documentation и действующие
  security/release workflows сохранены.
- SpotBugs работает в `verify` с raw/filtered reports и exact finding gate.
  Каждая acceptance имеет identity/selector, rationale, owner и review trigger;
  proposal tooling не принимает findings автоматически.
- CPD имеет единый production-source report и reviewed group-count ratchet;
  совпадение tokens проходит semantic triage в `R030-QUAL`. Замена группы при
  прежнем количестве остаётся известным ограничением компактной модели.
- PMD source policy принята отдельно: поимённые rules, blocking zero-baseline
  rules, exact advisory counts и отдельный исполняемый ownership/size watchlist.
- JaCoCo/report-integrity входят в `verify`; lossless Surefire/Failsafe lifecycle
  и best-effort Codecov подтверждены совместно с `R030-TEST`.
- Dependency analysis прошёл bounded evaluation и исправление подтверждённых
  POM mismatches; постоянный blocking lifecycle оставлен отложенным. Opt-in
  advisory report не получает broad ignores или необоснованный `failOnWarning`.

## Критерии сохранения закрытия

- Принятые checks сохраняют полный scope, pinned tools, воспроизводимые XML/HTML,
  fail-closed analyzer/report behavior и project-owned CI evidence.
- Изменение scope/baseline проходит semantic review; findings не скрываются
  широкими suppressions и снижением floors.
- Улучшения обновляют reviewed ratchets явно, с rationale и применимой проверкой;
  новые blocking controls требуют отдельного adoption decision.
- Наличие старого успешного run не заменяет свежий gate финального кандидата.
  Required statuses и внешние settings повторно сверяются в `R030-SEC/REL`.

## Принятые отсрочки и граница

Spotless/Checkstyle, japicmp/Revapi, Error Prone/NullAway, SonarQube/Qodana и
SAST/CodeQL не входят в этот release scope. Dependency convergence, расширение
compiler-warning policy и новые generated-document controls требуют отдельного
решения; их отсутствие не переоткрывает завершённую adoption.

Tool adoption не требует устранения всего legacy debt и не разрешает
автоматический refactoring/removal. Test-bytecode analysis и CPD fingerprint
identity допускаются только после evidence полезности. Родительский POM не
добавляет runtime dependencies, repackage остаётся в runnable bootstrap.
`R030-BUILD` опирается на `R030-BASE` и предоставляет controls остальным goals.
