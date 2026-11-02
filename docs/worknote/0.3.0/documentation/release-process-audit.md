---
title: "DOC-01B.4 — Общие правила релизов и release notes"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified for DOC-01B.4"
document_type: "Audit and verification evidence"
source_of_truth: false
language: "ru"
---

# DOC-01B.4 — Общий релизный процесс, записи и шаблон

Дата: 2026-11-02, Asia/Irkutsk. HEAD:
`5edd3af3a955c7cdd087e8b5917176c58285ae9b`, ветка `docs/quality`.
На входе сохранены незакоммиченные `DOC-01B.1/2/3`.
Контракт — [R030-DOC](../goals/R030-DOC-documentation.md).

Пользователь явно определил RELEASE-PROCESS как общую политику предрелизных
процессов проекта, а не план текущей версии. Применён code-documenter.
Scope: RELEASE-PROCESS EN/RU, release-notes README и TEMPLATE, навигация и
инвентаризация. Локальный AGENTS дополнен по прямому разрешению пользователя.
SECURITY-ENGINEERING и THREAT-MODEL отложены пользователем для отдельного
разбора; их политика используется как вход, сами документы не изменялись.

## Находки и решения

| ID | Наблюдение | Результат |
|---|---|---|
| `DOC-R01` | Общая политика содержала реальные версии, количество опубликованных библиотек и конкретные recovery/status incidents | Добавлена явная карта владения policy/notes/evidence/tooling; из общей политики убраны конкретные release versions, результаты и изменяемый component inventory. Исторические записи не переписаны |
| `DOC-R02` | Независимое владение библиотеками, shared Maven implementation и общереакторный DoD могли читаться как одна схема | Сохранены ADR 0048, exact-version family и критерии активации; POM fragments и глобальный override явно относятся к shared mode. Активный режим и статус перехода принадлежат tooling/publication guide. DoD определяет источник версии продукта/семейства, не обещает уже реализованную независимость |
| `DOC-R03` | README называл каждый release file именем tag, хотя component records используют artifact/version и не application template | Разделены application notes из tagged source и component publication records; область структурного теста описана точно |
| `DOC-R04` | Source-level, binary/stand evidence и «build once» не объясняли различие development builds и продвигаемого комплекта | Идентичность связывает source → bundle → publication → activation; новые байты требуют собственного binary evidence. Повторная сборка не получает автоматически прежнюю квалификацию или статус recovery |
| `DOC-R05` | Minimum quality gate показывал только Maven verify/diff; policy/manual/external требования могли смешиваться с тем, что исполняет tag workflow | Добавлены принятые verify/PMD/docs/packaging controls и owning links. Source/tag admission принадлежит workflow, draft helper — supplied files/notes/checksum. Разрешённый disposition не превращает failed blocking gate в pass |
| `DOC-R06` | Template по умолчанию объявлял observable changes неприменимыми; upgrade/verification guidance недостаточно разделяли автоматические и ручные действия | Сохранены обязательные headings; нужен явный текст или осмысленный Not applicable, backup/recovery unit, поддерживаемые layout/версии, post-upgrade checks, actual passed/failed/unavailable/deferred scopes |
| `DOC-R07` | Rollback мог читаться как переключение binary либо полное обращение внешних эффектов | Общая политика требует квалификации заявленного пути и согласованной application/config/unit/DB recovery point; автоматизация ограничена документированным scope, remote effects и перемещённые файлы требуют отдельного согласования |
| `DOC-R08` | Отсутствовала обязательная RU-пара RELEASE-PROCESS | Создан содержательный перевод, сохранены canonical anchors и literal blocks; README/template остаются English-only |
| `DOC-R09` | В локальных инструкциях не было явной границы общего процесса и кандидата | Ссылка и пояснение добавлены в Required reading AGENTS в начале файла, до обычного лимита загрузки; CLAUDE использует тот же symlink |

## Привязка к живым источникам

Source set `release` в [source-sets.tsv](source-sets.tsv) содержит точные пути.
Значимые соответствия:

| Утверждение | Источник | Ограничение свидетельства |
|---|---|---|
| Shared revision / parent / effective managed dependencies | Root/child POM; MavenVersionConventionTest | Три проверки подтверждают действующий режим; независимая версия не активирована правкой документа |
| Packaged build information / ECS filtering | Bootstrap POM, application YAML; BuildInfoContractTest; prepare-release-artifacts.sh | Generated metadata сверены; это не новая квалификация публикуемого JAR/host |
| Tag subset, annotation, exact source/version и build-only/draft modes | release.yml; prepare-release-artifacts.sh | Workflow проверяет annotation/commit для promotion. Helper сам по себе не доказывает source admission; external branch/tag settings не проверялись |
| Draft assets, no clobber, published read-only | publish-release-draft.sh; packaging contracts | Существующие байты сравниваются, недостающие добавляются только draft; никакие реальные releases не создавались |
| Product notes structure vs semantic review | ReleaseNotesContractTest; TEMPLATE/README | Девять обязательных H2/order, title/version и известные unfinished markers; не автоматический review совместимости/операторского смысла |
| Generic library admission / signed recovery / selected origin | library-publication workflow/tooling и offline tests; ADR 0028/0029/0048 | Сохранены точные core/adapter и immutable identities; offline fixtures не являются новым Central/GitHub repository evidence |
| Deployment / recovery boundary | install.sh, deploy-local.sh, deploy-local-root.sh, packaging README/contracts | Source/contract review; live systemd activation/rollback не запускались. Packaging README целиком здесь не аудирован |

## Границы общего документа

RELEASE-PROCESS не содержит версий текущего/прошлого кандидата и его blockers,
run IDs, результатов публикации или progress checklist. Синтаксис X.Y.Z и
правила 0.x/1.0 остаются частью общей version policy. Названия owning files,
постоянные роли workflow и режимы admitted tooling допустимы как ссылки на
исполняемый механизм, без выдачи принятой политики за выполненную миграцию.

Application notes и library records имеют разные consumers и naming contracts.
Уточнённый шаблон остаётся прикладным шаблоном приложения; component records
не принуждаются к заголовку IOC Extractor или его обязательным разделам.
Исторический v0.1.0 import остаётся исключением owning reference/test; его факт
не становится частной веткой общего процесса. Все прежние records сохранены.

AGENTS остаётся ignored локальной инструкцией, не published link target и
не частью Git-коммита. Policy/record граница закреплена одной ссылкой с
пояснением, без второй копии общей политики.

## Проверки

| Проверка | Результат |
|---|---|
| `make doctor-core` | Пройдено, Java 21 |
| Focused Maven: ReleaseNotesContractTest, BuildInfoContractTest, MavenVersionConventionTest | 6 tests, 0 failures/errors/skips |
| `make lint-shell` | ShellCheck, packaging contracts и tools contracts — PASS |
| `make library-publication-test` | 228 offline tests — PASS; реальные публикации/consumer repository queries не запускались |
| EN/RU | 28 headings, 6 literal blocks; identifiers и нормализованные targets совпадают, выполнен semantic review |
| Примеры | XML fragments сверены с актуальной POM shape; четыре Make dry-runs; literal checksum command принимает верный и отклоняет испорченный synthetic artifact |
| Общий scope политики | Проверено отсутствие concrete release versions в EN/RU; прошлые источники и исторические records сохранены |
| Inventory / protected files | 344 уникальных Markdown paths, 64 source sets; current/base hashes и source paths сверены; Java/POM/scripts/workflows, historical records, ADR/catalogs/baseline и отложенные security docs неизменны |
| `make docs` | 2237 occurrences, 607 unique, 2017 OK, 0 errors, 220 excluded; offline gate |
| `git diff --check` / новые Markdown | Пройдено; новые файлы отдельно проверены на trailing whitespace |

## Состояние

После пакета: 344 Markdown paths, 64 source sets; отсутствуют 69 обязательных
RU-пар: 3 root docs, 14 capability docs, 49 ADR и 3 guides.
SECURITY-ENGINEERING/THREAT-MODEL остаются отложенными для отдельного комплекта,
а не исключёнными из обязательного перевода. Остальной DOC-05 не закрывается
review правил и шаблона release notes.

Production/test Java, POM, scripts/workflows, analyzer/coverage policy, ADR,
generated catalogs и прежние release records не менялись. Полные verify/PMD
не перезапускались: последнее успешное evidence относится к
`191e74146dddeeab36bf59d9d09020a92bef0620`, freshness=false. Этот пакет не
квалифицирует текущий release candidate и не закрывает R030-REL/R030-SEC.
