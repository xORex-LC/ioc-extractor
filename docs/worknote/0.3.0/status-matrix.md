---
title: "Матрица состояния релиза 0.3.0"
version: "0.3.0"
status: "Active"
source_of_truth: false
language: "ru"
---

# Матрица состояния релиза 0.3.0

Состояние на консолидацию 2026-11-08; это roadmap, не отчёт fresh gate.
Правила переходов: [execution-model](execution-model.md).
Исторические outcomes и точные identities:
[qualification record](../../../.github/release-evidence/ru/0.3.0.md) и
[компонентные records](../../../.github/release-notes/README.md).

## Глобальные цели

| Goal | State | Подтверждённое / следующий gate |
|---|---|---|
| R030-BASE | verified | Исходные compatibility/runtime сравнения сохранены; менять comparison contract только явно |
| R030-DATA | verified | TTL/import/aggregate repository/package и отдельная live activation квалифицированы; H5 breadth явно deferred в OPS-8, TOOL-10 остаётся открытым |
| R030-BUILD | verified | Tool adoption и ratchets приняты; required statuses активированы на main/release-0.3.0 и проверены в run 34679847573; fresh candidate gate всё равно обязателен |
| R030-TEST global controls | verified | Lifecycle, coverage, regex, waits, external shells, pilots, Codecov и standalone consumers внедрены; module cells отдельны |
| R030-LIB | verified | Шесть 0.3.0-rc.1 библиотек опубликованы и имеют отдельные records/consumers; TOOL-8 recovery, TOOL-6 API comparison и TOOL-9 versions остаются открытыми; ETL внутренний |
| R030-QUAL | planned | Закрыть module review и актуальный intake в goal; зелёный analyzer не заменяет review |
| R030-RETIRE | planned | Сверить поддерживаемые/history surfaces и выполнить RETIRE-APP-11; DEP-9/10 уже устранены |
| R030-ARCH | planned | Подтвердить module/package maps и boundaries; QUAL-BOOT-1 остаётся review scope |
| R030-SEC | in-progress | Старый dependency repair квалифицирован отдельно; SRC-3 и repository/workflow/publication-security review ещё открыты |
| R030-DOC | in-progress | Основные root/capability/guide комплекты пересмотрены; оставшаяся очередь и scope ниже |
| R030-REL | planned | Все обязательные prerequisites, fresh security, compatibility/performance, packaging/upgrade/rollback и final release evidence |

## Module hardening matrix

Сохранены состояния исходной матрицы; `planned` означает необходимость сверки
owning DoD/evidence, а не доказанный дефект реализации. Старые `in-progress`
также нужно сверить с фактическим компонентным scope. Публикация, глобальный
TEST и обновление документа не закрывают прочие cells автоматически.

| Maven module | `R030-QUAL` | `R030-RETIRE` | `R030-ARCH` | `R030-TEST` | affected `R030-DOC` |
|---|---|---|---|---|---|
| `platform/platform-errors` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-diagnostics-api` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-diagnostics` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-etl` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-events` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-concurrency` | `in-progress` | `planned` | `in-progress` | `in-progress` | `in-progress` |
| `platform/platform-logging-slf4j` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-observability` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-diagnostics-logging` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-domain` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-processing` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-application` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-application-tck` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-config-spring-boot` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-events-spring` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-regex-re2j` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-psl` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-processing-camel` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-source-tika` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-csv` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-manifest-json-jackson` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-store-jdbc` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-transport-smb` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-ingest` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-cli-picocli` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `bootstrap/ioc-app` | `planned` | `planned` | `planned` | `planned` | `planned` |

## Оставшаяся очередь

| Scope / item | Owner / goal | Следующий проверяемый результат |
|---|---|---|
| QUAL CPD/PMD intake, QUAL-BOOT-1 | QUAL/ARCH/TEST | Live characterization и disposition по компактным таблицам [QUAL](goals/R030-QUAL-code-health.md); метрики разрешают review, не механическое извлечение |
| RETIRE-APP-11 StableArtifactId | RETIRE / application artifact | Подтвердить отсутствие Java/resource/reflection/API use, отдельное удаление declaration и obsolete reference с gates |
| Supported/history retirement surfaces | RETIRE / owning modules | Сохранить migrations/ledger replay, engine fallback, SMB, maintenance, publish/TCK до принятого retirement; [контракт](goals/R030-RETIRE-retirement.md) |
| Security: SRC-3 | SEC / dependency owners | Датированная affected-version матрица сохранена; reachability/owner disposition либо qualified remediation и fresh security evidence ещё нужны |
| SEC-LOG-1 | SEC / observability | При отложенном аудите SECURITY-ENGINEERING сверить broad redaction statement с formatter-only structured-context contract; free message/Throwable/cause/suppressed требуют producer ownership; это не доказательство новой утечки |
| DOC remaining paths | DOC / owning documents | [198 исходных задач](documentation/remaining-work.tsv): 49 ADR translations, 126 исходных README reviews (11 README verified (CLI/configuration/CSV/Spring events/ingest/manifest), 115 pending), 2 отложенных security docs, 15 release records, 6 compatibility redirects; локальный частичный review не закрывает полный scope |
| Publication follow-ups | LIB/SEC/REL | TOOL-6 API comparison, TOOL-7 provenance, TOOL-8 protected configuration CI recovery, TOOL-9 independent-version Maven/CI transition; не повторная публикация исходных RC |
| External target qualification | DATA/REL / operators | OPS-8 H5 две identities; неподтверждённые server/platform claims остаются skips; source default disabled |
| Operational/source findings | Owning capabilities / REL | OPS-10…14, OUT-4, ING-11/13/15/16, SRC-3 и TOOL-10 сохраняются в [KNOWN-ISSUES](../../KNOWN-ISSUES.md); retirement записок не закрывает их |
| Internal ETL reuse | QUAL/ARCH / ETL | Перед внешним reuse/publication сверить direct construction, compacted-policy input, post-effect observer failures и resource budget; текущие ограничения закреплены в README/Javadoc, ARCH-5 |
| Final release comparison | REL | Повторить representative workloads с сопоставимым boundary; latency/RSS regression и unavailable checks получают явный disposition |

QUAL-REGEX-1 закрыт TEST-REGEX-03, QUAL-CSV-1 — POM metadata correction.
RETIRE-DEP-9 (application→platform-errors) и RETIRE-DEP-10 (CSV→commons-io)
уже устранены. Они не остаются кандидатами на повторное исправление.

## Принятые defer

| Scope | Причина / ограничение | Exit condition |
|---|---|---|
| DATA-IMPORT-01 / ADR 0025 H5 / OPS-8 | Нет двухидентичного live fixture; это не pass и не расширение SMB support claim | Opt-in SmbManagedImportHardeningContractTest на конкретном family: denial, capability/namespace, claim, reconnect, disposition, exact purge |
| Multi-daemon ingestion lease/fencing | 0.3.0 поддерживает single-daemon; process-local ING-10 guard не cross-process ownership | Реальный multi-daemon deployment requirement и отдельное durable fencing решение |
| Security documents | Пользователь отложил SECURITY-ENGINEERING/THREAT-MODEL в отдельный комплект | Отдельный substantive audit/перевод; SEC и release closure до него не объявлять |

## Обновление

После item обновлять только затронутую cell/очередь, указывать постоянный outcome
и точную verification identity. Completed audit transcripts, полный census и
проектные журналы retired; они доступны в immutable snapshot из qualification
record, но не являются новым active backlog. Шесть опубликованных RC не означают
закрытие всех модулей или готовность приложения к выпуску.

### README CLI adapter — 2026-11-11

Рекурсивно пересмотрены два исходных README `adapter-cli-picocli`; добавлены
README test package и consumer fixtures. Все четыре reference-документа на
английском: локальные обязанности, структура, dependencies, early/Spring paths,
ленивое разрешение use cases, вывод/exit codes и границы test coverage.
Два исходных queue items закрыты с сохранением original hash/revision;
новые test/fixture references указаны как companion outcomes в строке модуля.
Это закрывает affected DOC cell выбранного модуля, остальные goals не
переоценены. Проверки: полный `make verify`, локальные ссылки и структура README;
логи проверки остаются локальными в `.dev/cli-readme-audit/`.

### README configuration adapter — 2026-11-11

Пересмотрен module README adapter-config-spring-boot, добавлены английские
README production/test packages. Описаны API/internal boundary, source/schema
coverage, name-only/result/migration contracts, ownership и различия local tests /
standalone qualification. Один исходный queue item verified с прежним hash/ref;
новые references записаны как companion outcomes. DOC cell модуля закрыт;
остальные goals, TOOL-8 и историческая publication qualification не переоценены.
Проверки: make verify, dependency tree, README file maps и локальные ссылки;
логи локальные в `.dev/config-readme-audit/`.

### README CSV adapter — 2026-11-11

Рекурсивно пересмотрены три исходных README adapter-csv и добавлены английские
README обоих test packages. Уточнены strict input и limits, validators/transforms,
deferred preparation, mapping DSL, generation-owned mutable projection, encoding,
immutable staging/recovery/durability, leases и разные retention/catalog semantics.
Пять references описывают local ownership и границы test evidence; wire-format
Jackson tests и deployment-filesystem qualification не подменяются локальными
codec fixtures. Три исходных queue items verified с сохранением hash/ref, новые
companion outcomes отмечены в очереди. Закрыт только affected DOC cell.
Проверки: make verify, dependency tree, README file maps/links и diff --check;
локальные логи `.dev/csv-readme-audit/`.

### README Spring events adapter — 2026-11-11

Пересмотрен module README adapter-events-spring, добавлены английские README
production/test packages. Уточнены one-attempt publication, partial delivery,
normal-return/observer semantics, immediate Spring Error unwrap, primary/suppressed
failure evidence и service-owned dispatch/resource boundary. Явно отделены
inline executor tests от async completion и source tests от packaged consumers.
Один original queue item verified с прежним hash/ref; новые references отмечены
как companion outcomes. Только affected DOC cell закрыт; family publication,
independent-version activation и другие goals не переоценены. Проверки: make
verify, dependency tree, file maps/links и diff --check; логи локальные в
`.dev/events-spring-readme-audit/`.

### README ingest adapter — 2026-11-11

Пересмотрены module README и inbound package README adapter-ingest, русский
package reference переведён на English. Добавлены три README: outbound
maintenance и два test packages. Локальная карта покрывает document admission,
bounded prepare/ordered promotion, journals/retry/recovery/shutdown, managed import
snapshots/terminal units и ordinary leaf retention. Уточнены разные WatchService
backstops, pre-hash blocked queue, strict atomic claim/seal versus weaker legacy
ledger/retention fallback, observation и single-daemon boundary. Два original
queue items verified, исходные ref/hash/language сохранены, новые README отмечены
companions. Только affected DOC cell закрыт; прочие goals и внешняя qualification
не переоценены. Проверки: make verify, dependency tree, README links/file maps,
English/provenance/preservation checks и diff --check; локальные логи в
`.dev/ingest-readme-audit/`.

### README Jackson manifest adapter — 2026-11-11

Оба исходных README adapter-manifest-json-jackson пересмотрены и переведены
на English; добавлен test-package README. Карта охватывает codec/package-info,
четыре private wire records и единственную IT suite. Сверены POM/dependency tree,
port/models, bootstrap wiring и filesystem integrity callers. Уточнены exact
UTF-8 bytes, ordered arrays, legacy v1/explicit-layout v2, null/enum/error/resource
границы и caller-owned hash/marker verification. Убрано неверное blanket обещание
missing-field rejection: missing primitive rows -> zero подтверждён локальным
probe. V1 golden, V2 round-trip и независимые consumer fixtures разграничены.
Два original queue items verified, provenance сохранены, новый README отмечен
companion; только affected DOC cell закрыт. Прочие goals не переоценены.
Проверки: make verify, dependency tree, make docs, README links/file maps/English,
provenance/preservation и diff --check; локальные логи `.dev/manifest-readme-audit/`.
