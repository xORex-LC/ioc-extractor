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
| DOC remaining paths | DOC / owning documents | [198 исходных задач](documentation/remaining-work.tsv): 49 ADR translations, 126 исходных README reviews (74 README verified (CLI/configuration/CSV/Spring events/ingest/manifest/Camel/PSL/regex/JDBC/SMB/bootstrap/build-support/core including application/domain/processing/TCK), 52 pending), 2 отложенных security docs, 15 release records, 6 compatibility redirects; локальный частичный review не закрывает полный scope |
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
