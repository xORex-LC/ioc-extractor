---
title: "DOC-02C — Хранение, lifecycle и экспорт"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation; TOOL-10 open"
document_type: "Audit and verification evidence"
source_of_truth: false
language: "ru"
---

# DOC-02C — Хранение, жизненный цикл и экспорт

Дата: 2026-11-04, Asia/Irkutsk. HEAD
`4641e5ea9ff5e75edbf61cdff6581108c60f04e4`, ветка `docs/quality`.
На входе незакоммиченные DOC-02A/B. Их 12 содержательных документов и два audit
record сохранены побайтово; общие navigation/inventory/status дополнены.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).

Scope: storage, canonical-record-lifecycle dev/guide и artifact-export, четыре
EN/RU-пары. Применены code-documenter, java-code-review, cli-developer и
spring-boot-engineer. Цель — точность и полнота выбранных документов с проверкой
кода, транзакционных границ, recovery и операторских примеров. Весь JDBC API,
module README, managed import, ingestion, transport и deployment целиком не
объявляются проверенными. Production/test Java и инструменты не изменялись.

## Находки и решения

| ID | Приоритет / наблюдение | Изменение |
|---|---|---|
| `DOC-S01` | M: guide обещал rollback до activation с сохранением аддитивной схемы; старый binary может отвергнуть новую версию даже без TTL | Разделены schema migration и lifecycle activation; требуется квалифицированная совместимость либо согласованное восстановление binary/configuration/обеих БД/owned recovery files |
| `DOC-S02` | M: общая фраза о перестроении CSV/export могла означать восстановление исторического slice из текущих строк | Mutable projection отделена от immutable snapshot/delivery identity; историческому срезу нужны исходные проверенные байты. Потеря уже acknowledged mutable file сама не создаёт pending generation |
| `DOC-S03` | M: активность на deadline, физическое удаление и обновление CSV читались как одно мгновенное действие | Объяснены логическое исключение при равенстве, captured asOf на операцию и последующая сходимость archive/projection |
| `DOC-S04` | M: не хватало последствий TTL change, committed replay и частичной activation | Добавлены future confirmations без массового изменения deadline, отсутствие нового TTL у того же committed observation, резюмируемый переход ACTIVATING и запрет disabled уже после начала cutover; upgrade уже активного lifecycle отделён от первичного cutover |
| `DOC-S05` | M: insert-driven формулировки скрывали public-field mutation без новой lifecycle | Revision описана через публичные изменения, включая import update; expiry/renewal отделены. Manual export не принуждает новый slice при неизменных plan/revisions |
| `DOC-S06` | M: flow ставил pre-gate до lease/recovery; общая атомарность и cross-process ownership требовали уточнения | Flow сверён с CLI/bootstrap/service, lease охватывает recovery и pre-gate, атомарность ограничена одним профилем; local NIO lock не означает multi-daemon support |
| `DOC-S07` | M: failure table немедленно присваивала FAILED при materialization error | Описан остающийся активный checkpoint, решение следующего recovery, marker recovery, частичный staging и разные normal/recovery event failure outcomes |
| `DOC-S08` | L: retention pin не определял terminal states; marker мог восприниматься как достаточное доказательство | Указаны SUCCEEDED/ABANDONED, missing-ledger pin, exact membership/hash verification, manifest-hash marker и обязательный atomic move |
| `DOC-S09` | M: health guide не объяснял admission, все причины DEGRADED и CLI exit semantics | Добавлены агрегатные поля, коды 0/1/2, отличие curl transport success от health acceptance, сохранение DOWN body и обход loopback proxy |
| `DOC-S10` | L: отсутствовали три RU-пары; export canonical смешивал языки, Native reservation section стоял после Related | Export переписан на EN, созданы три RU capability docs, переработана RU guide; ссылки на owning source/tests, единая структура и навигация |
| `DOC-S11` | M: описанный executable lifecycle smoke не проходит текущий Native terminal naming contract | Реальный isolated run получил timeout после успешного source archive; новый TOOL-10 и явное ограничение в dev/guide. Scripts не исправлялись в documentation scope; smoke/load не объявлены принятыми |

## Живые источники и глубина проверки

Точные пути внесены в source sets `storage`, `lifecycle`, `export` в
[source-sets.tsv](source-sets.tsv).

| Область | Владелец и проверка | Граница |
|---|---|---|
| Canonical truth, independent reservation и transaction rollback | JdbcCanonicalLifecycleWriter, JdbcLifecycleTransactions, lifecycle TCK binding и storage foundation IT | Document transaction — один artifact; import transaction — вся доставка. Не проведён повторный полный import audit |
| Schema compatibility | DataframeFormatMigrations, ServiceSchemaMigrations, SqliteUserVersionSchemaMigrator и его IT | Текущие форматы 12/14; additive не означает readable старым binary. Принятые ADR не изменялись |
| Validity, activation, clock | FixedRecordValidityPolicy, RecordLifecycle, LifecycleActivationService/AdmissionService, JdbcLifecycleClock и runtime IT | Проверены supported fixed/disabled и описанные границы; не обещаются wall-clock resampling на commit или массовый backfill |
| Mutable generation ownership | GenerationOwnedArtifactProjection, JdbcArtifactProjectionWorkStore, MutableProjectionOwnershipIT | Process-local artifact owner; acknowledged external deletion требует operational recovery |
| Snapshot, slots, identical-byte new revision | JdbcSnapshotSliceReader, JdbcExportSlotRegistry, ReusableExportSlotIntegrationIT | До callbacks проверяются generation/полнота, три попытки при race; это не load/RSS приёмка |
| Formation/recovery/retention | ExportService, ExportRunRecoveryService, ExportChangeDetector, SliceRetentionService, publish retention guard | Recovery не перечитывает canonical rows; completion не равна remote delivery |
| Bootstrap и CLI | AppConfig, lifecycle/export schedulers, health indicators, HealthCommand/ExportCommand | CLI mock endpoint не доказывает реальную actuator assembly; installed service не запускался |

## Выполненные проверки

| Проверка | Результат |
|---|---|
| make context / doctor-core | Branch/HEAD/dirty, JDK 21 и local upstream проверены; full verify/PMD stale |
| Lifecycle/export/application/bootstrap unit cohort | 18 suites, 157 cases, 0 failures/errors/skips |
| ExportCommandTest / HealthCommandTest | 2 suites, 14 cases, 0 failures/errors/skips; всего 171 unit case |
| Выбранный Failsafe verify | 12 suites, 104 cases, 0 failures/errors/skips; не полный make verify |
| make package | Bootable JAR собран из текущего исходного дерева без тестов; full gate evidence не обновляет |
| CLI/examples | 10 отдельных classpath CLI JVM: help/version, UP/DEGRADED/DOWN/unreachable и две health commands из guide. Два реальных curl запроса к своему mock endpoint; DOWN JSON сохранён. В literal block изменён только deployment port. DB не создавались |
| EN/RU | Четыре пары: совпадают уровни headings, literal blocks, inline identifiers и нормализованные multiset ссылок; выполнена смысловая сверка |
| lifecycle smoke | FAILED: exit 1, ожидание 300s, source уже SOURCE_ARCHIVED и admission SUCCEEDED; сценарий expiry/retention/reappearance целиком не исполнен. TOOL-10 открыт |
| make docs / inventory / whitespace | 2524 occurrences, 648 unique, 2304 OK, 0 errors, 220 excluded. Реестр: 355 Markdown paths / 64 source sets, 61 отсутствующая RU-пара (3 root / 7 capability / 49 ADR / 2 guide). Current/base hashes, source paths, 14 сохранённых документов A/B и git diff --check PASS |

Unit cohort использовал `./mvnw -B -ntp -T 1C -pl bootstrap/ioc-app -am test`,
`-Dsurefire.failIfNoSpecifiedTests=false` и точный `-Dtest`:
FixedRecordValidityPolicyTest, RecordLifecycleTest, LifecycleContractModelsTest,
LifecycleRuntimeServicesTest, GenerationOwnedArtifactProjectionTest,
ExportChangeDetectorTest, ExportServiceTest, ExportRunRecoveryServiceTest,
ExportPlanTest, ExportRunTest, SliceRetentionServiceTest, ExportPlanCatalogTest,
LifecycleHealthIndicatorTest, ExportHealthIndicatorTest,
LifecycleDeadlineSchedulerTest, LifecycleHistoryRetentionSchedulerTest,
DaemonExportSchedulerTest, DaemonSliceRetentionSchedulerTest.
CLI cohort использовал тот же test lifecycle с
`-pl adapters/adapter-cli-picocli -am` и двумя именами из таблицы.

Failsafe команда использовала `./mvnw -B -ntp -T 1C -pl bootstrap/ioc-app -am
-Dskip.unit.tests=true verify`, `-Dfailsafe.failIfNoSpecifiedTests=false` и
`-Dit.test=SqliteUserVersionSchemaMigratorIT,JdbcLifecycleStorageFoundationIT,JdbcLifecycleRuntimeIT,JdbcCanonicalRecordLifecycleContractIT,JdbcSnapshotSliceReaderIT,JdbcExportSlotRegistryIT,JdbcExportRunLedgerContractIT,NioExportOperationGuardIT,FileSystemSliceRetentionStoreIT,OnDemandExportIntegrationIT,ReusableExportSlotIntegrationIT,MutableProjectionOwnershipIT`.

## TOOL-10: результат фактического smoke

После `make package` выполнен существующий leaf harness:

```bash
tools/dev/lifecycle-smoke.sh --size 1000 --port 18089 \
  --workspace .dev/r030-doc-02c/lifecycle-smoke
```

Использованы синтетические источники и отдельные paths/loopback port. Daemon
стал HEALTHY. File ledger сохранил `status=SOURCE_ARCHIVED`, document admission
— `phase=TERMINAL`, `terminalOutcome=SUCCEEDED`. Архивный файл существует и
оканчивается `initial.html.sealed`; `wait_for_done` ищет `*-initial.html` и его
не видит. Итог: `daemon did not archive initial.html within 300s`, exit 1.
EXIT cleanup остановил именно этот disposable daemon, runtime status STOPPED.

Логи показывают reconciliation/projection, но harness не дошёл до своих
соответствующих assertions и финального отчёта. Нельзя превращать наблюдение
в PASS, подменять archived name вручную или выдавать focused IT за полную
приёмку smoke/load. Долг записан в [KNOWN-ISSUES](../../../KNOWN-ISSUES.md)
с критериями исправления/переквалификации обоих профилей. Иной код инструмента
не исследован как полностью проверенный, новых root permissions не требовалось.

## Состояние и ограничения

Логи, synthetic CLI fixtures, parity/inventory scripts и snapshot 14 предыдущих
документов находятся в ignored `.dev/r030-doc-02c`. Сами Maven reports могут
очищаться следующим lifecycle; totals сохранены в локальных логах и этом record.

Документный scope DOC-02C проверен с явно открытым TOOL-10. Общие DOC-02/04/05,
R030-DOC, R030-SEC и R030-REL не закрыты. Полные make verify/make pmd-analysis не
перезапускались: последнее full evidence на
`191e74146dddeeab36bf59d9d09020a92bef0620`, оба freshness=false. Load/systemd/
live SMB qualification не запускались. Source Java, POM, workflows, scripts,
coverage/analyzer baselines, принятые ADR, generated catalogs и historical
release records сохранены. Commit/push/publication/deployment не выполнялись.
