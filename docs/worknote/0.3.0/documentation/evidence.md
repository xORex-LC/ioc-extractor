---
title: "R030-DOC — Проверки inventory и первого пакета"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified for DOC-INV-01 and DOC-01A"
document_type: "Verification evidence"
source_of_truth: false
language: "ru"
---

# Проверки DOC-INV-01 и DOC-01A

Дата: 2026-11-01, Asia/Irkutsk. Исходный HEAD:
`2c76d8b095a2162c7038c64de896b244517cf4c7`, ветка `docs/quality`.
Проверки выполнены на documentation worktree поверх этого HEAD до создания
коммитов. Они не являются post-commit полным verify/PMD evidence.

## Исходное состояние

`make context`: чистое дерево, `0.3.0-SNAPSHOT`, upstream отсутствует.
Последние полные verify/PMD прошли на `191e74146dddeeab36bf59d9d09020a92bef0620`;
для текущего HEAD оба freshness-флага `false`.

POM census: parent + 26 функциональных JAR + 4 report POM = 31 проект.
SpotBugs/CPD/PMD registries учитывают 31 проект и 25 production scopes;
test-support TCK исключён из production denominators. Это текущие значения,
а не исправление исторического baseline.

## Проверенные источники первого пакета

- `ArchitectureTest`, `DomainBoundaryTest`, `PipelineModelArchitectureTest`,
  module-local `LoggingArchitectureTest` и POM bans.
- `BuildQualityVerifier.verifyCpdReports`, `verifyPmdReports`,
  `verifyPmdPolicyCounts`; scope TSV и report POMs.
- `Makefile`, `tools/ci/build.sh`, `tools/ci/pmd.sh` и CI jobs.
- Шесть descriptors `COMPONENTS`, protected workflow inputs и events
  core/adapter admission/recovery tests.

Смысловой обзор EN/RU BOUNDARIES выполнен по одинаковым разделам, строкам
архитектурной таблицы, режимам blocking/advisory, exceptions и deferred controls.
Проверка одинаковых identifiers и нормализованных относительных targets
дополняет этот обзор, но не заменяет его.

## Запуски

| Проверка | Результат |
|---|---|
| Полнота inventory / source paths / hashes / translation paths | Пройдено: 329/329 исходных paths и 3 новых, 332 уникальных строки, 61 набор источников; все пути и SHA-256 сверены; 77 отсутствующих RU-пар явно отмечены |
| Сверка topology / registry / matrix | Пройдено: 31 проект, по 25 analyzed scopes, ровно 26 функциональных строк в module matrix |
| Сверка BOUNDARIES EN/RU | Пройдено: 5 разделов, одинаковые inline identifiers, одинаковые нормализованные targets; имена правил сверены с ArchitectureTest; смысловой обзор описан выше |
| Сверка publication selectors | Пройдено: все 6 селекторов раздела совпадают с protected workflow; dependency/core/recovery assertions сверены с descriptors и тестами |
| `make test-one MODULE=bootstrap/ioc-app TEST=ArchitectureTest` | Пройдено: 34 tests, 0 failures/errors/skips |
| `make test-one MODULE=bootstrap/ioc-app TEST=DocumentationConventionTest` | Пройдено: 1 test, 0 failures/errors/skips |
| `make library-publication-test` | Пройдено: 228 tests, 27.939 s, без retries; это offline fixtures, remote publication не выполнялась |
| `make docs` | Пройдено: 1911 link occurrences, 554 unique, 1691 OK, 0 errors, 220 excluded offline policy |
| `git diff --check` и whitespace новых Markdown | Пройдено |

Локальные console receipts сохраняются в ignored `.dev/r030-doc/`.
В пакет не входят новые production/test Java, POM, analyzer configuration,
catalog generation или исполняемые guide examples. Полные `make verify` и
`make pmd-analysis` в этом documentation slice не перезапускаются; свежесть
старых результатов не заявляется. Новый live security scan и remote repository
qualification не выполняются.

## Ограничение результата

Inventory фиксирует 100% учёт исходных Markdown paths. Большинство документов
требует дальнейшего смыслового ревью и перевода. DOC-01A проверяет BOUNDARIES
и изменённый раздел RELEASE-PROCESS; весь `R030-DOC` остаётся `in-progress`.
Исторические ADR, generated catalogs, release identities и baseline evidence
не изменены.
