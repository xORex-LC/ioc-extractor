---
title: "DOC-02M — Разработка и публикация библиотек"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation slice; tooling follow-ups remain open"
document_type: "Execution evidence"
source_of_truth: false
language: "ru"
---

# DOC-02M — Разработка и публикация библиотек

Проверка 2026-11-07 (Asia/Irkutsk), HEAD
`37eb6a0714f5e08ecad067403280ce2eff2934f4`, ветка `docs/quality`.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).
Полностью рассмотрены [разработка EN](../../../guides/libraries/development.md)
и [RU](../../../guides/ru/libraries/development.md),
[публикация EN](../../../guides/library-publication.md); создана отсутствовавшая
[RU-версия публикации](../../../guides/ru/library-publication.md).

Применён code-documenter. Формат Markdown, EN/RU и границы документации
сохранены. Навигация исправлена точечно; полный аудит или языковая миграция
связанных README не заявляются. На входе сохранены hashes 372 Markdown paths
и строки обоих TSV; H/I/J/K/L остаются без переписывания.

## Источники и полнота

Проверены живые root/шесть module POM, Makefile, publication tool и его offline
contracts, publication workflow, CI matrix, standalone fixtures и постоянные
release records шести компонентов. Сверены действующие механизмы и принятые
правила ADR 0048/RELEASE-PROCESS. Исторические records и ADR не менялись.

| Руководство | Проверенные части | Результат |
|---|---|---|
| Разработка | Выбор существующего API, service/library ownership, изменение контракта и callers, совместимость, тесты/Javadoc, versions, upgrade и описание изменения | Все разделы рассмотрены; добавлены реальные локальные команды, границы packaging и опубликованных bytes, SNAPSHOT и security/baseline qualification |
| Публикация | Шесть selectors, POM/dependency admission, local package/consumer, build/protected jobs, tag/version identity, secrets, validate/publish, core-first events, recovery, repository origin, consumption и failure cases | Все разделы рассмотрены; операции и inputs сведены в таблицы, добавлены consumer settings и troubleshooting, уточнены доверие к manifest и свежесть credential notes |
| Переводы | Смысловые обязательства и ограничения каждого раздела, navigation, commands, XML и diagram | Две полные EN/RU-пары; одинаковы семь literal blocks на язык, CLI flags и нормализованные link targets; шесть локальных fragments проверены отдельно |

Внешние правила сверены 2026-11-07 с первичными источниками:
[Central Publisher API](https://central.sonatype.org/publish/publish-portal-api/),
[GitHub Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry),
[GitHub environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments),
[workflow dispatch](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch),
[Resolver filtering](https://maven.apache.org/resolver-1.x/remote-repository-filtering.html)
и [flatten plugin](https://www.mojohaus.org/flatten-maven-plugin/flatten-mojo.html).
Реальные account/environment settings и состояние credentials этим не проверены.

## Находки и изменения

| ID | Наблюдение | Исправление |
|---|---|---|
| `DOC-PUB01` | Принятая независимость версий могла читаться как уже действующий выбор опубликованных library bytes в сервисе | Явно описаны локальный reactor/shared revision, отдельный distribution consumer и незавершённый TOOL-9 |
| `DOC-PUB02` | Локальное packaging и release qualification легко смешать; допустимость dirty/SNAPSHOT зависит от операции | Добавлен минимальный development example; свежий output, запись dirty identity, запрет release signing и границы full gates описаны явно |
| `DOC-PUB03` | `validate` можно принять за безопасную offline проверку, а signing — за часть unprivileged build | Таблица операций и разделение jobs: validate получает protected credentials и создаёт удалённый USER_MANAGED deployment |
| `DOC-PUB04` | Связи workflow inputs и различие первого adapter attempt/recovery были распределены по длинному тексту | Добавлена единая input matrix; сохранены exact core/version, receipts, immutable tag/bundle и recovery branch-tooling boundaries |
| `DOC-PUB05` | Название `check --signed` может создавать ложное ожидание повторной криптографической проверки | Указаны фактические проверки manifest/fingerprint/clean identity/hash inventory; GPG проверяет подписи при создании, recovery также требует доверенной provenance |
| `DOC-PUB06` | Исторические expiry notes могли читаться как текущая проверка доступа; event metadata использовал неточное имя поля | Отделены исторические даты от live expiry/revocation; поле исправлено на `eventVersion` |
| `DOC-PUB07` | Для обычного GitHub consumer не хватало прозрачной настройки Maven/auth и действий при отказе | Добавлены согласованные server/repository XML, env-backed credentials, PAT/GITHUB_TOKEN boundaries и десять failure/response cases |
| `DOC-PUB08` | У publication отсутствовал RU; navigation явно сообщала отсутствие перевода | Создан полный перевод, обновлены guide indexes и ссылка из RU development/library index; русский release-policy fragment проверен и исправлен |

Оба руководства сохраняют назначение: development объясняет выбор и изменение
библиотеки, publication — сборку, admission, публикацию, consumer qualification
и восстановление. Постоянные project rules остаются в RELEASE-PROCESS,
конкретные identities/results — в component records. Документный аудит не
создаёт нового правила публикации или новой независимой versioning реализации.

## Свежие проверки

Evidence сохранено локально в `.dev/r030-doc-02m/`.

- `make library-publication-test`: **228 offline contracts, PASS**. Synthetic
  Central/GitHub responses — тестовые doubles, не новые реальные публикации.
- `make test-module MODULE=platform/platform-concurrency`: **23 tests / 3 suites,
  0 failures/errors/skips**. Это выбранный модуль, не full-reactor gate.
- Реальный `make library-bundle LIBRARY_COMPONENT=concurrency
  LIBRARY_BUNDLE=.dev/r030-doc-02m/concurrency-bundle`: **PASS**, свежий unsigned
  `0.3.0-SNAPSHOT` bundle с dirty identity и четырьмя primary artifacts.
- Реальный `make library-consumer` того же bundle: **PASS**. Внешний temporary
  project, пустой private Maven cache, guide examples/consumer contracts и
  совпадение четырёх artifacts/origin. Это local repository qualification,
  не новый Central/GitHub download и не signed release.
- Шесть исходных signed `0.3.0-rc.1` bundles из прежнего evidence: **18 actual
  CLI calls** (`module`, `artifact`, `check --signed`), шесть manifest hashes
  совпали с permanent records, проверены **120 файлов / 24 primary artifacts**.
  Все **67 Java sources** совпали с current module sources. Исходные bundles
  сохранены без изменений; повторная GPG-проверка этим не заявлена.
- `check-examples.py`: **3 sh blocks** проверены `bash -n`, **16 make dry-runs**,
  два literal `check` вызова сверены с actual help/ранее проверенными bundles;
  **3 XML fragments** разобраны. Четыре исходных publication fences сохранены
  побайтово, новые blocks добавлены отдельно.
- Реальный offline Maven 3.9.9 `help:3.5.1:effective-settings` с settings из
  руководства: **PASS**. Явные private user/global settings, dummy env values,
  интерполяция username, маскированный password и совпадение server/repository
  ID проверены. Операторские settings/credentials и GitHub access не использованы.
- Две EN/RU-пары проверены по смыслу, структуре, literal blocks, flags,
  нормализованным links и локальным anchors. Первая попытка anchor check нашла
  EN fragment в RU release-policy target; ссылка исправлена, повтор прошёл.

Финальные проверки:

- `make docs`: **3094 total / 726 unique / 2822 OK / 0 errors / 272 excluded**.
  Проверка локальных fragments выполнена отдельно; исключённые внешние адреса
  не выдаются за проверенные реальные сервисы.
- Inventory: **374 paths / 64 source sets / 52 missing RU pairs** — только
  3 root docs и 49 ADR. Все **32 capability + 38 guide paths** имеют пары
  и содержательный review state. Все current/base hashes проверены; исходные
  329 baseline paths и их hashes сохранены.
- **364 входных Markdown-файла** вне восьми выбранных input paths сохранены
  побайтово; все non-M registry rows и source sets, кроме `publication`, неизменны.
  Новый audit/RU publication учтены. Предыдущие H/I/J/K/L, KNOWN-ISSUES,
  production/test/tooling/POM/history/generated/security inputs сохранены.
- `git diff --check`: **PASS**. Index пуст; ignored guidance/handoff остаются
  ignored, CLAUDE — symlink на AGENTS. Коммит и публикация не выполнялись.

## Границы результата

Подписи, теги, live validation/upload/publish, recovery dispatch и deployment
не выполнялись. Release identities не менялись. Код, POM, workflows, fixtures,
security policies и KNOWN-ISSUES сохранены. Нового source defect в этом срезе
не обнаружено; найденные ранее operational/security findings остаются открытыми.

TOOL-6/7/8/9 сохраняют собственные критерии: compatibility automation, GPG fixture
investigation, protected configuration recovery и независимые версии не закрыты
локальным consumer или переводом. Полные `verify`/PMD не запускались заново:
сохранённые PASS относятся к `191e74146dddeeab36bf59d9d09020a92bef0620`,
а не к текущему HEAD; `verify.fresh=false`, `pmd.fresh=false`.

После этого пакета все текущие обычные capability/guide docs имеют EN/RU-пары
и содержательный review state. Это завершение DOC-02 в документном scope с
открытыми source findings. Общий R030-DOC остаётся открытым: root security/registry,
ADR migration/translation, README и исторический execution bundle требуют
собственных срезов; наличие ссылок на README не подтверждает их полный аудит.
