---
title: "Roadmap инженерного релиза 0.3.0"
version: "0.3.0"
status: "Accepted"
source_of_truth: false
language: "ru"
---

# Roadmap инженерного релиза 0.3.0

## Назначение и границы

0.3.0 — engineering/hardening-релиз существующего IOC Extractor. Этот комплект
содержит только принятый scope, оставшуюся работу и условия закрытия релиза.
Постоянные правила находятся в [RELEASE-PROCESS](../../RELEASE-PROCESS.md),
[CONVENTIONS](../../CONVENTIONS.md), [TESTING](../../TESTING.md) и архитектурных
картах. Roadmap не заменяет эти документы и принятые ADR.

Кроме hardening приняты и реализованы DATA-TTL-01, DATA-IMPORT-01 и
DATA-AGGREGATE-01. Они меняют lifecycle, управляемый импорт и aggregate artifact;
их границы и квалификация сведены в [R030-DATA](goals/R030-DATA-data-capabilities.md).
Сервис feeds-collector, новые внешние IOC integrations, RabbitMQ runtime,
микросервисы, смена языка/build system и спекулятивные abstractions в scope не
входят. Новые возможности требуют отдельного принятого scope change.

## Цели и условия закрытия

Все одиннадцать целей MUST в принятом scope с явными исключениями ниже.
`verified` глобального инструментария не закрывает модульные cells.
Текущие состояния и очередь находятся в
[матрице](status-matrix.md); порядок — в [модели выполнения](execution-model.md).

| ID | Outcome / release contract |
|---|---|
| R030-BASE | [Воспроизводимая исходная точка и comparison boundary](goals/R030-BASE-baseline.md) |
| R030-DATA | [TTL, managed import и aggregate, безопасная активация](goals/R030-DATA-data-capabilities.md) |
| R030-QUAL | [Конечный code-health review](goals/R030-QUAL-code-health.md) |
| R030-RETIRE | [Контролируемое устранение dead code/compatibility](goals/R030-RETIRE-retirement.md) |
| R030-ARCH | [Maven-границы и package organization](goals/R030-ARCH-architecture.md) |
| R030-TEST | [Эффективные тесты и coverage без имитации качества](goals/R030-TEST-test-quality.md) |
| R030-BUILD | [Actionable quality gates и принятые ratchets](goals/R030-BUILD-build-quality.md) |
| R030-LIB | [Проверенный shared layer и реальная публикация](goals/R030-LIB-shared-libraries.md) |
| R030-SEC | [Repository, CI и publication security](goals/R030-SEC-security.md) |
| R030-DOC | [Актуальная English-first документация и переводы](goals/R030-DOC-documentation.md) |
| R030-REL | [Воспроизводимый и квалифицированный выпуск](goals/R030-REL-release-readiness.md) |

## Поддерживаемое поведение

Необъяснённое изменение CLI/exit codes, конфигурации/precedence, SQL migrations,
канонической и внешней identity, dedup, CSV/manifest, diagnostics/ECS, daemon,
health, maintenance, sync и packaging считается регрессией. Intentional bug fix,
retirement либо DATA scope change требует явного объяснения и regression /
contract / golden / integration / smoke evidence.

Отсутствие usages, нулевое покрытие, совпадение токенов или высокая complexity
сами по себе не разрешают удаление/абстракцию. Поддерживаемые альтернативные
engines, внешние adapters, migration history и compatibility сохраняются до
контролируемого retirement. Новый найденный долг получает owner/disposition;
он не становится автоматически новой задачей релиза.

## Release gate

Перед кандидатом выполняются standing gates из RELEASE-PROCESS и TESTING:
clean reactor verification, coverage floors/ratchets, SpotBugs no-new-findings,
CPD/PMD ratchets, dependency/security policy и library consumer qualification.
Существующие aggregate 75/80 и domain/application 85/90 floors не ослабляются.
Принятые PIT/stability/dependency-analysis pilots не превращаются в обязательный
полный запуск на каждый PR: нужны их dispositions и сохранённая evidence.

Дополнительно закрываются:

- все применимые goal/module cells либо явно принятое исключение;
- compatibility и controlled retirement, representative performance/resource
  comparison с исходной точкой, объяснение регрессий/недоступных проверок;
- packaging, upgrade, coordinated rollback и целевые deployment qualification;
- документация, остаточная очередь, curate release notes и identities артефактов;
- current security disposition, включая SRC-3; старый scan не заменяет новый.

Пропущенная внешняя проверка получает named skip/defer и ограничивает support
claim. Общий release остаётся открытым до выполнения всех обязательств;
зелёная сборка или шесть опубликованных библиотек его не закрывают.

## Принятые изменения scope

| Изменение | Принятые последствия |
|---|---|
| DATA-TTL-01, 2026-08-15 | MUST R030-DATA; явная активация существующей БД, lifecycle/identity/projection, upgrade/rollback и capacity evidence; ADR 0020–0023 |
| DATA-IMPORT-01 | Managed import, delivery/receipt authority, SMB ownership и qualification; ADR 0024–0025 |
| DATA-AGGREGATE-01 | Многотипный aggregate artifact и порядок наблюдений; ADR 0030; не автоматический backfill |
| DATA-IMPORT-H5-DEFER, 2026-09-01 | ADR 0025 H5 не пройден: target-specific two-identity qualification перенесена в OPS-8; default import disabled и ограниченный support claim сохраняются |
| Библиотечные решения | Шесть RC опубликованы; независимые версии приняты ADR 0048, Maven/CI transition ещё TOOL-9; ETL остаётся внутренним |
| DOC-SEC-OUT-OF-SCOPE, 2026-11-16 | По явному решению пользователя полный аудит, переработка и перевод SECURITY-ENGINEERING/THREAT-MODEL исключены из baseline 0.3.0; SDLC и модель угроз — отдельная работа одной из следующих версий. Две inventory строки сохраняются как N/A и не блокируют DOC/release closure; применимые security gates и отдельные findings не закрываются этим исключением |
| OPS-10/12/15-DEFER, 2026-11-17 | По явному решению пользователя исправления трёх обозначенных дефектов перенесены в следующие релизы. Они остаются open в KNOWN-ISSUES с impact/mitigations и acceptance criteria; их исправление не является обязательством 0.3.0. QUAL/TEST/REL учитывают это scope change, а не заявляют defect closure или новый pass; другие OPS этим решением не исключаются |
| ARCH/QUAL-REFACTOR-DEFER, 2026-11-18 | Пользователь отложил весь предложенный архитектурный/технический рефакторинг: QUAL-BOOT-1 из fix-now в deferred, bootstrap assembly/package moves, catalog/shutdown/logging/CSV consolidation, constructor и durable-workflow decomposition. Постоянные записи ARCH-6/CODE-6 в KNOWN-ISSUES сохраняют причины/риски/owners/return criteria. Реализация не блокирует 0.3.0; module review/dispositions, уже выполненные изменения, security и final candidate gates не исключаются |

Изменение MUST-goal, compatibility/retirement, architecture, coverage, CI либо
DoD оформляется review с причиной, affected goals, влиянием на evidence и новым
disposition. MUST нельзя молча перенести в backlog.

## Остаток после принятых переносов — 2026-11-18

BASE, DATA в принятом scope, BUILD, общие TEST controls, шесть RC publications
и DOC baseline завершены. Зачтены также QUAL/RETIRE/ARCH/TEST шести библиотек;
RETIRE-APP-11 удалён. Повторное внедрение инструментов, перевод документации,
повторная публикация исходных RC и реализация ARCH-6/CODE-6 не являются
оставшейся обязательной работой этого релиза.

| Остаток | Что ещё не подтверждено / результат закрытия |
|---|---|
| Module review/dispositions | У 20 scopes не завершён полный зачёт QUAL/RETIRE/ARCH/TEST. Подтвердить remaining DoD по существующим code/tests/evidence и dispositions, не открывать новую обязательную волну отложенного refactoring. Новые конкретные gaps получают отдельное решение |
| SEC | Завершить repository/workflow/publication control review, current dependency/advisory applicability SRC-3 и SEC-LOG-1. Исключение двух security docs не исключает эти obligations; текущие external settings и fresh scan не подтверждаются старым run или текстом policy |
| Known operational/resource risks | Для не отложенных явно ING-11/13/15/16, OUT-3/4, SRC-2, OPS-11/13/14 нужны release dispositions по supported inputs/targets, mitigation и acceptance. Наличие записи не требует автоматически исправить всё, но перенос OPS-10/12/15 не переносит остальные findings |
| Library/tooling evidence | TOOL-8 protected configuration recovery не закрыта локальным consumer. TOOL-7 сохраняет unexplained signing-fixture failure. TOOL-6 требуется до следующего library release, TOOL-9 — до первого independent-version release; выбрать application-only/component scope и применимые obligations, не вводить новые gates автоматически |
| Measurements/targets | Выполнить согласованное candidate comparison и applicable resource/target qualification; ING-15/16, OUT-4 и broken lifecycle runner TOOL-10 не закрываются старой калибровкой. TOOL-11 ограничивает source-provenance claim checkout-based local deploy. При ограничении маршрута/нагрузки нужен явный допустимый disposition |
| R030-REL | Выбрать clean source/version/toolchain/targets; fresh source gates, один bundle/digest, compatibility, install/upgrade/coordinated rollback, curated notes, residual risks и final go/no-go. Publication — отдельная авторизация |

Текущий исходный снимок — docs/quality, `202cb334`, `0.3.0-SNAPSHOT` с
незакоммиченными изменениями. Сохранённые verify/PMD от 2026-11-17 passed,
но после документальных изменений stale. Итоговый release candidate/bundle
не выбран; эта сверка не является новой security, runtime или gate qualification.
Подробная очередь и владельцы — в [матрице](status-matrix.md).

## Сохранённая evidence

[Постоянная запись квалификации 0.3.0](../../../.github/release-evidence/ru/0.3.0.md)
сохраняет исходные identities, сравнения, принятые ограничения, failed attempts
и ссылки на immutable Git snapshot. [Запись 0.2.0](../../../.github/release-evidence/ru/0.2.0.md)
сохраняет опубликованные bytes и recovery, ранее существовавшие только локально.
[Компонентные release records](../../../.github/release-notes/README.md) остаются
источником фактов публикации. Ни одна историческая запись не означает fresh pass
для текущего HEAD.
