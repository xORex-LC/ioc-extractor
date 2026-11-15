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
| `platform/platform-diagnostics-api` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `platform/platform-diagnostics` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `platform/platform-etl` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `platform/platform-events` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `platform/platform-concurrency` | `in-progress` | `planned` | `in-progress` | `in-progress` | `verified` |
| `platform/platform-logging-slf4j` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `platform/platform-observability` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `platform/platform-diagnostics-logging` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `core/ioc-domain` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `core/ioc-processing` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `core/ioc-application` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `core/ioc-application-tck` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-config-spring-boot` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-events-spring` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-regex-re2j` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-psl` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-processing-camel` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-source-tika` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-csv` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-manifest-json-jackson` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-store-jdbc` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-transport-smb` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-ingest` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `adapters/adapter-cli-picocli` | `planned` | `planned` | `planned` | `planned` | `verified` |
| `bootstrap/ioc-app` | `planned` | `planned` | `planned` | `planned` | `verified` |

## Оставшаяся очередь

| Scope / item | Owner / goal | Следующий проверяемый результат |
|---|---|---|
| QUAL CPD/PMD intake, QUAL-BOOT-1 | QUAL/ARCH/TEST | Live characterization и disposition по компактным таблицам [QUAL](goals/R030-QUAL-code-health.md); метрики разрешают review, не механическое извлечение |
| RETIRE-APP-11 StableArtifactId | RETIRE / application artifact | Подтвердить отсутствие Java/resource/reflection/API use, отдельное удаление declaration и obsolete reference с gates |
| Supported/history retirement surfaces | RETIRE / owning modules | Сохранить migrations/ledger replay, engine fallback, SMB, maintenance, publish/TCK до принятого retirement; [контракт](goals/R030-RETIRE-retirement.md) |
| Security: SRC-3 | SEC / dependency owners | Датированная affected-version матрица сохранена; reachability/owner disposition либо qualified remediation и fresh security evidence ещё нужны |
| SEC-LOG-1 | SEC / observability | При отложенном аудите SECURITY-ENGINEERING сверить broad redaction statement с formatter-only structured-context contract; free message/Throwable/cause/suppressed требуют producer ownership; это не доказательство новой утечки |
| DOC remaining paths | DOC / owning documents | [198 исходных задач](documentation/remaining-work.tsv): 49 ADR translations, 126 исходных README reviews (107 README verified (CLI/configuration/CSV/Spring events/ingest/manifest/Camel/PSL/regex/JDBC/SMB/bootstrap/build-support/core (application/domain/processing/TCK)/packaging/concurrency/IOC diagnostics/diagnostics API/diagnostics logging/ETL/events/SLF4J logging/IOC observability/tools), 19 pending), 2 отложенных security docs, 15 release records, 6 compatibility redirects; локальный частичный review не закрывает полный scope |
| Publication follow-ups | LIB/SEC/REL | TOOL-6 API comparison, TOOL-7 provenance, TOOL-8 protected configuration CI recovery, TOOL-9 independent-version Maven/CI transition; не повторная публикация исходных RC |
| External target qualification | DATA/REL / operators | OPS-8 H5 две identities; неподтверждённые server/platform claims остаются skips; source default disabled |
| Operational/source findings | Owning capabilities / REL | OPS-10…15, OUT-4, ING-11/13/15/16, SRC-3 и TOOL-10 сохраняются в [KNOWN-ISSUES](../../KNOWN-ISSUES.md); retirement записок не закрывает их |
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

### README Camel processing adapter — 2026-11-12

Module README adapter-processing-camel пересмотрен; добавлены четыре English
README: contract/compile/runtime и test package. Карта охватывает 21 production
и 5 test Java files. Уточнены neutral/Camel dependency seam, admission bounds,
once-bound predicates, ordered tri-state selection, lazy view/recovery evidence,
native sequential dispatch/UnitOfWork, shallow payload ownership и lifecycle.
Устаревший optional-registration текст заменён текущим required bootstrap path;
разграничены readiness snapshot, bounded drain и termination caller workers.
Один original queue item verified с сохранением ref/hash/language; companions
отмечены в note, закрыт только affected DOC cell. Остальные goals и историческая
capacity/security qualification не переоценены. Проверки: make verify, dependency
tree, make docs, README links/file maps/English/provenance и diff --check;
локальные логи `.dev/camel-readme-audit/`.

### README PSL adapter — 2026-11-12

Оба исходных README adapter-psl пересмотрены и переведены на English; добавлен
test-package README. Сверены classifier/domain port/feature extractor/policy,
bootstrap wiring, POM/dependency tree и packaged Guava suffix data. Уточнены
PRIVATE/public boundaries, порядок shortcuts/PSL, UNKNOWN, lexical IPv4 и onion
validation limits, bare-host input и отсутствие network refresh; устаревшая
stage-3 привязка заменена actual call chain. Локальный 15-case probe подтвердил
добавленные граничные пояснения, без изменения Java/tests. Два original queue
items verified, provenance сохранены, новый README отмечен companion. Закрыт
только affected DOC cell; остальные goals/security qualification не переоценены.
Проверки: make verify, dependency tree, make docs, README links/file maps/English,
provenance/preservation и diff --check; локальные логи `.dev/psl-readme-audit/`.

### README regex adapter — 2026-11-12

Оба исходных README adapter-regex-re2j пересмотрены/переведены English; добавлен
test-package README. Карты охватывают четыре production files и shared contract
suite. Сверены compile/cursor helpers, domain port/consumers, POM/tree и bootstrap
selection/configured-pattern tests. Уточнены lazy borrowed CharSequence и lifetime,
UTF-16 half-open/full-match offsets, eager mutable findAll и caller-owned limits,
native errors, common syntax и explicit JDK choice без automatic fallback.
Локальный contract и bootstrap corpus отделены от universal equivalence и
security/performance qualification. Два original queue items verified, input
provenance сохранены, новый README отмечен companion; только affected DOC cell
закрыт, остальные goals не переоценены. Проверки: make verify, dependency tree,
make docs, README links/file maps/English/provenance/preservation и diff --check;
локальные логи `.dev/regex-readme-audit/`.

### README JDBC storage adapter — 2026-11-12

Пересмотрены module/package README adapter-store-jdbc; добавлены English README
dataframe/service SQL и test package. Карты охватывают 100 production, 32 test
Java files и 26 SQL resources. Сверены ownership двух БД, Native transaction/
receipt/id/slot boundaries, schema catalogs, connection PRAGMAs, thread-confined
sessions, workspaces и health/test limits. Два original queue items verified,
input provenance сохранены; три companion README отмечены в note, закрыт только
affected DOC cell. OPS-15 (unchecked migration failure commits partial DDL at old
user_version) воспроизведён isolated file-backed SQLite probe и добавлен EN/RU
в KNOWN-ISSUES; проблема открыта, canonical rollback guarantee не распространён
на мигратор. Other goals, load/security/deployment qualification не переоценены.
Проверки: make verify, dependency tree, make docs, all README links/file maps,
English/provenance/preservation и diff --check; логи `.dev/jdbc-readme-audit/`.

### README SMB transport adapter — 2026-11-12

Пересмотрены/переведены module/package README adapter-transport-smb, добавлен
English test-package README. Карты охватывают 22 production и 17 test Java files.
Сверены POM/compile+runtime tree, bootstrap shared pool/snapshot/watcher wiring,
endpoint/session ownership, encryption/timeouts, marker-last publish и его пределы,
managed probe/claim/materialization/disposition/retention, notifications и telemetry.
Уточнены positive-only live encryption contract, разные cleanup scopes notify/import
fixtures, partial credential profiles и фактический охват two-identity denial test.
Два original queue items verified, provenance сохранены; companion отмечен в note,
закрыт только affected DOC cell. OPS-8 и server/security/capacity qualification
не переоценены; external contracts без provisioned target остаются skipped.
Проверки: make verify, make doctor-core/docs, dependency trees, source README links/
file maps, English/provenance/preservation, command syntax/selectors и diff --check;
логи `.dev/smb-readme-audit/`. Java/POM/tests unchanged.

### README bootstrap application — 2026-11-12

Последовательно пересмотрены/переведены module, entry-package и bootstrap-package
README ioc-app; добавлены 13 English companions для runtime resources/META-INF,
Java test groups и consumer/golden/customer resources. Карты охватывают 135
production и 96 test-tree Java files плюс 2 Java consumer source fixtures.
Плоский production package из 134 files разложен по responsibility sections без
переноса Java. Сверены main/early CLI/checkers, explicit config/registry admission,
AppConfig/SyncConfig/import graph, lazy datasource ownership, required processing
и policy pins, recovery-before-intake, worker phases/admission/stop boundaries,
mutable/immutable output, event backstops, health/logging и packaged resources.
Исправлены монолитная карта несуществующих subdirectories, прежняя sequential
export cadence, stale event-publisher ownership и optional-router framing.
Три original queue items verified, provenance сохранены; 13 companions отмечены
в note. Exact consumer slices не дополнены README (membership — wire contract).
Закрыт только affected DOC cell; live SMB/deployment/security/load acceptance и
other module goals не переоценены. Java/POM/config/test behavior unchanged;
previous README waves и OPS-15 сохранены. Проверки: make verify, doctor/docs,
runtime dependency tree, все source README links/maps, English/provenance/
preservation, command syntax/selectors и diff --check; логи `.dev/bootstrap-readme-audit/`.

### README build-support quality tools — 2026-11-14

Пересмотрены все 7 English README build-support: root, общие analyzer tools,
test-quality и 4report POM owners. Сверены 9JDK-onlyJava sources/harnesses,
root/report POM wiring, scope/ruleset/baseline/coverage/test registries и Make/CI.
Разграничены 4Maven report projects и2non-reactor tool dirs, root synthetic checks
и late real reports, exact SpotBugs versus CPD/PMD count ratchets, test-suite union
versus executed cases, downstream coverage/floors, policy/watchlist и freshness.
Исправлены пропущенный test-quality в root map, прежние 20PMD dependencies и
неполное описание root harnesses; engine-reflection dependency filter bridge и
missing-raw/late-check boundary описаны явно.7originalqueue items verified,
provenance сохранены; отмечен только documentation scope (report POMs не имеют module cells), прочие goals/
security/watchlist qualification не переоценены. Verifier Java, POMs, rules,
thresholds, counts, baselines и CI не менялись. Проверки: root validate, make verify,
make pmd-analysis, doctor/docs, source README links/maps, English/provenance/scope,
command syntax/dry-run и diff --check; логи `.dev/build-support-readme-audit/`.

### README application core — 2026-11-14

Пересмотрены/переведены28originalREADME ioc-application, добавлены24English
companions:52localreferences охватывают все production package dirs (486Javafiles)
и74testJavafiles. Дополнены отсутствовавшие policy/observation/admission/maintenance/
pipeline/port maps и12test references; classification namespace отмечен как marker,
а actual classification — owning ioc-processing. Сверены POM/compile tree,
workspace/streamed source and cursor lifetime, required plan/checkpoint, split
prepare/promote, per-artifact document writes versus atomic managed-import promotion,
canonical/lifecycle/export-slot/observation identities, durable ordering/replay,
expiry/projection/admission, import contracts/mapping/receipts/terminal cleanup,
immutable formation/recovery/cadence/retention и sync detection/delivery/retries.
Исправлены stale batch-only pipeline, прежние monolith/ports-only claims и future
publish-ledger framing. Framework-free не подменён отсутствием I/O: existing JDK NIO
fetch/marker mechanics указаны; OPS-11/12 остаются открытыми. Test maps разграничивают
local fake/NIO evidence и downstream TCK/parser/SMB/Camel/runtime qualification.
28originalqueue items verified; provenance сохранены,24companions отмечены в module
note. Закрыт только application DOC cell; QUAL/ARCH/RETIRE/TEST и resource/security/
live acceptance не переоценены. Java/POM/tests/API unchanged; previous build-support
slice сохранён. Проверки: make verify, dependency tree, doctor/docs, all source README
links/package maps, English/provenance/preservation/command syntax-selectors/diff;
логи `.dev/application-readme-audit/`.

### README domain core — 2026-11-14

Пересмотрены/переведены 8 original README ioc-domain; добавлены 3 English
companions для test root, feature suites и literal-engine support. 11 local
references охватывают 6 production capabilities (45 Java files), 11 Surefire
suite classes и 1 test helper. Сверены POM/compile tree, capability DAG и outward
regex/PSL seams, вызовы bootstrap/processing и workspace-backed application stages.
Исправлены отсутствовавший feature в package map, несуществующий
SourceContext.UNKNOWN, blanket external-implementation claim и batch-only карты.
Разграничены materialized outcomes и streaming protocols, type priority versus
source order, absolute UTF-16 offsets, invocation-owned claims, refang order/counts,
NBSP/overlap marker precedence и inclusive attribution, normalization versus lexical
validation, supported address forms/typed failures, source-preserving host/scheme
derivation, bare-IP routing, declarative first-match/unmatched decisions и evidence
limits literal/host-kind doubles. Все 8 original queue items verified, input provenance
сохранены; 3 companions отмечены в module note. Закрыт только domain DOC cell;
QUAL/ARCH/RETIRE/TEST и mutation/security/live/capacity qualification не переоценены.
Java/POM/tests/API unchanged; previous build-support/application slices сохранены.
Проверки: make verify, dependency tree, doctor/docs, all source README links/maps,
English/provenance/preservation, command syntax/selectors и diff --check;
логи `.dev/domain-readme-audit/`.

### README processing core — 2026-11-14

Пересмотрен original English README ioc-processing; добавлены 8 English companions:
production namespace и 5 capability packages, test root и mapping suites. 9 local
references охватывают 22 production Java files и 8 Surefire suites. Сверены
POM/compile tree (только domain и platform-errors), module guard и реальные
CSV/bootstrap/application callers. Разграничены domain composition и external
route/view/workspace/storage ownership, neutral file versus network classification,
ClassifiedIndicator consistency, exact-cell parsing после caller-owned refang,
typed parse failures и limits synthetic extractor evidence. Описаны thread-confined
session ownership/close, complete source/type classification keys, exact policy
identity, current-source host reconstruction, LRU/charged retention/oversized bypass
и отсутствие process-wide memory guarantee. Mapping reference покрывает column
views/gates/constants/null/id cells, transform binding/order/lazy errors,
source-label presentation, provider/filter/transform boundaries и typed located
rejection versus programming failures. Test maps различают local semantic evidence
и downstream CSV/regex/PSL/Camel/import/runtime qualification.
1 original queue item verified, input provenance сохранены; 8 companions записаны
в module note. Закрыт только processing DOC cell; QUAL/ARCH/RETIRE/TEST и общий
release/security/capacity scope не переоценены. Java/POM/tests/API unchanged;
предыдущие build-support/application/domain изменения сохранены. Проверки:
make verify, dependency tree, doctor/docs, all source README links/maps, English,
198-row provenance/preservation, command syntax/selectors и diff --check;
логи `.dev/processing-readme-audit/`.

### README core overview и application TCK — 2026-11-14

Пересмотрены/переведены 7 original README: общий core overview и 6 TCK references;
добавлены 2 English companions (TCK namespace и junit). 9 references охватывают
все 15 TCK Java files: 6 abstract contracts, 5 composed annotations и 4 package-info.
Core map дополнен ioc-processing; разделены 3 production modules и test-support
artifact, directory grouping и root reactor ownership. Сверены POM/compile tree,
все 47 inherited test methods и 7 concrete consumers (6 JDBC и file ingestion),
fixture setup/cleanup и actual report wiring. Исправлена blanket identical-behavior
claim: TCK проверяет named observable scenarios; migrations/physical races/failure
matrices/live acceptance остаются adapter-owned. Описаны required factories и
same-store fixture ports, canonical identity/epoch baseline, ingest observation
versus content identity/terminal race, export CAS/exact replay/atomic progress,
lifecycle time/deadline/revision/ID boundary и import FIFO/checkpoints/retention/
receipt replay. Lifecycle test publicId относится к canonical storage ID, не к
reusable immutable-export slots. Import-writer base не объявлен полным proof
cross-artifact atomicity. JUnit reference различает inherited semantic tags,
name-controlled discovery, external property gating и slow metadata; main-source
TCK не считается самостоятельным executed suite или production denominator.
7 original queue items verified, input provenance сохранены; 2 companions записаны
в module note. Все исходные core README tasks теперь verified; закрыт только TCK
DOC cell, остальные module/release goals не переоценены. Java/POM/tests/API unchanged;
предыдущие README slices сохранены. Проверки: make verify, dependency tree,
doctor/docs, all source README links/maps, inherited consumer report membership,
English/198-row provenance/preservation, command syntax/selectors и diff --check;
логи `.dev/core-tck-readme-audit/`.

### README packaging scripts/templates — 2026-11-15

Пересмотрены 2 original English README packaging и tests; добавлен templates
companion. 3 references охватывают 7 shell lifecycle/release scripts, 5 host
templates и packaging contract suite. Сверены GNU/systemd/JDK/native prerequisites,
root/ordinary-user boundary, safe prefix/marker/account/source checks, installer
versus local-deploy activation, config candidates/force, DB+WAL/SHM ACL/xattr backup,
unit restoration/retention, rollback arm/initial-install limits, CLI versus daemon
JVM/profile/port, storage health versus aggregate config-apply gate и no shared
lifecycle lock. Release helper/workflow ownership уточнён: annotated remote-tag и
exact checked-out source — workflow, не draft helper/dry-run. Избыточная процедура
TTL cutover заменена owning guide links с сохранением ключевых backup/one-way
ограничений; restored systemd-hardening anchor сохраняет security-registry citation.
Подтверждён existing OPS-13; TOOL-11 (same-status edit/HEAD drift bypass) воспроизведён
в temp Git repo и добавлен EN/RU. Скрипты/templates/Java/POM/tests не изменены;
серверные install/deploy/config/publish операции не выполнялись.
2 original queue items verified, input provenance сохранены; templates companion
записан в module note. Packaging не Maven-модуль и не имеет отдельной module DOC
cell; прочие cells/goals/stand/security/release acceptance не переоценены.
Проверки: make lint-shell, explicit config-helper ShellCheck, source shell syntax,
release-help/local validate/dry-run probes, make docs, source README links,
provenance/scope и make verify; логи `.dev/packaging-readme-audit/`.

### README platform concurrency — 2026-11-15

Пересмотрен module English README; добавлены production package и test companions.
3 references охватывают 12 public types (включая 2 implementations), package-info
и 3 Surefire suites. Сверены all sources/tests, actual ingest/ledger/sync/import
consumers, POM/compile tree, flattened consumer POM и library-publication archives;
production Java совпадает с component tag ioc-platform-concurrency-v0.3.0-rc.1.
Разграничены per-instance admission FIFO и separate synchronous guard exclusion,
non-deduplicating equal keys, waiting versus dispatched work, key cardinality и
worker queue/global capacity, exclusive asynchronous pool ownership и unsupported
caller-runs/discard policies. Описаны rejection/dispatch-abandonment/runtime/Error
paths, synchronous observer constraints, no default logger, drain versus join,
same-thread reentrancy/cross-key nesting, approximate telemetry и wall-clock age.
Tests map отмечает bounded cleanup и limits synthetic/reflection failure evidence;
service ledgers/reconcile остаются correctness authorities. Published qualification
не подменена local JAR examples и текущим reactor; TOOL-9/versioning scope сохранён.
1 original queue item verified, input provenance сохранены; 2 companions отмечены
в module note. Закрыт только concurrency DOC cell; QUAL/ARCH/RETIRE/TEST и другие
library/release goals не переоценены. Java/POM/tests/API unchanged; previous
packaging slice и TOOL-11 EN/RU сохранены. Проверки: make verify, publication-profile
package/compile tree, consumer POM/archives, exact EN/RU guide examples against local
JAR, doctor/docs, source README maps/links, English/provenance/preservation,
command syntax/selectors и diff --check; логи `.dev/concurrency-readme-audit/`.

### README platform IOC diagnostics — 2026-11-15

Пересмотрены/переведены 5 original README; добавлен test-root companion.
6 references охватывают 26 main Java files (22 types и 4 package-info) и
4 Surefire suites. Сверены shared API/product metadata/error boundary,
12 enum catalogs, explicit aggregate, renderer, producer/consumer imports,
bootstrap redacting formatter/logging sink и POM/compile tree (API + errors).
Исправлены stale DiagnosticCode message-template ownership, missing import/lifecycle
maps, invented direct-import prohibition и registration/ArchUnit conflation.
Описаны stable IDs/severity/impact/message metadata, OPERATION budget boundary,
exact import-ID lookup, explicit static registration versus file-based test scan,
generated docs ownership/update path и production-reference versus actual emission.
Renderer reference разграничивает IOC fallback templates и literal generic IDs,
missing/nonrecursive substitutions, formatter-only disclosure, no i18n loader,
raw DiagnosticException message/context/cause и producer responsibility. Это не
новая подтверждённая утечка и не изменение security disposition.
5 original queue items verified, input provenance сохранены; 1 companion записан
в module note. Закрыт только IOC diagnostics DOC cell; API/logging bridge modules
и другие QUAL/ARCH/RETIRE/TEST/library/release goals не переоценены. Java/POM/tests/
API/generated DIAGNOSTICS-CATALOG unchanged; previous packaging/concurrency
изменения и known issues сохранены. Проверки: make verify, focused module tests
including catalog generator comparison (без update), dependency tree, doctor/docs,
all source README links/maps/English, provenance/preservation, command syntax/
selectors и diff --check; логи `.dev/diagnostics-readme-audit/`.

### README platform diagnostics API — 2026-11-15

Пересмотрены 4 исходных английских README; добавлена общая карта тестов.
5 references охватывают 18 публичных типов, внутренний валидатор маркеров,
3 package-info и 9 Surefire suites. Сверены все исходники и тесты, фактические
ETL/application/bootstrap/logging потребители, JDK-only граница и Maven-дерево.
Уточнены ownership потребителя, равенство кодов/диагностик, неглубокие snapshots,
nullable Result и чистые политики с явным применением решения до durable effects.
Разведены stage N+2 и run N, OPERATION вне бюджета, сохраняемые rejecting signals,
порядок замены, точные counts, batch versus display marker, единый reserved code,
отсутствие dedup/replay protection и потеря деталей при меньшем stage budget.
Описаны synchronous delivery, число вызовов сверх бюджета, частично принятый
префикс при ошибке callback/overflow, containment RuntimeException versus Error,
владение ресурсами и пределы гарантий памяти/потоков/доставки. Карта тестов
разграничивает API witnesses, фактическую компиляцию guide sources и интеграцию.
4 исходные задачи verified, все 198 строк/input provenance сохранены; 1 companion
отмечен в module note. Закрыт только DOC cell; QUAL/RETIRE/ARCH/TEST и остальные
library/release goals не переоценены. Production Java совпадает с component tag
`ioc-platform-diagnostics-api-v0.3.0-rc.1`; историческая публикация не заменена
локальной проверкой. Java/POM/tests/API и предыдущие изменения не затронуты.
Проверки: make verify, 78 локальных тестов, package с library-publication/strict
Javadoc и compile tree, parent-independent consumer POM/archives, три точных
EN/RU примера на локальном JAR, doctor/docs, ссылки/карты всех Java sources,
English/provenance/preservation, синтаксис команд/Make selectors и diff --check.
Повторная remote qualification, публикация и отдельный security/PMD/PIT прогон
не проводились; логи `.dev/diagnostics-api-readme-audit/`.

### README platform diagnostics logging — 2026-11-15

Переведены/переработаны 2 исходных README; добавлена общая карта тестов.
3 references охватывают 2 production types + package-info и 3 Surefire suites.
Сверены все исходники/тесты, фактическая композиция AppConfig, renderer,
shared SLF4J builder/MDC/redactor, architecture rules и Maven compile tree.
Уточнены internal product JAR versus reusable libraries, direct/transitive deps
и test-scoped backend. Описаны typed fields и FATAL→ERROR mapping с сохранением
severity, UNKNOWN outcome независимо от policy, generic category shielding,
restoration и caller-owned correlation; renderer вызывается до level filtering.
Redaction описана в границах подстановок context: точные indicator/item/value,
original-value SHA-256 fingerprint при INFO+ и lexical sanitizer при DEBUG/TRACE,
embedded literal copies, paths/fragments/scheme-less limits, неизменность исходной
диагностики/Throwable и producer ownership остальных каналов. Это уточнение
действующего контракта, не новая подтверждённая утечка или security disposition.
Разведены direct sink failure propagation и service-owned resilient wrapper,
synchronous latency/resources и отсутствие queue/retry/durable guarantee.
Карта тестов отличает captured Logback event, formatter, substitute-sink pipeline
MDC и отдельную bootstrap ECS integration; concurrency cleanup проверяется явно.
2 исходные задачи verified; все 198 строк и input provenance сохранены;
companion указан в module note. Закрыт только DOC cell; QUAL/RETIRE/ARCH/TEST,
security и остальные library/release goals не переоценены. Java/POM/tests/API,
генерируемые каталоги и предыдущие изменения не затронуты.
Проверки: make verify, focused 37 tests, doctor/package/compile tree, make docs,
все README links/Java maps/English, provenance/preservation, синтаксис команд,
Make selectors и diff --check; логи `.dev/diagnostics-logging-readme-audit/`.
Отдельные PMD/security/PIT прогоны и stand/provider qualification не выполнялись.

### README platform ETL — 2026-11-15

Пересмотрены 2 исходных английских README; добавлена общая карта тестов.
3 references охватывают 10 public top-level types + package-info и 2 Surefire
suites. Сверены все исходники/тесты, IocExtractionService typed composition и
owned preparation, observer/logging consumers, architecture rules и compile tree.
Подтверждён internal product JAR: diagnostics API + IOC diagnostics/errors,
JDK-only closure, но не независимый catalog-free reusable kernel.
Сохранены прежние ARCH-5 ограничения; дополнены direct/wildcard type ownership,
nullable/shallow envelopes и metadata, helper-preserved counts versus complete-list
constructor, initial-marker seeding, append-only delta validation, merge/delivery/
compaction/policy ordering и точный outcome summary. Budget, OPERATION, retained
samples, lifetime delivery и full policy history разграничены.
Описаны after-stage policy/initial FATAL/empty chain, typed versus generic stage
failure и runtime failures вне stage-body conversion, observer callbacks и
try-with-resources cleanup, monotonic duration, terminal-summary failure identity.
Сегменты runPreparation/continuation/finishPreparation отделены от ресурсного
владения, транзакций, cancellation и dedup. Application prepare/checkpoint/write
не приписан kernel. Карта тестов отличает local witnesses от неполной квалификации
ARCH-5 и cross-module checkpoint/MDC/architecture evidence.
2 исходные задачи verified; все 198 строк/input provenance сохранены; companion
указан в module note. Закрыт только DOC cell; QUAL/RETIRE/ARCH/TEST, ARCH-5 и
остальные goals не переоценены. Java/POM/tests/API/generated catalogs и предыдущие
изменения не затронуты. Нового source defect/KNOWN-ISSUES disposition нет.
Проверки: make verify, focused 28 tests, doctor/package/compile tree, make docs,
ссылки/карты всех Java sources/English, provenance/preservation/scoped cell,
синтаксис команд/Make selectors и diff --check; логи `.dev/etl-readme-audit/`.
Отдельные PMD/security/PIT и external consumer/stand qualification не выполнялись.

### README platform events — 2026-11-15

Пересмотрен исходный английский module README; добавлены production/test package
companions. 3 references охватывают 7 public types + package-info и 3 Surefire
suites. Сверены все исходники/тесты, application event/publisher consumers,
Spring adapter и bootstrap composition/listener, architecture rules и Maven tree.
Описаны JDK-only publish-only/anti-broker boundary, consumer-owned payload и
metadata, exact-text/positive-version/time/null validation, отсутствие ID/type
registry/dedup/context propagation. Publication return/failure, partial delivery,
telemetry callbacks и adapter-specific exception/timing semantics разграничены.
No-ops/recorder проверяют event reference без metadata inspection; snapshot
immutable/shallow, duplicates и mutable payload остаются видны. Описаны sequential
versus overlapping order, concurrent recording, COW cost/unbounded retention,
per-fixture ownership и отсутствие worker/close/reset/durable history.
Разграничены same-version events core/adapter family, ещё действующий shared
reactor version и service-owned metadata eventVersion. Published tag source
совпадает с Java; historical publication evidence не заменено local qualification.
1 исходная задача verified;198 строк/input provenance сохранены;2companions
отмечены в module note. Закрыт только DOC cell; QUAL/RETIRE/ARCH/TEST и другие
library/release goals не переоценены. Java/POM/tests/API/generated catalogs и
предыдущие изменения сохранены; нового source defect/KNOWN-ISSUES disposition нет.
Проверки: make verify, focused43tests, doctor/package с library-publication/strict
Javadoc и compile tree, parent-independent dependency-free POM/archives, точный
EN/RU guide source на local JAR и Java/XML parity, make docs, README links/maps/
English, provenance/preservation/scoped cell, синтаксис команд/Make selectors,
incoming anchors и diff --check; логи `.dev/events-readme-audit/`.
Отдельные PMD/security/PIT, remote cold consumption, adapter family requalification,
publication/deployment не выполнялись.

### README platform SLF4J logging — 2026-11-15

Пересмотрен исходный английский module README; добавлены основной production
package, ECS/redaction и test package companions. 5 references охватывают
8 public types, internal normalizer и 3 package-info, 7 suites + 2 test helpers.
Сверены все исходники/тесты, IOC observability facades, diagnostics formatter,
bootstrap encoder/recipe evidence owners, architecture rules и compile tree.
Описаны key grammar/descriptor equality versus event identity, native scalar
normalization/snapshots и rejected-update invariants, null declaration/omission
versus ambient hiding, disabled-level validation и exact-key MDC collision scope.
Same-thread/LIFO obligations, reverse first-touch restoration, untouched writes,
worker reconstruction, provider/partial-cleanup failures и no automatic retry
разграничены. Описаны scalar ECS versus helper semantics versus encoder metadata/
dataset/error ownership; current synchronous mutable-event Boot recipe не расширен
async library witness. Redaction описана как selected STRING transforms без
severity bypass/whole-event/Throwable scrub; lexical URL limits сохранены.
Карты тестов отличают API/provider capture, exact guide compilation, private MDC
fault injection, async cleanup, Boot output bytes и packaged/remote qualification.
Sole required compile dependency SLF4J подтверждена consumer/embedded POM;
strict Javadoc и archives проверены. All main Java совпадает с published tag;
historical signed evidence не заменено local JAR guide checks и reactor gate.
1 исходная задача verified;198 строк/input provenance сохранены;4 companions
отмечены в module note. Только DOC cell закрыт; QUAL/RETIRE/ARCH/TEST и library/
release/security goals не переоценены. Java/POM/tests/API/generated catalogs и
предыдущие изменения сохранены; нового source defect/KNOWN-ISSUES disposition нет.
Проверки: make verify, focused112tests (включая actual 3 guide classes), doctor/
package library-publication/strict Javadoc/compile tree, consumer POM/archives,
plain+Boot EN/RU Java/XML/YAML parity, 3 exact plain sources + existing fixture
driver на local JAR и только 3 external logging JARs; make docs, README links/maps/
English, provenance/preservation/scoped cell, command syntax/Make selectors,
incoming anchors и diff --check; логи `.dev/logging-readme-audit/`.
Отдельные PMD/security/PIT, fresh-cache/remote consumer qualification,
publication/deployment и другой provider/async encoder не выполнялись.

### README platform IOC observability — 2026-11-15

Пересмотрены 3 исходных английских README; добавлена общая карта тестов.
4 references охватывают 9 public types + 2 package-info и 6 Surefire suites.
Сверены все исходники/тесты, shared logging/ETL contracts, diagnostics bridge,
AppConfig/tracer/typed selector/encoder, catalog generator/ratchet и compile tree.
Разведены internal product facade и published generic logging library; actual
production closure shared logging+ETL+diagnostics/errors+SLF4J, backend test-only.
Описаны LogField descriptor binding, action/outcome wire helpers versus generic
Enum.toString, catalog descriptions versus producer disclosure gates, ioc.event.type
и skipped disposition ownership. Mode-label enum не выдан за bootstrap selector.
MDC STRING-only catalog field с Object→text адаптацией, null/hide, caller-owned
same-thread/LIFO scope и callback factory cleanup описаны отдельно от typed event
normalization; original RuntimeException/Error и distinct/same cleanup identity
сохранены в контракте. URL helper не выдан за fingerprint/whole-event scrub.
Observer map уточняет run/source + optional source-path/mode attributes, stage
scope, DEBUG start/complete versus ERROR failure и duration/cause ownership;
callbacks не доказывают durable completion и могут fail after effects.
Generated LOGGING-CATALOG остаётся authoritative inventory: normal comparison
без update-флага, explicit regeneration path, taxonomy checks и EventAction
bytecode-reference versus actual emission/LogField coverage разграничены.
Карта тестов отличает local capture/fault injection/async witness от pipeline
MDC, IOC JSON, bootstrap backend и standalone Boot recipe evidence.
3 исходные задачи verified;198 строк/input provenance сохранены;1companion
отмечен в module note. Только DOC cell изменён; QUAL/RETIRE/ARCH/TEST и release/
library/security goals не переоценены. Java/POM/tests/API/generated catalogs и
предыдущие изменения сохранены; нового source defect/KNOWN-ISSUES disposition нет.
Проверки: make verify, focused29tests включая generator comparison без update,
doctor/package/compile tree, make docs, README links/all Java maps/English,
provenance/preservation/scoped cell, command syntax/Make selectors/incoming
anchors и diff --check; логи `.dev/observability-readme-audit/`.
Отдельные PMD/security/PIT, publication/stand/provider qualification не выполнялись.

### README tools — 2026-11-15

Рекурсивно пересмотрены 12 исходных README tools, два русских переведены
на English; добавлять README в промежуточные src/main/java не потребовалось.
Карта охватывает 43 implementation/fixture inputs: dev/CI scripts, standalone
Java generator, семь независимых POM/consumer pairs, repository allowlist, tool
pins и JDT preferences. tools не Maven-модуль; module DOC cells не менялись.

Уточнены local Make versus workflow leaves/jobs, отсутствие consumers/Codecov
в pre-push, отдельные verify/PMD receipts и invalidated при source drift даже
с Maven exit zero. Описаны normalized .dev ownership/reset, app без workspace,
explicit fixture paths/config overrides, PID/JAR и limited readiness checks,
submission versus durable acceptance, generator input counts versus canonical
rows, command dependencies и отдельные scopes heap/process RSS. Lifecycle
TOOL-10 сохранён открытым; ложное обещание systemd memory ceiling удалено,
canonical allocator checks не выданы за export-slot non-reuse.

Для fixtures сохранены actual bilingual guide extraction и independent witnesses,
empty-cache/settings/origin/hash checks, exact POM/graph profiles, routing failure
controls, same-version events family и cleanup; local contracts не выданы за
remote publication/second-service evidence. Central без --publish не описан
как dry-run; signing/remote/recovery operations отсылают к owning procedure.

12 исходных задач verified;198 строк/input provenance сохранены;107/126
исходных README verified,19pending. QUAL/RETIRE/ARCH/TEST и release/library/
security goals не переоценены. Все предыдущие изменения и 43 non-README inputs
сохранены; scripts/Java/POM/tests/API/tool pins/generated catalogs unchanged.
Проверки: doctor-core, ShellCheck+packaging/tools shell contracts, offline
publication contracts, make docs, recursive README links/source maps/English,
provenance/preservation/command syntax и make verify; логи `.dev/tools-readme-audit/`.
Runtime/load scenarios, cold/remote consumers, publication, PMD/security/PIT
повторно не запускались.
