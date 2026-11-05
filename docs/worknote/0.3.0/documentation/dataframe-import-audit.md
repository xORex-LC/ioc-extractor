---
title: "DOC-02D — Управляемый импорт dataframe"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation; OPS-10 open"
document_type: "Audit and verification evidence"
source_of_truth: false
language: "ru"
---

# DOC-02D — Управляемый импорт dataframe

Дата: 2026-11-05, Asia/Irkutsk. HEAD
`4641e5ea9ff5e75edbf61cdff6581108c60f04e4`, ветка `docs/quality`.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).

Scope: dev/dataframe-import и operator guide, две EN/RU-пары; новый RU capability
и содержательное обновление существующего RU guide. Применены code-documenter,
java-code-review, cli-developer и spring-boot-engineer. Проверяются факты,
полнота, различия механики/операторских действий, примеры и границы evidence.
Наличие ссылок не объявляет все module READMEs или публичный API проверенными.
Production/test Java, POM и scripts не изменялись.

На входе незакоммиченные DOC-02A/B/C. Их 23 содержательных документа/record
сохранены побайтово. KNOWN-ISSUES дополнен только новым OPS-10; удаление этой
строки восстанавливает точные входные байты, включая неизменённый TOOL-10.
Общие navigation/inventory/status дополнены новым пакетом.

## Находки и решения

| ID | Приоритет / наблюдение | Результат |
|---|---|---|
| `DOC-I01` | M: guide представлял processed-route как опцию, хотя обязательность действует и при disabled intake | Согласованы required named plan, source-cell inputs, authorized outputs и отсутствие автоматического импорта всех вычисленных полей |
| `DOC-I02` | M: не хватало точных ABSENT/NULL/VALUE, quoted NULL, whitespace и полного merge contract | Добавлены exact header/alias rules, short-row rejection, порядок transforms/null/formula/validation, policy precedence и таблица пяти политик для existing rows |
| `DOC-I03` | M: фраза «другое identity value — новая запись» скрывала alternative matching и stable-key conflict | Разделены within-delivery reduction и active cross-delivery matching; описаны coalesce, keep-first, last-nonempty и отказ смены stable primary identity найденной записи |
| `DOC-I04` | M: renew-unchanged мог читаться как общий выключатель TTL | Отделены no-op, public mutation и ordered nonblank candidate; TTL-only confirmation не создаёт public revision, receipt recovery не является новым наблюдением |
| `DOC-I05` | M: обещание preview без durable writes было слишком широким для полного CLI | Реальная JVM создала dataframe schema v12 с нулём business rows/import receipts. Validator не claim/promote, но Spring composition может инициализировать/мигрировать storage и workspace |
| `DOC-I06` | M: установленные команды без sudo не проходят root guard helper; requirements до preview неясны | Команды используют sudo; описаны enabled catalog/fixed lifecycle, oneshot без daemon intake, отдельная проверочная конфигурация и startup limits |
| `DOC-I07` | M: validation/normalization и транспортная безопасность смешивались | Валидаторы проверяют refanged whole-cell candidate, но не переписывают input. Указаны required SMB3/session encryption, source claim/capability и границы positive probe против ACL audit |
| `DOC-I08` | M: RU не содержал актуального порядка retention, а partial outcome не был достаточно явным | Описаны COMPLETED_WITH_ERRORS в terminal, сроки 30d/90d, remote purge до local evidence/receipt/ledger и отличие local archive от сохранения SMB object |
| `DOC-I09` | M: status recovery flag принадлежит oneshot process; при nonterminal head обнаружен реальный отказ nullable read | Описана отдельная daemon health authority. Новый OPS-10: SQL NULL next_attempt_at_ms вызывает Bad value for type Long; ошибка не объявляется успешным status result |
| `DOC-I10` | M: replay можно было принять за повтор прежнего контракта или уже завершённую обработку | Уточнены active catalog, retained local source/report, новое occurrence, admission-only CLI success и stable --new-delivery при повторе одного запроса; placeholder ID сделан корректным shell argument |
| `DOC-I11` | L: отсутствовал RU capability; RU guide отставал по validators, slots, preview и replay | Создан полный RU capability, RU guide переработан; heading/literal/identifier/link parity сверена, схема warnings уточнена как dataframe v12 отдельно от service v11 reservation |
| `DOC-I12` | M: guide ссылался на отсутствующую в module README invocation; external support мог восприниматься как универсальный pass | Даны owning suite и TESTING policy, credentials через env, OPS-8 qualification boundary; четыре offline external skips не превращены в live evidence |

## Живые источники

Точные пути дополнены в source set `import` в
[source-sets.tsv](source-sets.tsv).

| Контракт | Владелец / свидетельство | Граница |
|---|---|---|
| Exact-one recognition | DataframeImportCatalogCompiler/Recognizer, ImportHeaderPlan, CommonsCsvDelimitedRecordReader | Имя/расширение не выбирают contract, aliases/case/headers точные; весь parser API отдельно не квалифицирован |
| Tri-state, merge и processed routing | DataframeImportRowMapper, ImportMergeResolver/PolicyResolver, RouterProcessedImportRowPreparer, validators | Output allowlist и source authority сохраняются; preview row-local и не выполняет global reduction/DB planning |
| Canonical commit/identity/TTL/slots | JdbcCanonicalImportWriter, mutation session, workspace writer и canonical import TCK/IT | Одна dataframe transaction; reservations независимы; source/report finalization не откатывает commit |
| Stage/recovery/retention/replay | Application admission/staging/processing/promotion/recovery/retention/replay services | Sealed stage, pinned configuration и committed receipt имеют разные восстановительные полномочия |
| Local ownership/report | LocalManagedImportSourceLifecycle, LocalFilesystemImportSnapshotStore, LocalImportTerminalStore и IT | Producer bytes защищаются до processing; report не содержит raw IOC, source paths или source digest |
| SMB boundary | SmbManagedImportSourceLifecycleIT, runtime composition IT и opt-in external suites | Вызовы транспорта в deterministic IT моделируются; нового Samba/Windows server-family/ACL результата нет |
| CLI/startup/health | ImportValidate/Status/ReplayCommand, shipped ioc wrapper, runtime configuration/state, JdbcImportStatusReader | Полный CLI может создавать schema; status flag process-local; OPS-10 остаётся открытым |

## Выполненные проверки

| Проверка | Результат |
|---|---|
| make context / doctor-core | HEAD/branch/dirty, local upstream и JDK 21 проверены; full gates stale |
| Unit cohort | 25 suites, 212 cases, 0 failures/errors/skips |
| Selected Failsafe verify | 12 suites, 123 reported cases: 119 executed PASS, 4 external skips; 0 failures/errors. Не полный make verify |
| Real preview initialization | Exit 0; dataframe.db создан с user_version=12, все пять artifact tables и import_commit пусты; source не claimed |
| Real local terminal probe | Полный Spring import composition, local claim/snapshot, ручное продвижение use cases prepare/promote/finalize до TERMINAL/SUCCEEDED без daemon workers; независимый SQL expectation: одна masks row example.org / Feed A |
| CLI и literal shell | В итоговом fixture выполнены 14 CLI JVM: help/version, invalid preview (exit 3), advisory duplicate preview, empty-head status, replay и его повтор, literal guide commands. Из них один status вызов завершился exit 1 / OPS-10; остальные соответствуют ожидаемым outcomes. Все четыре bash-блока синтаксически корректны; observation block явно FAILED, health/journal remainder проверен отдельно. Samba install/setfacl и sudo/journalctl моделируются; production root actions не выполнялись |
| EN/RU | Две пары: headings, literal blocks, inline identifiers, normalized link multisets и смысл сверены |
| Inventory / preservation / links | 357 Markdown paths / 64 source sets; 60 отсутствующих RU-пар (3 root / 6 capability / 49 ADR / 2 guide). Base/current hashes и source paths совпадают; 23 прежних файла неизменны, registry exact bytes восстанавливаются удалением OPS-10. make docs: 2577 occurrences, 659 unique, 2357 OK, 0 errors, 220 excluded; git diff --check PASS |

Команды использовали `./mvnw -B -ntp -T 1C -pl bootstrap/ioc-app -am`.
Unit запуск — `test -Dsurefire.failIfNoSpecifiedTests=false` с точным `-Dtest`:
DataframeImportCatalogCompilerTest, DataframeImportCatalogCompilerValidationTest,
ProcessedRouteRequirementTest, DataframeImportRecognizerTest, ImportHeaderPlanTest,
DataframeImportRowMapperTest, ImportMergeResolverTest, ImportCellTest,
DataframeImportModelContractsTest, DataframeImportStagingServiceTest,
DataframeImportDetectionServiceTest, DataframeImportAdmissionServiceTest,
DataframeImportProcessingServiceTest, DataframeImportPromotionServiceTest,
DataframeImportRecoveryServiceTest, DataframeImportRetentionServiceTest,
DataframeImportSourceReadinessCoordinatorTest, EventPublishingCanonicalImportWriterTest,
CommonsCsvDelimitedRecordReaderTest, DelimitedInputLimitingReaderTest,
CsvImportValueValidatorRegistryTest, RouterProcessedImportRowPreparerTest,
ManagedDataframeImportRuntimeTest, DataframeImportHealthIndicatorTest,
EarlyCliLauncherTest.

Failsafe запуск — `-Dskip.unit.tests=true verify
-Dfailsafe.failIfNoSpecifiedTests=false` с точным `-Dit.test`:
JdbcCanonicalImportWriterContractIT, JdbcImportDeliveryLedgerContractIT,
JdbcImportWorkspaceIT, LocalManagedImportSourceLifecycleIT,
LocalImportTerminalStoreIT, LocalFilesystemImportSnapshotStoreIT,
SmbManagedImportSourceLifecycleIT, DataframeImportSmbRuntimeConfigurationIT,
RouterSelectedImportDeliveryIT, SpringCommandConstructionIT,
SmbManagedImportContractIT, SmbManagedImportHardeningContractIT.
Последние два suite property-gated: три baseline cases и один hardening case
пропущены без provisioned fixture. Это не новый live SMB pass и не закрытие OPS-8.

## OPS-10: обнаруженный runtime-дефект

Синтетический local source прошёл claim/snapshot/promotion/finalization. Реальная
CLI команда `import replay --delivery documentation-forward --new-delivery
documentation-replay` вернула exit 0 и новый SNAPSHOT_PINNED. Повтор с тем же
new-delivery сохранил одну occurrence, а canonical rows/receipt не применялись
повторно. Следующий literal status command упал.

Голова service DB имеет `state=SNAPSHOT_PINNED`, `next_attempt_at_ms=NULL`.
`JdbcImportStatusReader.java:61` оборачивает
`resultSet.getObject("next_attempt_at_ms", Long.class)` в Optional, но драйвер
бросает `java.sql.SQLException: Bad value for type Long` раньше Optional.
Пустая очередь до replay читалась успешно. `DataframeImportHealthIndicator`
использует тот же status use case: это подтверждённая зависимость по source,
а не результат нового live actuator теста с такой головой.

Дефект записан как [OPS-10](../../../KNOWN-ISSUES.md). Его устранение требует
изменения owning reader и meaningful regression для NULL/ненулевого deadline,
CLI/health и полных gates; сейчас Java не изменялась. Нельзя вручную подставлять
retry deadline в БД, скрывать status error успешным последующим journalctl или
объявлять весь command block зелёным. Документы явно показывают ограничение.

## Состояние и границы

Ignored `.dev/r030-doc-02d` содержит логи, source snapshots, parity/inventory
scripts, две изолированные fixture areas, preview-boundary и status failure
records. Все IOC, данные source и DB синтетические; installed paths, настоящие
sudo/systemd/journalctl и Samba/NTFS ACL не изменялись. Shell privilege/ACL tools
моделируются; это syntax/argv evidence, не проверка серверных permissions.

Две EN/RU-пары завершены в документном scope с открытым OPS-10. Общие DOC-02/04/05,
R030-DOC, R030-SEC и R030-REL не закрываются. TOOL-10 сохранён и не исправлялся.
Полные make verify/make pmd-analysis не перезапускались; последнее full evidence
на `191e74146dddeeab36bf59d9d09020a92bef0620`, оба freshness=false. Принятые ADR,
generated catalogs, historical release records, source Java, POM, scripts,
workflows и analyzer/coverage baselines не менялись. Commit/push/publication/
deployment и действия на установленном stand не выполнялись.
