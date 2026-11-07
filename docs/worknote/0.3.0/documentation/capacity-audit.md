---
title: "DOC-02H — Ресурсные ограничения и ёмкость обработки"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation slice"
document_type: "Execution evidence"
source_of_truth: false
language: "ru"
---

# DOC-02H — Ресурсные ограничения и ёмкость обработки

Проверка 2026-11-07 (Asia/Irkutsk), HEAD
`37eb6a0714f5e08ecad067403280ce2eff2934f4`, ветка `docs/quality`.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).

Комплект: [native-capacity](../../../dev/native-capacity.md) и
[bulk-resource-sizing](../../../guides/bulk-resource-sizing.md), две EN/RU-пары.
Английские документы уточнены и дополнены; созданы оба отсутствовавших перевода.
Применены code-documenter, java-code-review, cli-developer, spring-boot-engineer.
Формат и границы следуют CONVENTIONS и согласованному порядку аудита.

Исторические таблицы Native, их binary/source identities, условия и неудачные
screens сохранены. Их измерения повторно не выполнялись. Сверены живые defaults,
ограничения, владение ресурсами, наблюдение и поддерживаемые команды. Нагрузочная
переквалификация, изменение installed service и измерение новых SLO не входят
в этот документный срез. Java/tests/POM/scripts, generated catalogs, quality
floors, ADR/release history и security docs не менялись; коммита нет.

## Находки и решения

| ID | Приоритет / пробел | Результат |
|---|---|---|
| `DOC-CAP01` | M: reference envelope и installation defaults не имели прямой связи с текущими владельцами настроек | Сверены шаблоны YAML/unit/env, low-memory vs balanced, CPU equivalents, heap/cgroup и проверка effective limits; профиль не объявлен новой installation policy |
| `DOC-CAP02` | M: memory budget можно было принять за ограничитель всей памяти | Описаны reservation formula, три штатных lease и effective window; parser/native/RSS остаются отдельными измерениями |
| `DOC-CAP03` | M: disk/retention не раскрывали reservation и idle cleanup | Live pins резервируют полный allowance, inactive pins учитываются; pruning выполняется на open, а не таймером через 24h; recovery ownership сохранено |
| `DOC-CAP04` | M: увеличение workspace disk могло выглядеть достаточным для любого входа | Добавлены independent source/pending/import snapshot и row/field limits; oversized unclaimed input остаётся в inbox; HTML region shape имеет отдельное значение |
| `DOC-CAP05` | M: writer priority и measured hold не объясняли atomicity/latency boundary | Nonpreemptive admission, per-artifact document vs cross-artifact import, Java wait vs busy timeout, cumulative/max telemetry и failed admitted calls отделены от commit receipts |
| `DOC-CAP06` | M: WAL footprint и tuning нуждались в пояснении | Reader pin, retained journal size vs live ceiling, reusable DB space и overlap/backup headroom описаны без удаления live WAL или ослабления durability |
| `DOC-CAP07` | M: не было конкретного пути наблюдения capacity | Добавлены health component command/exit codes, карта counters, cached pressure и dataframe-only WAL; UP не подменяет backlog/pressure/latency acceptance |
| `DOC-CAP08` | M: validation, daemon JVM и CLI launch могли восприниматься единым ресурсным профилем | Разделены semantic check, daemon EnvironmentFile, transient CLI/helper heap и фактически применённые systemd/ancestor limits |
| `DOC-CAP09` | M: ссылка на поддерживаемые load commands не показывала открытый отказ harness | TOOL-10 явно связан с `.html.sealed` mismatch и невыполненными поздними assertions; исторический live Native smoke не переименован в новый pass |
| `DOC-CAP10` | M: приёмке не хватало короткого воспроизводимого сценарного контракта | Указаны binary/config/corpus/initial DB/host, completion/control/resource criteria до прогона, repeated distributions и sustained arrival/retention отдельно от конечного batch |
| `DOC-CAP11` | L: отсутствовали обе RU-пары и maintenance entry points | Созданы переводы, согласованы runnable blocks, исходные числа/идентичности и ссылки; source map и navigation дополнены без blanket README review |

## Источники и границы

Source set `capacity` уточнён в [source-sets.tsv](source-sets.tsv).

| Область | Живые источники | Проверенная граница |
|---|---|---|
| Ресурсные настройки | application.yml, packaging YAML/unit/env/ioc/ioc-config, IocProcessingProperties, IocConfigPreflight | Defaults, cross-section validation, отдельные launch options и отсутствие автоматического применения systemd из YAML |
| Workspace | DocumentPreparationLimits, JdbcDocumentPreparationWorkspaceFactory, DocumentWorkspaceDiskUsage, JdbcDocumentPreparationWorkspace | Shared lease estimate, quota reservations, source quarter, close/discard/seal, admission-triggered pruning и safety ownership |
| Source stream | TikaSourceReader, StreamingHtmlParser, JdbcDocumentSourceWorkspace | Writer path, HTML pruning/region bound, SAX DOCX, bounded scratch и отсутствие общего memory promise для любого Tika format |
| Execution | DurableDocumentDispatcher, IngestFlowConfiguration | Serial detector, workspace-capped window, oldest-first promotion и saturated unclaimed source |
| SQLite | SqlitePragmaPolicy/TuningPreset, JdbcWriterAdmission, JdbcSnapshotSliceReader | Fixed safety settings, operation ownership/wait/hold, reader-bound snapshot и WAL pin |
| Наблюдение | DataProcessingCapacityHealthIndicator, HealthCommand | Health status conditions, exact detail names/units, cached pressure, component request path и exit codes |
| Developer facade | Makefile, lifecycle-smoke.sh и load scripts | Help/dry-run проверяют интерфейс, но не исполняют load; TOOL-10 остаётся открытым |

Внешние значения терминов сверены с первичными источниками:
[systemd resource control](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html),
[kernel cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html),
[SQLite journal_size_limit](https://www.sqlite.org/pragma.html#pragma_journal_size_limit).
Онлайн справочник systemd на момент чтения — версия 262; offline syntax check
использует установленный systemd 255.4, без activation и подтверждения ресурсов стенда.

## Выполненные проверки

- **34 unit/contract cases, 7 suites**, без failures/errors/skips:
  JdbcWriterAdmissionTest 5, SqlitePragmaPolicyTest 4,
  IngestAdapterPropertiesTest 1, DataProcessingCapacityHealthIndicatorTest 8,
  DocumentProcessingAdapterTest 8, ConfigurationDocumentationContractTest 7,
  DocumentationConventionTest 1.
- **157 IT cases, 10 suites**, без failures/errors/skips: основной отбор 130
  (JdbcDocumentPreparationWorkspace 59, DocumentSourceWorkspace 18,
  DocumentProcessingPolicyGate 1, SnapshotSliceReader 18, SqliteDataSourceFactory 1,
  FileSourceMessageHandler 28, NativeDeliveryLifecycle 1,
  RouterSelectedImportDelivery 1, TikaSourceReaderFormatContract 3) и отдельно
  StreamingSourceContractIT 27. Это deterministic contracts, включая частный
  100k slot-reuse fixture, а не новый full-service bulk benchmark или live SMB.
- **10 semantic CLI JVM:** оба literal YAML blocks отдельно/вместе на classpath
  и packaging configurations — 6 valid; memory/disk/source/workers violations —
  4 отказа, exit 78 / CONFIG.SEMANTIC_INVALID. В изолированных каталогах БД не созданы.
- **4 health CLI JVM:** реальный `health --component dataProcessingCapacity --json`
  против собственного HTTP fixture: UP → 0, DOWN/DEGRADED → 1, unreachable → 2.
  Проверены path и JSON; это не live actuator или installed privileged launcher.
  HTTP worker остановлен и bounded join подтверждён.
- **1 numeric Java probe:** production limits/factory подтвердили lease 21,037,056
  bytes, capacity 3 при штатном бюджете; workspace не открывался и storage не создавался.
- **7 прочих example/interface checks:** literal systemd fragment принят offline
  parser; observation Bash syntax/argv проверены synthetic systemctl/sudo wrappers;
  три script help и два make dry-run прошли без runtime/load/systemd activation.
- Две пары прошли heading, inline-code count, literal block и normalized-link
  проверки; translated anchors сопоставлены явно. Три исторические EN-таблицы
  совпали с HEAD побайтово; numeric cells RU совпали; старые inline identities
  сохранены, пять historical source commits разрешились через Git.
- `make docs` прошёл: 2854 total / 695 unique / 2612 OK / 0 errors / 242 excluded;
  `git diff --check` прошёл. Inventory проверил 367 paths/64 source sets, все
  current/base hashes и 357 сохранённых входных Markdown-файлов. Проверки ссылок
  не подменяют semantic/parity review или новый нагрузочный прогон.

Evidence в ignored `.dev/r030-doc-02h/`: unit.log, integration.log,
source-stream.log, retained integration XML, examples.json/log, budget-probe.log,
historical-source-identities.json, parity/inventory checks и docs log.
Перед отдельным Maven selector текущие XML сохранены: lifecycle намеренно
удаляет stale reports. Это не изменение suite universe или quality policy.

## Учёт и сохранение предыдущей работы

На входе 364 tracked Markdown paths и чистый index. Snapshot hashes и исходных
inventory/source-set rows сохранён до правок. Теперь 367 Markdown paths,
64 source sets и 54 отсутствующие RU-пары: 3 root, 1 capability, 49 ADR, 1 guide.
Исходный census 329 на `2c76d8b095a2162c7038c64de896b244517cf4c7` и base hashes
не менялись. Не относящиеся к H source sets/review states сохранены.

Содержательные документы/records DOC-02A–G, KNOWN-ISSUES с TOOL-10/OPS-10/11/12,
ADR/generated catalogs, исходный release baseline и security docs сохранены.
Root/guides README меняются только навигационно; локальные module/directory
README не проходили новый полный аудит.

## Свежесть и оставшаяся работа

make context подтверждает исходный HEAD; `verify.fresh=false`, `pmd.fresh=false`:
последние полные PASS принадлежат `191e74146dddeeab36bf59d9d09020a92bef0620`.
Полные make verify / pmd-analysis, повторная performance qualification, actual
systemd activation/backup/restore, provisioned SMB/LAN, sustainable cleanup rate
и p95/p99 не выполнялись. Частичный module selector не освежает global gates.

OUT-4 и TOOL-10 остаются открытыми. DOC-02H закрывает документный комплект,
но не R030-DOC, R030-SEC, R030-REL или resource acceptance. Неудачные исторические
screens и failed smoke не превращены в pass; новые production source defects
этим срезом не заявлены и source fixes не выполнялись.
