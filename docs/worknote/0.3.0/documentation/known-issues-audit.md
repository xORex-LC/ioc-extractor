---
title: "DOC-01B.5 — Аудит реестра известных проблем"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified registry documentation; source findings remain open"
document_type: "Execution evidence"
source_of_truth: false
language: "ru"
---

# DOC-01B.5 — Аудит реестра известных проблем

Проверка 2026-11-07 (Asia/Irkutsk), HEAD
`37eb6a0714f5e08ecad067403280ce2eff2934f4`, ветка `docs/quality`.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).
Полностью рассмотрен [KNOWN-ISSUES EN](../../../KNOWN-ISSUES.md), создана
[RU-пара](../../../ru/KNOWN-ISSUES.md), точечно исправлена навигация docs index.

Применены code-documenter, java-code-review, architecture-review,
multi-module-maven, domain-driven-design и clean-code: проверены действующие
контракты, null/failure/concurrency boundaries, identity/authority, направления
зависимостей и различие подтверждённого дефекта и необязательного расширения.
Это аудит реестра, не проектирование новых агрегатов/адаптеров и не реализация
исправлений. Полный аудит security-документов остаётся отложенным пользователем.

На входе сохранены 374 Markdown hashes и строки двух inventories. Пакеты H–M,
их свидетельства, source/POM/tooling/test code и исторические records не менялись.

## Полнота и решения по каждому ID

[known-issues-review.tsv](known-issues-review.tsv) содержит **все 84 исходных ID**,
прежнее и текущее состояния, решение, живые источники и границу evidence.
Новых ID нет, удалённых или повторно использованных нет. Карта связывает записи
со **110 файлами**: реализациями, конфигурацией, POM, тестами и owning docs.
Hashes этих файлов сохранены локально. Наличие тестового файла в карте не
выдаётся за новый запуск каждого suite: фактический отбор указан ниже.

| Текущее состояние | Записей | Значение |
|---|---:|---|
| `open` | 11 | Дефект или принятая задача реализации остаётся |
| `partial` | 10 | Выполненная часть отделена от открытого остатка |
| `qualification` | 3 | Нужна названная приёмка/целевая проверка |
| `deferred` | 26 | Сознательные улучшения с явным триггером |
| `closed` | 33 | Исходный объём закрыт; соседние ограничения не присоединены |
| `out-of-scope` | 1 | Tail-режим дискретных документов исключён |
| **Всего** | **84** | Состояния не являются релизным расписанием |

Только **OPS-1** переведён из открытого в закрытый по исходному объёму проверки
нескольких/новых файлов: сохранённый завершённый intake 100 DOCX и текущие
running-daemon/watch тесты покрывают этот запрос. Новый прогон 100 документов
не выполнялся. Это не подтверждение multi-endpoint safety, другой серверной
семьи, SLO или всех режимов WatchService. OPS-11/12 остаются открытыми.

ING-17, OBS-7/8/9/10, ARCH-4 и OPS-9 уже были закрыты; они только перенесены из
основного backlog в архив. ING-2 перенесён туда как out-of-scope. Формулировки
архива сокращены до исходного результата, границ и проверяемых references;
исторические release records и неуспешные нагрузочные окна не переписаны.

## Существенные уточнения

| ID аудита | Пункты | Исправленный дрейф |
|---|---|---|
| `DOC-KI01` | Все 84 | Разделены дефекты, missing qualification, частичная реализация, trigger-deferred options и архив. Удалены неподтверждённые текущие сроки/effort как расписание; стабильные ID, владельцы и критерии сохранены |
| `DOC-KI02` | ING-11/13 | Учтены durable admission, retry state, receipts и различные pre-reservation/pre-hash окна. Receipt replay не объявлен полным cross-ledger resume; blocked ownership/health не смешан с повторным generic poll log |
| `DOC-KI03` | SRC-2, ING-15/16, OUT-4 | Удалено устаревшее описание BodyContentHandler(-1); имеющиеся streamed/workspace/source bounds отделены от общего parser/time budget. Реализованная clock coordination и расширенные успешные load profiles не закрывают оставшиеся cause/resource obligations |
| `DOC-KI04` | OUT-1, EXT-1/2/3 | NULL уточнён как shipped metadata defaults; constants/import и lifecycle time не смешаны. Для новых типов недостаточно regex. Существующие корпуса учтены, остаток onion/provider semantics явно указан |
| `DOC-KI05` | OBS-6, ARCH-1/2/3, OPS-4/6 | Принятый strict logging contract и небольшие module boundaries не названы непременно ошибочными. Outbox/Modulith/hysteresis/parallel fetch требуют триггера; blanket parallel-safe утверждение снято с учётом OPS-11/12 |
| `DOC-KI06` | TOOL-2/3/4/5/6/8/9 | SAST не описан как ещё не реализованный; существующие fixtures/runtime/load учтены. Удалён устаревший mutation universe 21. Compatibility scope расширен до всех шести admitted libraries. Protected configuration evidence и техническая независимость версий не объявлены завершёнными |
| `DOC-KI07` | OPS-1 и закрытые записи | Исходная верификация OPS-1 архивирована с ограниченной свежестью; ранее закрытые записи убраны из активных разделов без повторного «закрытия» |
| `DOC-KI08` | Навигация/язык/источники | Canonical English, полный RU, 94 общих explicit anchors, включая прежние section targets. Убраны ссылки published registry на worknotes и несуществующие старые dev paths. KNOWN-ISSUES получил собственный source set вместо ошибочного execution ownership |

Архитектурный вывод ограничен проверенным: canonical identity/ordered-field
authority принадлежат dataframe/application контрактам, адаптеры не получают
новой domain authority. Малый модуль и длинный dependency list требуют контекста,
а не автоматического merge/parameter-object. Новая DDD-модель не внедрялась.

## Свежие проверки

Evidence сохранено в `.dev/r030-doc-01b5/`.

- `make test-module MODULE=core/ioc-application`: **883 unit/contract tests**,
  110 suites, без failures/errors/skips. `make test-module
  MODULE=adapters/adapter-events-spring`: **899**, 111 suites, также PASS.
  Второй отбор включает первый; эти числа не суммируются как разные тесты.
- `make test-one MODULE=bootstrap/ioc-app TEST=ArchitectureTest`: **34 PASS**;
  `PatternEngineConfigurationTest`: **4 PASS**; итоговый
  `DocumentationConventionTest`: **1 PASS**. Вместе с отбором выше —
  **938 различных unit/contract tests в 114 suites**.
- `DaemonIngestE2EIT`: **3 PASS**, `LoggingMigrationIT`: **5 PASS**,
  `SpringControlEventPublisherIT`: **4 PASS** — **12 IT / 3 suites**.
  Реального SMB-сервера или нового нагрузочного профиля эти тесты не квалифицируют.
- OPS-11/12 заново воспроизведены текущими production classes с реальным SQLite
  и детерминированным transport double: второй равный tuple пропущен без GET;
  два FETCHED при одном inbox-файле. Приватный fresh workspace, timed coordination,
  owned workers завершены. Это подтверждённые отказы, не положительный результат
  безопасности fetch.
- OPS-13 заново воспроизведён на текущем source segment: semantic rejection
  оставил новые unit/helpers, прежние current/DB и сохранённый backup unit.
  Privileges/systemd подменены; установленный сервис не использован.
- OPS-10/14 и TOOL-10 сверены с текущими неизменёнными реализациями и исходными
  CLI/daemon/smoke свидетельствами предыдущих пакетов. Новый replay/load для них
  не заявлен, source fixes не выполнены.
- Actions прочитан через GitHub connector, GET только. Первые 100 запусков
  охватывают период до 2026-09-22; в них 10 publication runs, последний —
  2026-10-25. Нового результата configuration recovery не найдено. Исходный
  запрос к workflow collection был отклонён connector URL allowlist; чтение
  разрешённой run collection прошло. Запуски/согласования не создавались.
- OSV querybatch обновлён 2026-11-07T13:20:05Z по тем же **171 external
  coordinates** exact retained JAR SHA-256
  `cc6907fcb669f33b76f56d84016e360abbcb8fd6ca0b8f6c66853b5f1508fe21`:
  **11 advisory IDs / 7 coordinates**. Сам JAR повторно сверён по hash.
  Три cited GitHub advisory pages прочитаны заново. Screen подтверждает версии,
  не reachability или полный release security gate; SRC-3 открыт.
- EN/RU semantic review и `check-pair.py`: **84 IDs**, **94 одинаковых anchors**,
  **115 нормализованных links на язык**, **28 local fragments**, одинаковые
  heading levels/CLI flags; published registry не ссылается на worknotes/guidance.

Первый DocumentationConventionTest **не прошёл** из-за оставленного локального
Maven settings example предыдущего пакета: `.dev/r030-doc-02m/settings-fixture/pom.xml`
попал в filesystem-wide проверку модулей без README. Пример сохранён как
`pom.example.xml`, executable reactor/test policy не менялись; повтор после
этой очистки прошёл. Исходные failure log/XML сохранены, это не flaky retry.
Unit logs сохранены; полный XML первого 883-test отбора не удалось сохранить
до очистки отчётов следующим lifecycle. Повторяющий его 899-test отбор сохранён
полностью вместе с отдельными focused XML; подсчёт уникальных tests проверен.

Финальные проверки:

- `make docs`: **3279 total / 747 unique / 3004 OK / 0 errors / 275 excluded**.
  Local fragments проверены отдельно; исключённые внешние адреса не считаются
  проверкой доступности реестров или стендов.
- Inventory: **376 Markdown paths / 65 source sets / 51 missing RU pairs** —
  только 2 отложенных root security docs и 49 ADR. KNOWN-ISSUES теперь paired
  и имеет собственный source set; все текущие/base hashes проверены.
- **370 входных Markdown paths** вне четырёх выбранных existing documents
  сохранены побайтово. Все прежние source-set rows неизменны, новый `known-issues`
  добавлен отдельно. Per-ID TSV: 84 unique rows, 110 existing source paths/hashes.
- `git diff --check`: **PASS**. Production/test Java, POM, Makefile, tools,
  workflows, packaging, skills, ADRs, generated catalogs, historical release
  records и security policies неизменны. Index пуст; guidance/handoff ignored.
  Коммит, push и privileged actions не выполнялись.

## Границы завершения

Закрыт документный аудит реестра, а не перечисленные source/security defects.
Security policy/threat model остаются отдельным пользовательским комплектом;
README/ADR/execution миграции тоже не завершены. Критерии TOOL-4/5/6 не приняты
за уже внедрённые механизмы; TOOL-7/8/9/10 остаются открытыми в своих состояниях.

Новых подписи/публикации/deployment, live server-family/load acceptance,
security suppression или source fixes нет. Полные verify/PMD не запускались:
их прежние PASS относятся к `191e74146dddeeab36bf59d9d09020a92bef0620`,
`verify.fresh=false`, `pmd.fresh=false`. Исторический baseline SHA не менялся.
