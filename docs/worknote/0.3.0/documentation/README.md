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
`docs/quality`, чистое дерево. Исходный census — 2026-11-01;
текущий inventory обновлён 2026-11-06 в `DOC-02G` (Asia/Irkutsk).

## Что покрывает срез

[inventory.tsv](inventory.tsv) учитывает каждый Markdown-файл из
`git ls-files '*.md'` исходного HEAD, а также новые Markdown-файлы этого пакета.
Исключений для co-located README, release notes, переходных страниц и worknotes нет.
Локальные ignored `AGENTS.md`, handoff, `.dev/` и build output в census не входят.

[source-sets.tsv](source-sets.tsv) связывает строки с владельцами возможностей,
живыми POM/config/source и существующими проверками. Пути в TSV относительны
корню репозитория; несколько значений разделены `;`. Владельцы указаны по
ответственности, без назначения новых персональных обязательств.

Срез содержит 329 исходных Markdown-файлов и тридцать пять новых: эту заметку,
[evidence.md](evidence.md), [архитектурный аудит](architecture-audit.md),
[аудит конвенций](conventions-audit.md), [аудит тестирования](testing-quality-audit.md),
[аудит общих релизных правил](release-process-audit.md),
[аудит конфигурации](configuration-audit.md),
[аудит обработки/CSV](processing-csv-audit.md),
[аудит хранения/lifecycle/export](storage-lifecycle-export-audit.md),
[аудит managed import](dataframe-import-audit.md),
[аудит ingestion/daemon](ingestion-daemon-audit.md),
[аудит sync/координации](sync-coordination-audit.md),
[аудит observability](observability-audit.md), семь RU-переводов root maps
(`BOUNDARIES`, `PRINCIPLES`, `ARCHITECTURE`, `MODULARIZATION`, `CONVENTIONS`, `TESTING`, `RELEASE-PROCESS`)
и RU-переводы capability template/build-quality/configuration/configuration-failure-reports/processing/csv-output-format
и operator guide ioc-processing-routes, а также storage/canonical-record-lifecycle/artifact-export/dataframe-import/ingestion/sync/event-coordination/observability.
Всего 364 строки и 64 набора источников. Сам inventory и карта источников — TSV,
поэтому они не увеличивают Markdown denominator.

| Роль | Исходных файлов | После DOC-02G |
|---|---:|---:|
| README вне worknotes | 127 | 127 |
| Root maps/rules, без README и generated catalogs | 10 | 17 |
| Capability docs, включая шаблон | 16 | 30 |
| ADR, без README | 49 | 49 |
| Руководства, без README и переходных страниц | 35 | 36 |
| Переходные страницы старых guide paths | 6 | 6 |
| Generated catalogs | 2 | 2 |
| Release records/template, без README | 16 | 16 |
| Worknotes, включая их README | 68 | 81 |
| **Всего** | **329** | **364** |

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
  утверждения ещё не проверены. Завершённые пакеты имеют отдельный review state;
  проверка навигации или части документа не подтверждает весь связанный комплект.
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
| `DOC-F07` | M | После DOC-02G отсутствуют 56 обязательных RU-пар: 3 root docs, 2 capability docs, 49 ADR и 2 guides; существующая пара сама по себе не доказывает semantic parity | `DOC-01B/02/03`: проверить смысл, создать пары и навигацию; список отсутствующих paths берётся из TSV |
| `DOC-F08` | L | Часть исторического execution bundle написана на английском, хотя целевой контракт задаёт русские worknotes | `DOC-05`: отдельно определить обращение с исторической прозой; новые execution notes пишутся по-русски. Исторические замеры и результаты не переписывать под текущий HEAD |
| `DOC-F09` | L | EN/RU `remote-storage-sync.md` отличаются в двух YAML-блоках переведёнными комментариями; значения и остальные блоки совпадают | Устранено в `DOC-02F`: общий executable YAML и точные EN/RU literal blocks; исходный finding был языковым drift, source defects отдельно записаны OPS-11/12 |

Находки `DOC-A01..10`, проверка полноты и привязка к источникам архитектурного
комплекта зафиксированы в [DOC-01B.1](architecture-audit.md). Проверены шесть
страниц EN/RU; эти результаты не распространяются на другие root docs.

Находки `DOC-C01..07`, требования к содержанию/стилю Javadoc и обновление
шаблона возможности отражены в [DOC-01B.2](conventions-audit.md). Проверены
четыре страницы EN/RU и буквальный Java-пример; общий аудит Javadoc ещё не выполнен.

Находки `DOC-T01..09`, явная политика coverage quality gate и поведенческого
качества тестов описаны в [DOC-01B.3](testing-quality-audit.md). Связанный комплект
TESTING/build-quality начинает содержательный review capability docs в DOC-02;
другие тестовые классы и документы не получают verified автоматически.

Находки `DOC-R01..09` и граница общей release policy / конкретных records
зафиксированы в [DOC-01B.4](release-process-audit.md). SECURITY-ENGINEERING и
THREAT-MODEL пользователь отложил для отдельного комплекта; это не исключение
из R030-DOC и не изменение состояния R030-SEC.

Находки `DOC-CFG01..11`, проверка фактов, полноты, EN/RU и команд
зафиксированы в [DOC-02A](configuration-audit.md). Проверены configuration,
configuration-failure-reports и операторский справочник; это не полный аудит
bootstrap/packaging README или guides общей библиотеки конфигурации.

Находки `DOC-P01..11`, проверка полноты и documented examples отражены в
[DOC-02B](processing-csv-audit.md): processing, маршрутизация и CSV EN/RU.
Два локальных устаревших Javadoc отмечены для последующего API/module review;
source Java в этом пакете не меняется. Предыдущий DOC-02A сохранён.

Находки `DOC-S01..11` и четыре EN/RU-пары отражены в
[DOC-02C](storage-lifecycle-export-audit.md): storage, lifecycle dev/guide и
artifact-export. Сверены rollback, active reads/projection convergence и export
checkpoints. Реальный lifecycle smoke выявил дрейф terminal filename;
`TOOL-10` открыт, smoke/load acceptance не заявляется. KNOWN-ISSUES дополнен
только этой записью; его полный аудит остаётся в DOC-01B.

Находки `DOC-I01..12`, две EN/RU-пары и documented commands отражены в
[DOC-02D](dataframe-import-audit.md). Разделены advisory preview и CLI startup,
merge/identity/TTL, retention/replay и live SMB qualification. Реальный status
после replay выявил nullable-read дефект OPS-10; command block не объявляется
зелёным. KNOWN-ISSUES дополнен только этой записью, TOOL-10 сохранён.

Находки `DOC-N01..12` отражены в [DOC-02E](ingestion-daemon-audit.md).
Две EN/RU-пары ingestion/runbook согласованы по polling/claim, ordered promotion,
partial commit, blocked head, health exit codes и coordinated backup. Проверены
79 unit + 66 IT, documentation contract и sandbox commands; системный service,
live SMB и backup/restore на стенде не запускались. Все 29 содержательных входных
документов/records предыдущих пакетов сохранены побайтово.

Находки `DOC-S01..16` отражены в [DOC-02F](sync-coordination-audit.md).
Три EN/RU-пары sync/coordination/SMB guide согласованы по producer handoff,
identity/landing, marker/retention, retry, CLI и event/backstop limits.
195 unit + 31 executed IT / 3 external skips, 13 documentation/selector checks,
12 CLI JVM и semantic YAML прошли. Server/Windows execution не квалифицированы.
Реальные SQLite/use-case reproductions выявили два H-дефекта: OPS-11 пропускает
второй endpoint по общей identity, OPS-12 заменяет inbox source при гонке landing.
Source fixes остаются отдельной работой; KNOWN-ISSUES дополнен ровно этими строками.

Находки `DOC-O01..14` отражены в [DOC-02G](observability-audit.md).
Четыре EN/RU-пары observability/diagnostics/logging/Boot согласованы. Уточнены
retention против delivery, full-summary completion, observer/Error и disclosure
boundaries, profile/destination и различия strict recipe/IOC encoder.
294 unit + 14 backend IT, 12 checks, 228 publication contracts, 10 jq calls и
три cold Central consumers опубликованных 0.3.0-rc.1 прошли. Java/XML/YAML examples
сохранены; 22/12 Java files совпадают с original published sources. F и его
OPS-11/12 сохранены; global security/deployment/collector/load не квалифицированы.

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
| `DOC-01B` | `in-progress` | Архитектурный комплект, CONVENTIONS, TESTING и RELEASE-PROCESS завершены; KNOWN-ISSUES остаётся, security docs отложены пользователем для отдельного комплекта |
| `DOC-01B.1` | `verified` | PRINCIPLES/ARCHITECTURE/MODULARIZATION: точность и полнота, три RU-перевода, навигация, 61 focused test и parity review; [аудит и проверки](architecture-audit.md) |
| `DOC-01B.2` | `verified` | CONVENTIONS и capability template EN/RU: содержательный Javadoc, единый стиль и распределение документации; literal Java compile/doclint, 1 convention test, parity, inventory и links прошли; [аудит и проверки](conventions-audit.md) |
| `DOC-01B.3` | `verified` | TESTING/build-quality EN/RU: policy/mechanism coverage, полезные тесты, текущие области и admission; 147 synthetic scenarios, 4 lifecycle tests, tools contracts, 12 dry-runs, parity/inventory/links прошли; [аудит и проверки](testing-quality-audit.md) |
| `DOC-01B.4` | `verified` | Общий RELEASE-PROCESS EN/RU, release-notes README/template, policy/evidence ownership; 6 contract tests, 228 offline publication tests, packaging/tools, examples/parity/inventory/links прошли; [аудит и проверки](release-process-audit.md) |
| `DOC-02` | `in-progress` | build-quality проверен в связанном `DOC-01B.3`, конфигурационный комплект — в `DOC-02A`, обработка/маршрутизация/CSV — в `DOC-02B`, storage/lifecycle/export — в `DOC-02C`, managed import — в `DOC-02D`, ingestion/daemon — в `DOC-02E`, sync/координация — в `DOC-02F`, observability/libraries — в `DOC-02G`; остальные capability docs/guides ожидают сверки границ, failure/recovery/defaults, переводов и runnable examples |
| `DOC-02A` | `verified` | Configuration/configuration-failure-reports и operator reference EN/RU: факты, полнота, defaults, check/apply/recovery scope; 93 unit + 100 IT, повтор 7 documentation contracts, 12 command JVM, bash example, parity/inventory/links прошли; [аудит](configuration-audit.md) |
| `DOC-02B` | `verified` | Processing, routing guide и CSV format EN/RU: факты, полнота, выбор и commit boundaries; 234 unit + 103 IT, шесть example JVM и три CLI paths, parity/inventory/links прошли; [аудит](processing-csv-audit.md) |
| `DOC-02C` | `verified` | Storage/lifecycle dev+guide/export EN/RU: 171 unit + 104 IT, CLI/examples, parity/inventory/links; TOOL-10 открыт после реального smoke timeout, load не квалифицирован; [аудит](storage-lifecycle-export-audit.md) |
| `DOC-02D` | `verified` | Managed import dev/guide EN/RU: 212 unit + 119 executed IT / 4 external skips; isolated CLI/terminal fixtures, parity/inventory/links. OPS-10 открыт после status failure на nonterminal head; [аудит](dataframe-import-audit.md) |
| `DOC-02E` | `verified` | Ingestion и daemon runbook EN/RU: 79 unit + 66 IT, 1 documentation contract, 14 CLI JVM, 5 bash blocks, parity/inventory/links. Partial commit, blocked work, health и backup scope исправлены; live/systemd/restore не заявлены; [аудит](ingestion-daemon-audit.md) |
| `DOC-02F` | `verified-docs-open-defects` | Sync, event coordination и SMB guide EN/RU: 195 unit + 31 IT / 3 external skips, 13 checks, 12 CLI JVM, parity/inventory/links. OPS-11/12 воспроизведены; server/Windows runtime не квалифицированы; [аудит](sync-coordination-audit.md) |
| `DOC-02G` | `verified` | Observability и diagnostics/logging/Boot guides EN/RU: 294 unit + 14 IT, 12 checks, 228 contracts, 10 jq queries; три cold Central consumers 0.3.0-rc.1 и published-source equality, parity/inventory/links. Source/producer-wide security и deployment не заявлены; [аудит](observability-audit.md) |
| `DOC-03` | `planned` | ADR: original hashes сохранены, migration link plan выполнен, historical EN labels/status/number и semantic parity проверены |
| `DOC-04` | `in-progress` | Release-notes README проверен в связанном DOC-01B.4; остальные README ожидают language/reference/semantic review, с сохранением русской навигации |
| `DOC-05` | `in-progress` | Правила и шаблон release notes проверены в DOC-01B.4; содержательная сверка остальных matrix/ledgers/records и исторических measurements остаётся |

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
