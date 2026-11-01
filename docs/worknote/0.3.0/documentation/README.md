---
title: "R030-DOC — Инвентаризация документации"
version: "0.3.0"
goal_id: "R030-DOC"
status: "In progress"
document_type: "Execution inventory"
source_of_truth: false
language: "ru"
---

# R030-DOC — Инвентаризация и пакеты исправлений

Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).
Исходная точка — `2c76d8b095a2162c7038c64de896b244517cf4c7`, ветка
`docs/quality`, чистое дерево. Дата среза: 2026-11-01, Asia/Irkutsk.

## Что покрывает срез

[inventory.tsv](inventory.tsv) учитывает каждый Markdown-файл из
`git ls-files '*.md'` исходного HEAD, а также новые Markdown-файлы этого пакета.
Исключений для co-located README, release notes, переходных страниц и worknotes нет.
Локальные ignored `AGENTS.md`, handoff, `.dev/` и build output в census не входят.

[source-sets.tsv](source-sets.tsv) связывает строки с владельцами возможностей,
живыми POM/config/source и существующими проверками. Пути в TSV относительны
корню репозитория; несколько значений разделены `;`. Владельцы указаны по
ответственности, без назначения новых персональных обязательств.

Срез содержит 329 исходных Markdown-файлов и три новых: эту заметку,
[evidence.md](evidence.md) и русский перевод `BOUNDARIES.md`.
Всего 332 строки и 61 набор источников. Сам inventory и карта источников — TSV,
поэтому они не увеличивают Markdown denominator.

| Роль | Исходных файлов | После первого пакета |
|---|---:|---:|
| README вне worknotes | 127 | 127 |
| Root maps/rules, без README и generated catalogs | 10 | 11 |
| Capability docs, включая шаблон | 16 | 16 |
| ADR, без README | 49 | 49 |
| Руководства, без README и переходных страниц | 35 | 35 |
| Переходные страницы старых guide paths | 6 | 6 |
| Generated catalogs | 2 | 2 |
| Release records/template, без README | 16 | 16 |
| Worknotes, включая их README | 68 | 70 |
| **Всего** | **329** | **332** |

## Как читать реестр

- `path`, `bucket`, `owner` — файл, его роль и владелец возможности.
- `source_set` — ключ в карте источников. Для package README `local_scope`
  дополнительно задаёт собственный каталог, а не только весь модуль.
- `language_screen` — предварительный языковой сигнал после удаления fenced
  blocks, inline code, link destinations и обычных EN/RU navigation labels.
  `ru-prose` означает заметную кириллицу в оставшемся тексте; `en-screen` означает
  её отсутствие. Это не автоматическое заключение о языке и не оценка качества.
- `canonical`, `russian`, `translation_policy` — целевые пути и правило миграции.
  Для старой переходной страницы canonical указывает поддерживаемый guide.
- `parity` отдельно различает отсутствующую пару, непроверенную прозу,
  совпадающие исполняемые блоки и расхождение блоков. Совпадение блоков не
  подтверждает корректность выполнения или смысловой перевод.
- `review` показывает фактическую глубину проверки. Большинство строк имеют
  `mapped-not-semantically-reviewed`: файл учтён и источники найдены, но все его
  утверждения ещё не проверены. У `RELEASE-PROCESS.md` проверен только изменённый
  раздел публикации; документ целиком не помечен завершённым.
- `findings`, `batch` задают находки и следующий пакет.
- `base_sha256` фиксирует байты файла в исходном HEAD; `sha256` фиксирует байты
  текущего среза. Для новых файлов исходный hash равен `-`.

Команды в карте источников — план проверок, а не отчёт об их выполнении.
Названия каталогов тестов обозначают область чтения; они не утверждают, что
каждый suite уже просмотрен. Фактические запуски записаны в [evidence](evidence.md).
Javadoc всего публичного Java API в этом срезе не измерялся.

## Находки и решения

| ID | Приоритет | Наблюдение и последствия | Решение / пакет |
|---|---|---|---|
| `DOC-F01` | M | `BOUNDARIES.md` описывал старые 24 source roots / 30 projects и report-only PMD/CPD; это скрывало действующие blocking ratchets | Исправлено в `DOC-01A`: EN/RU-пара, явные правила отказа, ссылки на реестры вместо второй копии чисел |
| `DOC-F02` | M | Последний раздел `RELEASE-PROCESS.md` перечислял четыре селектора вместо шести; не объяснял core-first admission и сохранение исходного core | Исправлено в `DOC-01A`: шесть селекторов, exact-version events family, immutable recovery, явные TOOL-8/9 boundaries |
| `DOC-F03` | M | Модульная матрица учитывала 20 из 26 функциональных JAR; старые `planned` не отражают выполненные позднее slices | В матрицу добавлены шесть отсутствовавших модулей со статусом `planned`; содержательная сверка и закрытие cells остаются `DOC-05` и owning goals |
| `DOC-F04` | M | Ряд README содержит русскую или смешанную прозу; механическая кириллица также встречается в разрешённой навигации и примерах | `DOC-04`: проверить prose, перевести README на месте, сверить package/module map и зависимости |
| `DOC-F05` | M | `docs/guides/ru/README.md`, `docs/guides/ru/libraries/README.md` и `.github/release-notes/ru/README.md` являются русскими README | `DOC-04`: сохранить русский навигационный контент в подходящей странице, а прежние README paths — как английские точки входа; guides не удалять |
| `DOC-F06` | M | Перенос русского ADR в `ru/` меняет базу его относительных ссылок; побайтовое сохранение запрещает исправлять оригинал внутри файла | `DOC-03`: сначала план разрешения ссылок и проверка будущих targets, затем перенос с hash equality и явно помеченный исторический EN-перевод. Массовый перенос сейчас не выполнен |
| `DOC-F07` | M | Отсутствуют 77 обязательных RU-пар: 9 root docs, 16 capability docs, 49 ADR и 3 guides; существующая пара сама по себе не доказывает semantic parity | `DOC-01B/02/03`: проверить смысл, создать пары и навигацию; список отсутствующих paths берётся из TSV |
| `DOC-F08` | L | Часть исторического execution bundle написана на английском, хотя целевой контракт задаёт русские worknotes | `DOC-05`: отдельно определить обращение с исторической прозой; новые execution notes пишутся по-русски. Исторические замеры и результаты не переписывать под текущий HEAD |
| `DOC-F09` | L | EN/RU `remote-storage-sync.md` отличаются в двух YAML-блоках переведёнными комментариями; значения и остальные блоки совпадают | `DOC-02`: привести комментарии к English и сохранить семантику. Это языковой drift, а не доказанный дефект конфигурации |

### Границы известных открытых обязательств

`R030-SEC` и `R030-REL` остаются открытыми. Зелёные links не закрывают
security/release readiness. `ING-11`, полный `ING-13` и ресурсная приёмка `OUT-4`
требуют owning evidence/disposition; этот пакет не реализует их.
`OPS-8` остаётся явно отложенной target-deployment qualification.
TOOL-4/5/6/7/8/9 сохраняют свои triggers и критерии закрытия.

Все шесть cataloged библиотек имеют записи публикаций `0.3.0-rc.1`; это не
квалификация нового HEAD или второго развёрнутого сервиса. TOOL-8 нельзя закрыть
локальным consumer, TOOL-9 — принятым ADR. Исторический baseline остаётся
измерением исходной системы, а текущие числа реактора берутся из живого POM.

## Пакеты и критерии приёмки

| Work item | Состояние | Результат и проверка |
|---|---|---|
| `DOC-INV-01` | `verified` | Все исходные Markdown paths учтены ровно один раз; источник, владелец, перевод, review state, hashes и очередь определены; ссылки реестра проверены |
| `DOC-01A` | `verified` | Исправлены карта boundary/build gates и раздел library publication; EN/RU parity для BOUNDARIES проверена; architecture/convention/publication tests и links прошли |
| `DOC-01B` | `planned` | Остальные root maps/rules: утверждения сверены с POM/Make/CI/config, canonical EN и RU, vocabulary/navigation согласованы |
| `DOC-02` | `planned` | Capability docs и guides: границы, failure/recovery/defaults и примеры сверены с owning source/tests; пары и runnable examples проверены |
| `DOC-03` | `planned` | ADR: original hashes сохранены, migration link plan выполнен, historical EN labels/status/number и semantic parity проверены |
| `DOC-04` | `planned` | README: English-only, корректные package/module references, русская навигация сохранена, прежние targets разрешаются |
| `DOC-05` | `planned` | Сверка matrix/ledgers/release notes с фактическими evidence; явные dispositions, неизменность publication identities и исторических замеров |

Каждый пакет получает focused checks по изменённому контракту. `make docs`
проверяет локальные links; смысл, язык и parity проверяются отдельно. Изменения
Java/POM/analyzer scope требуют focused tests, `make verify` и `make pmd-analysis`.
Финальный `R030-REL` требует gates на точном release candidate.

## Сопровождение inventory

Перед следующим пакетом обновить затронутые строки, hashes, findings и evidence.
Не превращать `mapped` в `verified` по одному факту наличия ссылок или тестов.
При добавлении/удалении Markdown-файла обновить census и точный набор `path`.
Контрольный исходный набор воспроизводится командой:

```bash
git ls-tree -r --name-only 2c76d8b095a2162c7038c64de896b244517cf4c7 | rg '\.md$'
```

Текущий срез является reviewable данными, а не новым постоянным генератором docs.
При повторной сверке проверить SHA-256 файлов и существование всех путей из
`live_sources` / `verification_sources`. Generated catalogs изменяются только
через их генераторы и doc-sync tests.
