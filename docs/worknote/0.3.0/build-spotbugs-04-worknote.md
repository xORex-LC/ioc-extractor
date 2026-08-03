---
title: "BUILD-SPOTBUGS-04 — SpotBugs finding triage worknote"
version: "0.3.0"
goal_id: "R030-BUILD"
work_item: "BUILD-SPOTBUGS-04"
status: "Active"
document_type: "Temporary execution worknote"
source_of_truth: false
language: "ru"
---

# BUILD-SPOTBUGS-04 — рабочий журнал triage и baseline

## 1. Назначение и lifecycle

Этот временный worknote сопровождает поэкземплярный triage SpotBugs findings,
исправление подтверждённых immediate risks, формирование узкого legacy baseline
и доказательство детерминированного повторного запуска.

Документ не заменяет goal contract, status matrix или итоговый build-quality
ledger:

- обязательный контракт: [R030-BUILD](goals/R030-BUILD-build-quality.md);
- текущее состояние: [status matrix](status-matrix.md);
- принятые результаты и release evidence:
  [build-quality ledger](evidence/build-quality-ledger.md).

Пока work item выполняется, здесь фиксируются незавершённые проверки, рабочие
гипотезы, чекпоинты и локальные решения. При закрытии `BUILD-SPOTBUGS-04`
стабильные dispositions и evidence переносятся в ledger/status matrix, а этот
файл получает статус `Closed` и остаётся журналом исполнения 0.3.0.

## 2. Зафиксированная стартовая точка

| Поле | Значение |
|---|---|
| Release / goal / work item | `0.3.0` / `R030-BUILD` / `BUILD-SPOTBUGS-04` |
| Branch | `release-0.3.0` |
| Start commit | `5dd0fd49fa375c00b94593e95a0954ee2d75dc7d` |
| Maven revision | `0.3.0-SNAPSHOT` |
| Repository state | clean, upstream synchronized |
| Verification evidence | `make verify` passed; `verify.fresh=true` |
| Reactor | 24 projects: root, 20 functional JARs, 3 build-only report POMs |
| SpotBugs production scope | 19 runtime JAR modules; `ioc-application-tck` explicitly excluded |
| Initial raw baseline | 118 findings across 628 production classes |
| Report health | 19 module XML/HTML pairs plus aggregate; `errors=0`, `missingClasses=0` |
| Current remediation baseline | 74 reviewed findings: 56 false positives + 18 policy-noise findings; 68 exact selectors; no accepted legacy |

Перед первым triage pass стартовые факты MUST быть повторно проверены через
`make context` и clean SpotBugs reactor run. Значения выше являются snapshot, а
не разрешением игнорировать drift.

## 3. Scope и границы

В scope:

- нормализованный inventory всех 118 исходных findings;
- поэкземплярная semantic disposition;
- исправление подтверждённых immediate correctness/resource/concurrency risks;
- узкий versioned baseline для оставшегося legacy signal;
- единый baseline для module и aggregate reports;
- deterministic clean rerun и итоговое evidence.

Не в scope:

- blocking `spotbugs:check` — это `BUILD-SPOTBUGS-05`;
- test-bytecode analysis;
- Find Security Bugs;
- массовая rewrite ради нулевого raw count;
- package/category-wide suppressions;
- полный PMD ruleset;
- изменение production behavior без подтверждённого finding и regression test.

Analyzer error, missing class, пропущенный модуль или отсутствующий report не
являются false positive и никогда не заносятся в suppression baseline.

## 4. Правила работы

1. Сначала evidence и disposition, затем code/filter change.
2. Каждый raw finding получает стабильный рабочий ID `SB04-NNN`.
3. Допустимые dispositions:
   - `fix-now` — подтверждённый immediate risk;
   - `false-positive` — analyzer limitation при доказанном runtime contract;
   - `policy-noise` — analyzer верно видит конструкцию, но его generic design
     policy неприменима к documented project contract;
   - `accepted-legacy` — реальный, но не immediate-risk долг с owner и exit;
   - `resolved-by-related-fix` — finding устранён тем же узким исправлением.
4. Для `false-positive`, `policy-noise` и `accepted-legacy` обязательны точный selector,
   rationale, owner и review/exit condition.
5. Критичный correctness/resource/concurrency finding не исправляется молча:
   сначала фиксируются сценарий, последствия и предлагаемое изменение очереди.
6. Raw `target/` reports и локальные extraction artifacts не коммитятся.
7. Один широкий work item не означает один широкий commit. Независимые fixes,
   baseline wiring и closure evidence не объединяются без явного решения.
8. Parent Maven configuration и reactor aggregate используют один baseline;
   per-module copies не создаются без доказанной необходимости.

## 5. Чекпоинты исполнения

| ID | Проход | Exit evidence | State |
|---|---|---|---|
| `C0` | Reproduce and inventory | Clean report совпадает по scope/count; 118 findings получили `SB04-NNN` | `completed` |
| `C1` | Immediate-risk triage | SQL/security, correctness, concurrency, resource и nullable-path cases имеют disposition; SQL trust boundaries закреплены regression tests | `completed; hardened` |
| `C2` | Remaining semantic triage | `EI_EXPOSE_REP*`, exception contracts и остальные patterns полностью разобраны | `completed` |
| `C3` | Fix and baseline | Immediate risks исправлены; оставшиеся findings покрыты узкими reviewed selectors | `completed` |
| `C4` | Deterministic rerun | Clean и повторный reactor runs дают одинаковый accepted signal и полный комплект reports | `completed` |
| `C5` | Closure | Ledger/status matrix обновлены; `BUILD-SPOTBUGS-04=verified`; `BUILD-SPOTBUGS-05` готов | `completed` |

Одновременно `in-progress` находится только один чекпоинт. Переход обновляется в
этом файле вместе с краткой записью в журнале решений.

## 6. Очерёдность triage

| Порядок | Finding family | Raw count | Причина приоритета | State |
|---:|---|---:|---|---|
| 1 | SQL patterns | 12 | P1/security surface; подтвердить trusted metadata boundary | `completed` |
| 2 | Correctness patterns | 3 | Потенциальное неправильное runtime behavior | `completed` |
| 3 | `IS2_INCONSISTENT_SYNC` | 1 | Проверить concurrency contract | `completed` |
| 4 | Resource/lifecycle candidates из mixed patterns | 0 | Immediate-risk contract имеет приоритет над category | `completed` |
| 5 | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | 23 | Проверить каждый nullable `Path` edge case | `completed` |
| 6 | `EI_EXPOSE_REP` + `EI_EXPOSE_REP2` | 49 | Отличить mutable leak от binding/ownership contract | `completed` |
| 7 | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | 16 | Проверить публичные exception contracts и Javadoc | `completed` |
| 8 | Остальные patterns | 14 | Mixed legacy/style/performance/serialization signal | `completed` |

Количество resource/lifecycle candidates уточняется в `C0`; findings не
дублируются в итоговом total при переклассификации по риску.

## 7. Triage register

Регистр заполняется после нормализации aggregate XML. Одна строка соответствует
одному исходному `BugInstance`; одинаковое итоговое решение MAY ссылаться на
общую baseline entry только если selector остаётся узким.

`C0` присвоил IDs детерминированной сортировкой: risk-oriented family order
(SQL, correctness, concurrency, nullable path, representation exposure,
exception contract, остальные), затем pattern, module, class/member, source
location и fingerprint. Fingerprint хранится дословно как
`instanceHash/instanceOccurrenceNum`: SpotBugs не дополняет ведущие нули, поэтому
в текущем XML 106 hashes имеют длину 32, 11 — длину 31 и один — длину 30.

Контрольная обратная сверка подтвердила 118 последовательных уникальных IDs и
полное равенство множества 118 сохранённых fingerprints aggregate XML. Все
dispositions намеренно остаются `pending`: `C0` не выполняет semantic triage.

| ID | Module | Pattern | Class/member | Priority/category | Scenario/evidence | Disposition | Fix/baseline ref | State |
|---|---|---|---|---|---|---|---|---|
| `SB04-001` | `ioc-adapter-store-jdbc` | `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` | `com.iocextractor.adapter.out.store.jdbc.DataframeSchemaReconciler#apply` | P3 / `SECURITY` | План недоступен извне `apply`; SQL создаётся только reconciler из regex-validated identifiers и allowlisted type. `ea7a806f5e37f11838a6bea1bf7172/0` | `false-positive` | `C1-SQL-A` | `triaged` |
| `SB04-002` | `ioc-adapter-store-jdbc` | `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` | `com.iocextractor.adapter.out.store.jdbc.DataframeSchemaReconciler#columns` | P3 / `SECURITY` | Имя таблицы проходит `requireSqlIdentifier` и заключается в double quotes перед `PRAGMA table_info`. `9f2432cdb91df89209e35871fee4337a/0` | `false-positive` | `C1-SQL-A` | `triaged` |
| `SB04-003` | `ioc-adapter-store-jdbc` | `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` | `com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository#load` | P3 / `SECURITY` | Artifact обязан присутствовать в validated schema map; table/column identifiers повторно валидируются и quote-ятся. `860e2d254168fa78d62286f2c40d2eba/0` | `false-positive` | `C1-SQL-B` | `triaged` |
| `SB04-004` | `ioc-adapter-store-jdbc` | `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` | `com.iocextractor.adapter.out.store.jdbc.JdbcStorageHealthProbe#intPragma` | P3 / `SECURITY` | Helper теперь принимает только private `IntegerPragma`; exhaustive switch передаёт `executeQuery` literal `PRAGMA user_version` или `PRAGMA foreign_keys`. `7373b103801aad0c559799d4263633f7/0` | `resolved-by-fix` | `C1-SQL-C` | `resolved; absent from focused report` |
| `SB04-005` | `ioc-adapter-store-jdbc` | `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` | `com.iocextractor.adapter.out.store.jdbc.JdbcStorageHealthProbe#textPragma` | P3 / `SECURITY` | Helper теперь принимает только private `TextPragma`; exhaustive switch передаёт `executeQuery` literal `PRAGMA journal_mode` или `PRAGMA quick_check`. `1c1d2010e1ef56636e50813cb0d9088/0` | `resolved-by-fix` | `C1-SQL-C` | `resolved; absent from focused report` |
| `SB04-006` | `ioc-adapter-store-jdbc` | `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` | `com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceFactory#initializePersistentPragmas` | P1 / `SECURITY` | Все concatenated values (`UTF-8`, `INCREMENTAL`, `WAL`) задаёт `SqlitePragmaPolicy`; operator выбирает лишь preset, не SQL token. `6a02f61300ba077316237782576f3771/0` | `false-positive` | `C1-SQL-D` | `triaged` |
| `SB04-007` | `ioc-adapter-store-jdbc` | `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` | `com.iocextractor.adapter.out.store.jdbc.SqliteUserVersionSchemaMigrator#apply` | P3 / `SECURITY` | Один instance объединяет trusted migration body на строке 145 и `PRAGMA user_version=` с positive `int` на строке 147; production wiring загружает migrations только из packaged resources. `9946f8de9403808af3cd5b1070963635/0` | `false-positive` | `C1-SQL-E` | `triaged` |
| `SB04-008` | `ioc-adapter-store-jdbc` | `SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING` | `com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository#insertRow` | P2 / `SECURITY` | SQL shape строится из validated schema identifiers; все row values передаются через `?` bindings. `c1634a6606e4b8ffcd844dc5cadd39a/0` | `false-positive` | `C1-SQL-B` | `triaged` |
| `SB04-009` | `ioc-adapter-store-jdbc` | `SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING` | `com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository#rowId` | P3 / `SECURITY` | Artifact берётся из schema map и quote-ится после identifier validation; `rowKey` bind-ится. `a8ebdedecdc1b07861e6b1a9e6d51a39/0` | `false-positive` | `C1-SQL-B` | `triaged` |
| `SB04-010` | `ioc-adapter-store-jdbc` | `SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING` | `com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository#upsertSource` | P3 / `SECURITY` | Derived `_sources` table и все columns проходят identifier validation; provenance values bind-ятся. `850222ae253a20a5b62a7df56fb2cf03/0` | `false-positive` | `C1-SQL-B` | `triaged` |
| `SB04-011` | `ioc-adapter-store-jdbc` | `SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING` | `com.iocextractor.adapter.out.store.jdbc.JdbcSnapshotSliceReader#readCoverage` | P3 / `SECURITY` | `validatePlan` требует известный schema artifact; quoted table — единственная подстановка, artifact value bind-ится. `77d1f87f81c0fa25955364263c9f2245/0` | `false-positive` | `C1-SQL-F` | `triaged` |
| `SB04-012` | `ioc-adapter-store-jdbc` | `SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING` | `com.iocextractor.adapter.out.store.jdbc.JdbcSnapshotSliceReader#streamRows` | P2 / `SECURITY` | `validatePlan` сверяет artifact/columns с schema allowlist; identifiers quote-ятся, runtime row data в SQL не входит. `1f92ddc320c2bdead783e609b79d14e8/0` | `false-positive` | `C1-SQL-F` | `triaged` |
| `SB04-013` | `ioc-platform-observability` | `RV_RETURN_VALUE_IGNORED` | `com.iocextractor.observability.logging.LogEvent#lambda$write$0` | P2 / `CORRECTNESS` | SLF4J помечает `addKeyValue` `@CheckReturnValue`; текущий код продолжает работу на исходном builder и может потерять field при compliant copy-returning implementation. `3b87e2f343693d5a58827f3c9a000a2a/0` | `fix-now` | `IR-01` | `triaged` |
| `SB04-014` | `ioc-platform-observability` | `RV_RETURN_VALUE_IGNORED` | `com.iocextractor.observability.logging.LogEvent#write` | P2 / `CORRECTNESS` | Тот же общий SLF4J contract нарушен для `setCause`; следующий `log` вызывается на прежнем builder. `931c392de8cca10b285b4a6bcf27427d/0` | `resolved-by-related-fix` | `IR-01` | `triaged` |
| `SB04-015` | `ioc-adapter-sink-csv` | `SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR` | `com.iocextractor.adapter.out.sink.csv.ArtifactFilter#<init>` | P2 / `CORRECTNESS` | `NONE` — immutable shared empty value, а не singleton lifecycle; public constructor обязателен для configured include/exclude filters. `22d5f5b6275533de4047b929a884dff6/0` | `false-positive` | `C1-COR-A` | `triaged` |
| `SB04-016` | `ioc-adapter-sink-csv` | `IS2_INCONSISTENT_SYNC` | `com.iocextractor.adapter.out.sink.csv.CsvArtifactSliceWriter#active` | P2 / `MT_CORRECTNESS` | Исходная реализация была monitor-confined и соответствовала synchronous callback contract, поэтому живой race не подтвердился. Follow-up убрал temporal shared state: `stage` создаёт локальную `CsvSliceMaterialization` и передаёт её reader напрямую. `ce3515cbe758ab88c71718a20476d8fc/0` | `resolved-by-fix` | `C1-CON-A` | `resolved; absent from focused and reactor reports` |
| `SB04-017` | `ioc-adapter-ingest` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.in.ingest.FileIngestionLedger#lambda$findRecords$1` | P2 / `STYLE` | `path` — непосредственный child из `Files.list(ledgerDir)`, поэтому имеет leaf name; stream закрыт. `a17dc20f5b9aee6b634f9a229a5fcd5b/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-018` | `ioc-adapter-ingest` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.in.ingest.FileSystemSourceLifecycle#fileName` | P2 / `STYLE` | Ternary уже обрабатывает `null`; повторный `getFileName()` на immutable `Path` детерминирован. `ed71e9161cf3ad0bebb1c31c07c0036f/0` | `false-positive` | `C1-NP-B` | `triaged` |
| `SB04-019` | `ioc-adapter-ingest` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.in.ingest.FileSystemSourceLifecycle#move` | P2 / `STYLE` | Target всегда `@NotBlank` configured lifecycle dir `resolve` safe leaf; parent существует в production wiring. `cc923aad27b2cc302d2874fa859096d3/0` | `false-positive` | `C1-NP-B` | `triaged` |
| `SB04-020` | `ioc-adapter-ingest` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.in.ingest.FileSystemSourceLifecycle#toArchivedSource` | P2 / `STYLE` | Метод получает только regular-file child из `Files.list(processingDir)`; leaf name существует. `7b3851a67750fc70dec5cf79ccc60545/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-021` | `ioc-adapter-sink-csv` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.sink.csv.CsvArtifactProjection#tempPath` | P2 / `STYLE` | Null parent заменяется на `Path.of(".")`, но одна defensive rewrite вместе с leaf validation устранит ambiguous repeated-call flow. `657c1fb03dfbbf8aa968077dccf71d96/0` | `resolved-by-related-fix` | `IR-02` | `triaged` |
| `SB04-022` | `ioc-adapter-sink-csv` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.sink.csv.CsvArtifactProjection#tempPath` | P2 / `STYLE` | `@NotBlank` path всё ещё допускает filesystem root; у root нет file name, поэтому до temp creation возникает NPE. Запись не начинается и canonical DB не затрагивается. `7559636818a8b8e46aafecacd67f71da/0` | `fix-now` | `IR-02` | `triaged` |
| `SB04-023` | `ioc-adapter-sink-csv` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.sink.csv.CsvArtifactProjection#write` | P2 / `STYLE` | Parent проверяется на null; сохранение результата в local устранит предупреждение в том же узком path-hardening change. `784f87e9a9a5fd39541145ab3a07b5ee/0` | `resolved-by-related-fix` | `IR-02` | `triaged` |
| `SB04-024` | `ioc-adapter-sink-csv` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.sink.csv.FileSystemCompletedSliceCatalog#listCompleted` | P2 / `STYLE` | Invalid path — child из `Files.list(profileDir)`; leaf name существует. `451e8f82803afe99904b550ed37331cc/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-025` | `ioc-adapter-sink-csv` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.sink.csv.FileSystemCompletedSliceCatalog#listCompletedSliceNames` | P2 / `STYLE` | Все candidates — immediate children verified profile directory. `b51ce9279deb56aa6c1cb2e9037a8da0/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-026` | `ioc-adapter-sink-csv` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.sink.csv.FileSystemCompletedSliceCatalog#verify` | P2 / `STYLE` | Private verifier вызывается только для child path или `profileDir.resolve(safeSegment)`; leaf exists. `373835ddfb1193e6005b2007fdf4ff6/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-027` | `ioc-adapter-sink-csv` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.sink.csv.FileSystemSliceRetentionStore#listCompleted` | P2 / `STYLE` | Slice path — child из `Files.list(profileDir)` после physical-directory check. `2d668aa02540c8507a933a99a77fa7f6/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-028` | `ioc-adapter-sink-csv` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.sink.csv.SliceTreeVerifier#lambda$verifyExactMembers$0` | P2 / `STYLE` | Directory members поступают непосредственно из `Files.list(directory)`; stream закрыт. `1c0765bc84d2240b96685d4e7dd36ec8/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-029` | `ioc-adapter-source-tika` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.source.TikaSourceReader#extension` | P2 / `STYLE` | Первичная disposition опиралась на неверную посылку, что filesystem root нельзя открыть как input stream. Root regression показал достижимый null file name; общий `resourceName` guard устранил nullable dereference и сохранил typed failure. `c96f6f461815248a9d4527a86c92dd12/0` | `resolved-by-fix` | `C1-NP-E` | `resolved; absent from focused SpotBugs report` |
| `SB04-030` | `ioc-adapter-source-tika` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.source.TikaSourceReader#readText` | P2 / `STYLE` | На текущем filesystem provider root stream открывается, после чего старый код получал NPE. Он преобразовывался в `SOURCE.READ_FAILED`, но analyzer точно видел реальный nullable dereference; явная leaf validation теперь выдаёт стабильную boundary-причину. `4208ea1522d9ba84c0c34e92596df3b4/0` | `resolved-by-fix` | `C1-NP-E` | `resolved; root regression and focused SpotBugs report passed` |
| `SB04-031` | `ioc-adapter-store-jdbc` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.store.jdbc.LegacyLedgerImporter#importAll` | P2 / `STYLE` | Import list содержит только children, материализованные из `Files.list(legacyDir)`. `8eb4f410da82c4a4b0cfcc4193ead8d1/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-032` | `ioc-adapter-store-jdbc` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.store.jdbc.LegacyLedgerImporter#lambda$legacyFiles$0` | P2 / `STYLE` | То же child-path invariant; list stream закрыт. `ffc8241a66a4a68d89a82e384dd0761a/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-033` | `ioc-adapter-transport-smb` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.transport.smb.SmbFileTransport#lambda$localFiles$10` | P2 / `STYLE` | Comparator получает regular-file children из `Files.list(localDirectory)`; leaf names существуют. `5e5bfca26b4d0a401aaf36091430e7c0/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-034` | `ioc-adapter-transport-smb` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.transport.smb.SmbFileTransport#lambda$localFiles$11` | P2 / `STYLE` | Та же child-path гарантия перед `safeLeaf`; stream закрыт. `9456a9c7e501bc30df6de8d94d6bc29c/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-035` | `ioc-adapter-transport-smb` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.transport.smb.SmbFileTransport#publish` | P2 / `STYLE` | Publish iterates the already materialized `localFiles` child list. `ab4dd7d9826bddec6a3960784b7ae9a1/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-036` | `ioc-adapter-transport-smb` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.transport.smb.SmbFileTransport#publish` | P2 / `STYLE` | Second dereference has the same verified child-path provenance. `ab4dd7d9826bddec6a3960784b7ae9a1/1` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-037` | `ioc-adapter-transport-smb` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.adapter.out.transport.smb.SmbFileTransport#verifyUploadedSizes` | P2 / `STYLE` | Size verification receives only entries returned by `localFiles`. `588e7a610a503a476d76ef12f69e2911/0` | `false-positive` | `C1-NP-A` | `triaged` |
| `SB04-038` | `ioc-application` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.application.sync.RemoteFetchService#finalPathFor` | P2 / `STYLE` | Constructor makes inbox absolute; `leafName` enforces one nonblank safe segment, so resolved candidate is a direct child with parent=inbox. `b4a1a3c4744e27774c3872fe8e1ac3f7/0` | `false-positive` | `C1-NP-F` | `triaged` |
| `SB04-039` | `ioc-application` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `com.iocextractor.application.sync.RemoteFetchService#finalPathFor` | P2 / `STYLE` | Stable suffix preserves a safe leaf; the suffixed candidate is likewise a direct inbox child. `b4a1a3c4744e27774c3872fe8e1ac3f7/1` | `false-positive` | `C1-NP-F` | `triaged` |
| `SB04-040` | `ioc-adapter-ingest` | `EI_EXPOSE_REP` | `com.iocextractor.adapter.in.ingest.IngestAdapterProperties$Patterns#exclude` | P2 / `MALICIOUS_CODE` | Accessor возвращал исходный mutable binding list. `d7b323f87c0fe0cd9d2cbcd2804b919f/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-041` | `ioc-adapter-ingest` | `EI_EXPOSE_REP` | `com.iocextractor.adapter.in.ingest.IngestAdapterProperties$Patterns#include` | P2 / `MALICIOUS_CODE` | Та же adapter-binding alias: copy отсутствовала. `f3cce6cb4dfc6da92a161cdb3916c94a/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-042` | `ioc-adapter-sink-csv` | `EI_EXPOSE_REP` | `com.iocextractor.adapter.out.sink.csv.ColumnSpec#transform` | P2 / `MALICIOUS_CODE` | Adapter value record сохранял и возвращал caller-owned transform list без копии. `2d16b6f136eda7e47a5e5c980b8f81b1/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-043` | `ioc-adapter-sink-csv` | `EI_EXPOSE_REP` | `com.iocextractor.adapter.out.sink.csv.ConfigurableRowMapper#header` | P2 / `MALICIOUS_CODE` | `header` строится через `Stream.toList()` после defensive copy колонок и уже является unmodifiable. `7517b4775548db7bcd7d6dd8b38e66fe/0` | `false-positive` | `C2-REP-C` | `triaged` |
| `SB04-044` | `ioc-adapter-sink-csv` | `EI_EXPOSE_REP` | `com.iocextractor.adapter.out.sink.csv.CsvArtifactDefinition#accepts` | P2 / `MALICIOUS_CODE` | Public adapter definition возвращал caller-owned mutable set. `4a3413116ff21ad06360898deb8cc6ee/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-045` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.ExportPlanCatalog#plans` | P2 / `MALICIOUS_CODE` | `resolve` завершает построение через `List.copyOf`; accessor выдаёт immutable plan snapshot. `d84b636c441d973b1edeaf94d00e4fb7/0` | `false-positive` | `C2-REP-C` | `triaged` |
| `SB04-046` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties#patterns` | P2 / `MALICIOUS_CODE` | `REP-FIX` создаёт null-preserving unmodifiable snapshot; detector не выводит отсутствие внешнего owner у внутреннего `LinkedHashMap`. `e514ebe6ae8e6497319b778ed8a32c59/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-047` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$ArtifactIdentity#artifacts` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable, что закреплено regression test. `321d59427789ab8d26d2e8c7d5cc9535/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-048` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$ArtifactIdentity$Artifact#keyColumns` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `21898152d6e6d95f5c7f99eaf4ce3c0d/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-049` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Classify#rules` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `9b30fa71c0d8a8dddad035e4e509f075/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-050` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Classify$Rule#when` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `4a52f37d7e1f686b57bba129534e0e63/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-051` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Export#profiles` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `4803fec7397c4f66e5f6c11029ce35e/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-052` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Export$Profile#artifacts` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `e54252e74bad4dd5fc6d8efdf60cf08b/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-053` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Ingestion$Patterns#exclude` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `32750f92d56af54271412579aeaa13b/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-054` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Ingestion$Patterns#include` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `c979f70e1e210a5e83ec74c753cd77aa/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-055` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Maintenance$Retention#targets` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `4383028711e091ec3fa3830bcd139efc/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-056` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Refang#rules` | P2 / `MALICIOUS_CODE` | Null-preserving copied list сохраняет порядок без внешнего mutable owner; accessor unmodifiable. `11a462629da63283350d0221a37f6592/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-057` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Sink#artifacts` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `27f968e5458e541f71621c1e4ea4e628/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-058` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Sink$Artifact#accepts` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `d427c73f23b7f225acef3a345a6e5c0a/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-059` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Sink$Artifact#columns` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `17d6c50b951ca7086aa4740028b55003/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-060` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Sink$Artifact#exclude` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `5d1273b759b27055d9e869e41a9b17b4/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-061` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Sink$Artifact#include` | P2 / `MALICIOUS_CODE` | Null-preserving copied list не имеет внешнего mutable owner; accessor unmodifiable. `b9bf50e4732ccab46011395c7b6bf22d/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-062` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Sink$Artifact$Column#transform` | P2 / `MALICIOUS_CODE` | Nullable copied list не имеет внешнего mutable owner; accessor unmodifiable. `4c232ba4bc3cebc68710c0ed9d38965d/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-063` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.IocProperties$Source#sectionMarkers` | P2 / `MALICIOUS_CODE` | Null-preserving copied list сохраняет порядок без внешнего mutable owner; accessor unmodifiable. `adc0bb370c48470cb26ddf361beac388/0` | `false-positive` | `REP-FIX-FP` | `reviewed` |
| `SB04-064` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.LazyServiceStorage#dataSource` | P2 / `MALICIOUS_CODE` | Класс является lifecycle owner/provider именно этого shared datasource; Spring bean имеет `destroyMethod=""`, закрывает ресурс только owner. `78abcf5932d070b501ab72ebb6fa4894/0` | `false-positive` | `C2-REP-E` | `triaged` |
| `SB04-065` | `ioc-app` | `EI_EXPOSE_REP` | `com.iocextractor.bootstrap.TransportRegistry$Binding#transport` | P2 / `MALICIOUS_CODE` | `Binding` — bootstrap dependency bundle, а не value object; registry намеренно делегирует тому же adapter instance и владеет его lifecycle. `e4130582c3b17b49103e206b650c2a70/0` | `false-positive` | `C2-REP-E` | `triaged` |
| `SB04-066` | `ioc-application` | `EI_EXPOSE_REP` | `com.iocextractor.application.ingest.CanonicalArtifactsChanged#artifactNames` | P2 / `MALICIOUS_CODE` | Compact constructor валидирует элементы и присваивает `List.copyOf`; control-event payload immutable. `f047e7441979dcd1c0ac9cb9cf8402dc/0` | `false-positive` | `C2-REP-C` | `triaged` |
| `SB04-067` | `ioc-adapter-ingest` | `EI_EXPOSE_REP2` | `com.iocextractor.adapter.in.ingest.IngestAdapterProperties$Patterns#<init>` | P2 / `MALICIOUS_CODE` | Constructor сохранял caller-owned exclusion list без copy. `17412f59916541b9d5ca32f457b6a8cd/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-068` | `ioc-adapter-ingest` | `EI_EXPOSE_REP2` | `com.iocextractor.adapter.in.ingest.IngestAdapterProperties$Patterns#<init>` | P2 / `MALICIOUS_CODE` | Constructor сохранял caller-owned include list без copy. `88e2ccc27ed7e7f2f82d9ac814ffea3/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-069` | `ioc-adapter-sink-csv` | `EI_EXPOSE_REP2` | `com.iocextractor.adapter.out.sink.csv.ColumnSpec#<init>` | P2 / `MALICIOUS_CODE` | Constructor сохранял caller-owned transform list; null является допустимым DSL value. `2bf452c6c68175ca31a4d07c6999de09/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-070` | `ioc-adapter-sink-csv` | `EI_EXPOSE_REP2` | `com.iocextractor.adapter.out.sink.csv.CsvArtifactDefinition#<init>` | P2 / `MALICIOUS_CODE` | Constructor нормализовал filter, но не копировал accepted-type set. `2bd885fc321365e00b5fabc59e140fb0/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-071` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties#<init>` | P2 / `MALICIOUS_CODE` | Root constructor не копировал pattern map; null-sensitive validation запрещает безусловный `copyOf`. `b6ea00cf7c6b9d964b88d6142d511e6/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-072` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$ArtifactIdentity#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял mutable artifacts list. `279b2871263c8502cf91d7b8e2ec5132/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-073` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$ArtifactIdentity$Artifact#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял mutable key-column list. `c13f2d23621b7d3dd2b18988d1358150/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-074` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Classify#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял mutable classify rules. `a5c71d9670bee8cbca61f047c3da5bc4/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-075` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Classify$Rule#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял mutable predicate-key list. `c95ad42837f4e593501b3b1276bf021e/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-076` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Export#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял mutable profile list. `476e975b78f8152b0da50dfd6774f72f/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-077` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Export$Profile#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял mutable artifact-name list. `bda497677a1a86320cf68cf82534ba9d/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-078` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Ingestion$Patterns#<init>` | P2 / `MALICIOUS_CODE` | Bootstrap constructor сохранял mutable exclusion list. `36f9394efd2d07bc84c0e9ea533003ea/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-079` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Ingestion$Patterns#<init>` | P2 / `MALICIOUS_CODE` | Bootstrap constructor сохранял mutable include list. `ed34a5d726816821b78119a175de0913/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-080` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Maintenance$Retention#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял mutable retention targets. `3de8b959c89295faac211c39b948445c/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-081` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Refang#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял mutable ordered rules. `3177dc591e440a55446721d5926e57fc/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-082` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Sink#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял mutable sink artifacts. `b720c3dff4492ac9d359726495118b75/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-083` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Sink$Artifact#<init>` | P2 / `MALICIOUS_CODE` | Один constructor instance относился к accepted-type list; copy отсутствовала. `4d9396086518b83baab8fa6b39604287/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-084` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Sink$Artifact#<init>` | P2 / `MALICIOUS_CODE` | Один constructor instance относился к column list; copy отсутствовала. `d6ddc20d9e59bf25fa986b6ab8a9fd73/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-085` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Sink$Artifact#<init>` | P2 / `MALICIOUS_CODE` | Один constructor instance относился к exclusion-key list; copy отсутствовала. `dc6e5c5a30c2034dc1fcfbe52f80033b/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-086` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Sink$Artifact#<init>` | P2 / `MALICIOUS_CODE` | Один constructor instance относился к inclusion-key list; copy отсутствовала. `f7e956c34c1a1c2bbfeb41850e1d486/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-087` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Sink$Artifact$Column#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял nullable mutable transform list. `4678fb3055ceebe0db3781e8b468f6b1/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-088` | `ioc-app` | `EI_EXPOSE_REP2` | `com.iocextractor.bootstrap.IocProperties$Source#<init>` | P2 / `MALICIOUS_CODE` | Binding constructor сохранял mutable section-marker list. `9f860ec6e5c40a8722baca122d16f073/0` | `resolved-by-fix` | `REP-FIX` | `verified absent` |
| `SB04-089` | `ioc-adapter-cli-picocli` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.adapter.in.cli.ExportCommand#call` | P3 / `BAD_PRACTICE` | Logs the failure and rethrows the same runtime exception to the Picocli boundary. `3715419b138a600afca4c34bc44a6f39/0` | `policy-noise` | `C2-EX-A` | `triaged` |
| `SB04-090` | `ioc-adapter-cli-picocli` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.adapter.in.cli.ExtractCommand#call` | P3 / `BAD_PRACTICE` | Same CLI exception contract: mandatory log side effect, then unchanged propagation. `81595ad9b11bb20d5c3e2e4d618ce772/0` | `policy-noise` | `C2-EX-A` | `triaged` |
| `SB04-091` | `ioc-adapter-transport-smb` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.adapter.out.transport.smb.SmbFileTransport#publish` | P3 / `BAD_PRACTICE` | Failure path removes the remote temporary file, then preserves the primary runtime exception. `94ece55bd8105af5894ae60a0ce84620/0` | `policy-noise` | `C2-EX-B` | `triaged` |
| `SB04-092` | `ioc-app` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.bootstrap.LazyServiceStorage#initialize` | P3 / `BAD_PRACTICE` | Initialization preserves the migration failure; datasource-close failure is suppressed by an executable regression. `11e546190d2ff6319722be2f26d54f11/0` | `policy-noise` | `C2-EX-B/HARDEN-02` | `triaged` |
| `SB04-093` | `ioc-app` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.bootstrap.RemoteChangeFetchListener#fetch` | P3 / `BAD_PRACTICE` | Listener preserves the fetch failure into the keyed executor and suppresses a failing operational observer. `140f08d5e49e74e14a6a25c2a81899a/0` | `policy-noise` | `C2-EX-C/HARDEN-02` | `triaged` |
| `SB04-094` | `ioc-app` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.bootstrap.RemoteChangeWatchLifecycle#start` | P3 / `BAD_PRACTICE` | Partial-start failure closes already-started watches and preserves the original failure. `469a442a692cb759cd32c619ab888ca0/0` | `policy-noise` | `C2-EX-B` | `triaged` |
| `SB04-095` | `ioc-app` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.bootstrap.SliceCompletedPublishListener#publish` | P3 / `BAD_PRACTICE` | Listener preserves the publish failure into the keyed executor and suppresses a failing operational observer. `e42c3c0853dd29c53834f3c4cb10de8c/0` | `policy-noise` | `C2-EX-C/HARDEN-02` | `triaged` |
| `SB04-096` | `ioc-application` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.application.ingest.IngestionService#processClaimed` | P3 / `BAD_PRACTICE` | Failure path preserves the processing failure; failed-run accounting failure is suppressed by an executable regression. `7a89ba4685786a19ea93a39df38a53f1/0` | `policy-noise` | `C2-EX-B/HARDEN-01` | `triaged` |
| `SB04-097` | `ioc-application` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.application.ingest.IngestionService#recoverIncomplete` | P3 / `BAD_PRACTICE` | Recovery preserves an existing diagnostic-bearing runtime failure after required accounting. `1073f127fb3e9ae67c97ccba01a43fa/0` | `policy-noise` | `C2-EX-D` | `triaged` |
| `SB04-098` | `ioc-application` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.application.ingest.IngestionService#recoverIncomplete` | P3 / `BAD_PRACTICE` | Non-diagnostic runtime failures are translated once with the original cause retained. `1073f127fb3e9ae67c97ccba01a43fa/1` | `policy-noise` | `C2-EX-D` | `triaged` |
| `SB04-099` | `ioc-application` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.application.sync.RemoteFetchService#fetchOne` | P3 / `BAD_PRACTICE` | Failed fetch removes its staging file, then propagates the same runtime failure. `5b4c8c3522a8c9b5fc449feff65e94e9/0` | `policy-noise` | `C2-EX-B` | `triaged` |
| `SB04-100` | `ioc-platform-etl` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.platform.etl.PipelineRunner#emitSuppressionSummary` | P3 / `BAD_PRACTICE` | Summary emission does not replace or mask an already established primary failure. `55f447907b635a14f17b160bb4ee9e20/0` | `policy-noise` | `C2-EX-E` | `triaged` |
| `SB04-101` | `ioc-platform-etl` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.platform.etl.PipelineRunner#executeInRunScope` | P3 / `BAD_PRACTICE` | Stage failure remains primary; a failing failure-observer is suppressed by an executable regression. `bffd062e9915fa3e76d30e8510c74f45/0` | `policy-noise` | `C2-EX-E/HARDEN-02` | `triaged` |
| `SB04-102` | `ioc-platform-etl` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.platform.etl.PipelineRunner#executeInRunScope` | P3 / `BAD_PRACTICE` | Checked scope-close and operational observer failures remain secondary to unchecked execution failure. `bffd062e9915fa3e76d30e8510c74f45/1` | `policy-noise` | `C2-EX-E/HARDEN-02` | `triaged` |
| `SB04-103` | `ioc-platform-etl` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.platform.etl.PipelineRunner#executeInRunScope` | P3 / `BAD_PRACTICE` | Alternate cleanup branch retains the primary runtime exception and suppression order. `bffd062e9915fa3e76d30e8510c74f45/2` | `policy-noise` | `C2-EX-E/HARDEN-02` | `triaged` |
| `SB04-104` | `ioc-platform-etl` | `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `com.iocextractor.platform.etl.PipelineRunner#runWithOutcome` | P3 / `BAD_PRACTICE` | Observer/accounting failure handling deliberately preserves the original unchecked contract. `304748d9178334ff7558bfff33701d2d/0` | `policy-noise` | `C2-EX-E` | `triaged` |
| `SB04-105` | `ioc-adapter-transport-smb` | `BC_UNCONFIRMED_CAST_OF_RETURN_VALUE` | `com.iocextractor.adapter.out.transport.smb.SmbjChangeNotifySessionFactory#open` | P3 / `STYLE` | Shared typed guard now rejects a non-disk SMB share as non-retryable `NOT_FOUND` configuration failure. `bf73ded51595f0753a7623e7e67a0d9d/0` | `resolved-by-fix` | `C2-MIX-A/MIX-FIX` | `verified absent` |
| `SB04-106` | `ioc-adapter-transport-smb` | `BC_UNCONFIRMED_CAST_OF_RETURN_VALUE` | `com.iocextractor.adapter.out.transport.smb.SmbjShareClientFactory#open` | P3 / `STYLE` | Same shared typed guard replaces unchecked cast and pins permanent disposition. `5ecd53a230db10168007d19f7ab511b1/0` | `resolved-by-fix` | `C2-MIX-A/MIX-FIX` | `verified absent` |
| `SB04-107` | `ioc-platform-diagnostics` | `CT_CONSTRUCTOR_THROW` | `com.iocextractor.diagnostics.DiagnosticException#<init>` | P2 / `BAD_PRACTICE` | Exception is final and its null rejection remains an executable constructor contract. `c5d8d97ed68c1e802a05a8641c122513/0` | `resolved-by-fix` | `C2-MIX-B/MIX-FIX` | `verified absent` |
| `SB04-108` | `ioc-adapter-store-jdbc` | `DB_DUPLICATE_SWITCH_CLAUSES` | `com.iocextractor.adapter.out.store.jdbc.JdbcPublishLedger#requireTransition` | P3 / `STYLE` | Redundant post-validation branches removed; transition matrix remains covered by ledger regressions. `fc72f0cd33f6875eefdc46492ca2ebdb/0` | `resolved-by-fix` | `C2-MIX-C/MIX-FIX` | `verified absent` |
| `SB04-109` | `ioc-adapter-sink-csv` | `DLS_DEAD_LOCAL_STORE_OF_NULL` | `com.iocextractor.adapter.out.sink.csv.CsvSliceMaterialization#beginArtifact` | P3 / `STYLE` | Dead null store removed; existing printer-first cleanup ownership is unchanged and tested. `9bb6d5a90d0adb82eee2487e06e36986/0` | `resolved-by-fix` | `C2-MIX-C/MIX-FIX` | `verified absent` |
| `SB04-110` | `ioc-app` | `DM_CONVERT_CASE` | `com.iocextractor.bootstrap.LoggingPipelineDecisionTracer#identity` | P3 / `I18N` | Machine identity uses `Locale.ROOT`; Turkish-default-locale regression pins the token. `efd060cca1593aff8e79b4d9d322cad5/0` | `resolved-by-fix` | `C2-MIX-D/MIX-FIX` | `verified absent` |
| `SB04-111` | `ioc-application` | `DM_CONVERT_CASE` | `com.iocextractor.application.ingest.SourceKey#<init>` | P3 / `I18N` | Public source-key normalization uses `Locale.ROOT`; Turkish-default-locale regression pins the value contract. `5f8022e53204730ff75bf5cbf2483fcc/0` | `resolved-by-fix` | `C2-MIX-D/MIX-FIX` | `verified absent` |
| `SB04-112` | `ioc-application` | `DM_CONVERT_CASE` | `com.iocextractor.application.pipeline.stage.ExtractIndicatorsStage#lambda$trace$0` | P3 / `I18N` | Enum-derived trace outcome uses `Locale.ROOT` and is covered under Turkish default locale. `13693251420f8991d7cb5b687d971fee/0` | `resolved-by-fix` | `C2-MIX-D/MIX-FIX` | `verified absent` |
| `SB04-113` | `ioc-adapter-cli-picocli` | `REC_CATCH_EXCEPTION` | `com.iocextractor.adapter.in.cli.HealthCommand#parse` | P3 / `STYLE` | Fail-soft fallback catches only Jackson processing failures; malformed JSON regression remains null-returning. `1ad4dc55569667eed6a663165263ecc7/0` | `resolved-by-fix` | `C2-MIX-E/MIX-FIX` | `verified absent` |
| `SB04-114` | `ioc-adapter-cli-picocli` | `REC_CATCH_EXCEPTION` | `com.iocextractor.adapter.in.cli.HealthCommand#prettyOrRaw` | P3 / `STYLE` | Pretty fallback catches only Jackson processing failures and returns malformed raw payload unchanged. `ab571868a64e58fa53c90804901eefd8/0` | `resolved-by-fix` | `C2-MIX-E/MIX-FIX` | `verified absent` |
| `SB04-115` | `ioc-platform-diagnostics` | `SE_BAD_FIELD` | `com.iocextractor.diagnostics.DiagnosticException#diagnostic` | P3 / `BAD_PRACTICE` | No Java exception-serialization boundary exists; diagnostic models intentionally remain serialization-neutral. `83f71337c877cf0b7fea59b1b8525a89/0` | `false-positive` | `C2-MIX-F` | `triaged` |
| `SB04-116` | `ioc-adapter-store-jdbc` | `UPM_UNCALLED_PRIVATE_METHOD` | `com.iocextractor.adapter.out.store.jdbc.JdbcIngestionLedger#inTransaction` | P3 / `PERFORMANCE` | Dead transaction helper exposed a false single-writer assumption: `ING-10` permitted recovery/poller overlap while terminal transitions were blind find-then-update. `9a17a9287388b204002e5eaaca17d43c/0` | `fix-now` | `IR-03` | `resolved and verified by ING-10 I0..I4` |
| `SB04-117` | `ioc-adapter-store-jdbc` | `VA_FORMAT_STRING_USES_NEWLINE` | `com.iocextractor.adapter.out.store.jdbc.JdbcSnapshotSliceReader#readCoverage` | P2 / `BAD_PRACTICE` | Newline belongs to SQL whitespace inside a text block, not platform text output. `e5faaaaecb31abb88cbaaffef4e60c2a/0` | `false-positive` | `C2-MIX-G` | `triaged` |
| `SB04-118` | `ioc-app` | `VA_FORMAT_STRING_USES_NEWLINE` | `com.iocextractor.bootstrap.IocConfigurationFailureAnalyzer#description` | P2 / `BAD_PRACTICE` | Operator description now uses `%n`; exact platform-separator output is covered. `4546273d871867e68096f7b01f563efb/0` | `resolved-by-fix` | `C2-MIX-H/MIX-FIX` | `verified absent` |

### C1 SQL/security evidence

SQL injection risk assessed separately for dynamic identifiers, SQL grammar
tokens and runtime values. JDBC parameters cannot bind identifiers, so a
nonconstant query is not itself safe or unsafe: the deciding evidence is the
origin and validation of each interpolated fragment. Вывод ограничен текущим
production wiring: для `SB04-001..012` не найден достижимый путь от внешнего
или operator-controlled значения к SQL grammar без fail-closed validation.

| Evidence ref | Finding IDs | Dynamic fragment provenance | Trust boundary / conclusion |
|---|---|---|---|
| `C1-SQL-A` | `SB04-001..002` | Configured artifact/business-column names match `[A-Za-z][A-Za-z0-9_]*`; derived storage identifiers such as `_created_at` are revalidated with `[A-Za-z_][A-Za-z0-9_]*`; every dynamic identifier is quoted, SQL type belongs to `TEXT/INTEGER/REAL/BLOB/NUMERIC`, and reconciler generates the plan internally | The broader validator exists only for adapter-owned internal identifiers; both patterns exclude SQL grammar. Arbitrary SQL cannot reach private `apply`; analyzer cannot model the generated-DDL contract; false positive |
| `C1-SQL-B` | `SB04-003`, `SB04-008..010` | Repository first resolves caller artifact through immutable schema map; table/column names are validated again by `quote`; row key, ID, source and timestamps use bind parameters | Operator-controlled schema metadata may choose an allowed identifier, but cannot introduce SQL grammar. Runtime artifact values never become SQL text; false positive |
| `C1-SQL-C` | `SB04-004..005` | String PRAGMA names replaced with result-typed private enums; exhaustive switches invoke four literal statements: `user_version`, `foreign_keys`, `journal_mode`, `quick_check` | Compiler rejects values outside the closed enum sets and SpotBugs sees literal execute-sites; both findings are removed instead of baselined |
| `C1-SQL-D` | `SB04-006` | `initializePersistentPragmas` receives settings returned by adapter-owned `SqlitePragmaPolicy`: `encoding=UTF-8`, `autoVacuum=INCREMENTAL`, `journalMode=WAL` | The operator-provided tuning preset selects a closed enum/preset and cannot supply any concatenated token. The only P1 SQL finding is not exploitable under current wiring; false positive |
| `C1-SQL-E` | `SB04-007` | SpotBugs groups two execute sites: a code-owned migration body loaded by `ServiceSchemaMigrations`/`DataframeFormatMigrations` from packaged resources, and `PRAGMA user_version=` plus a positive Java `int`; versions must be contiguous from 1 | Neither site accepts runtime/operator SQL text under production wiring. Migration SQL is a trusted deployment artifact, while the PRAGMA suffix has no string injection surface; false positive |
| `C1-SQL-F` | `SB04-011..012` | Export plan artifact must exist in immutable schema map; requested columns must be a subset of declared schema columns and pass identifier validation | Only validated, quoted identifiers determine query shape; artifact value in the coverage predicate is bound. Export data cannot alter SQL grammar; false positive |

#### C1 SQL/security regression contract

Six focused cases pin the trust-boundary argument without changing production
behavior:

- `SqlTrustBoundaryTest` rejects SQL-shaped artifact name, column name and SQL
  type before schema/query generation;
- `SqlitePragmaPolicyTest` rejects a SQL-shaped tuning preset before PRAGMA
  generation;
- `JdbcArtifactRepositoriesTest` proves that `JdbcArtifactIdBaseline` rejects
  an SQL-shaped artifact name and that SQL-shaped IOC/source values are bound,
  stored verbatim and leave both artifact and revision tables operational.

This disposition must be reviewed again if migration bodies become external,
the configured/internal identifier or type allowlists are widened, or another
query-shape builder bypasses the immutable schema map. Health PRAGMA expansion
must extend the typed enum and exhaustive literal switch together.

Resource ownership was also checked on this surface: every flagged `Connection`,
`Statement`/`PreparedStatement` and `ResultSet` is covered by
try-with-resources. SQL triage found no resource/lifecycle candidate and no
immediate security risk. The exact suppressions are deliberately deferred to
`C3`; each selector must remain instance/class-method scoped and retain the
evidence reference above.

### C1 correctness evidence

| Evidence ref | Finding IDs | Contract evidence | Conclusion |
|---|---|---|---|
| `IR-01` | `SB04-013..014` | Project resolves `slf4j-api:2.0.18`. Official [`LoggingEventBuilder` Javadoc](https://www.slf4j.org/apidocs/org/slf4j/spi/LoggingEventBuilder.html) marks both calls `@CheckReturnValue` and says the return is a builder, "usually this". `LogEvent` accepts the provider-neutral `Logger` interface but ignores this result before continuing on the original builder | Confirmed abstraction-contract defect. Current Logback behavior mutates the same builder and `LogEventTest` proves event fields are emitted, so no current data-loss reproduction exists; nevertheless a compliant copy-returning provider can lose structured fields and cause. One narrow chaining/assignment fix should resolve both findings before baseline |
| `C1-COR-A` | `SB04-015` | `ArtifactFilter.none()` caches one immutable empty filter, while normal bootstrap construction uses the public two-list constructor for configured predicates | No singleton identity/lifecycle contract exists. Making the constructor private would break the intended API; false positive |

`IR-01` needs a provider-contract regression test, not only the existing Logback
integration test: a test builder that returns a successor instance must prove
that all key-values and the cause reach the exact instance on which `log` is
called. No production edit is made during `C1`.

### C1 concurrency evidence

| Evidence ref | Finding IDs | Ownership and happens-before evidence | Conclusion |
|---|---|---|---|
| `C1-CON-A` | `SB04-016` | The original singleton writer serialized every `stage` call with its monitor and held that monitor from `active` assignment through synchronous `SnapshotSliceReader.stream` callbacks and cleanup. No in-contract race existed. The follow-up nevertheless removed `active` and the writer-level callback forwarding: each `stage` operation now owns a local `CsvSliceMaterialization` passed directly to the reader; a regression proves that callback failure cannot contaminate the next operation | Resolved by structural hardening. The callback session no longer depends on mutable writer state, while `stage` remains synchronized for the existing filesystem/recovery serialization contract |

Other writer operations that can overlap with staging are synchronized on the
same monitor. The `Files.walk` stream used by discard is closed explicitly. No
resource leak or immediate concurrency risk was confirmed on this surface.

### C1 nullable-path and resource evidence

| Evidence ref | Finding IDs | Path provenance / edge case | Conclusion |
|---|---|---|---|
| `C1-NP-A` | `SB04-017`, `SB04-020`, `SB04-024..028`, `SB04-031..037` | Every dereferenced path is an immediate entry returned by `Files.list(verifiedDirectory)`; later methods receive the same materialized child list | A directory entry has a leaf name. SpotBugs does not carry this NIO stream invariant through lambdas/callers; false positives |
| `C1-NP-B` | `SB04-018..019` | `fileName` already handles the nullable result and repeats the call on immutable `Path`. Move target is a safe leaf resolved under a nonblank configured processing/done/failed directory | Production target has a parent; no reachable null under validated daemon configuration. Revisit if lifecycle directories cease to be configured roots |
| `IR-02` | `SB04-021..023` | Parent-null output such as `masks.csv` is supported by the `.` fallback. A filesystem-root output also satisfies current `@NotBlank` config validation, but `root.getFileName()` is null | Root is not a valid projection file. Current outcome is fail-safe but is an unhelpful runtime NPE. Add collect-all semantic validation plus local defensive extraction of parent/file name; one narrow change resolves all three findings without changing valid outputs |
| `C1-NP-E` | `SB04-029..030` | The executable root regression overturned the initial control-flow assumption: the current provider opens the filesystem root, then `getFileName()` returns null. A shared `resourceName` guard now rejects any path without a leaf before opening/parsing; `extension` uses the same guard. `TikaSourceReaderDiagnosticTest#preserves_filesystem_root_as_typed_read_failure` pins the typed `SOURCE.READ_FAILED` outcome | Analyzer signal was valid, although the old NPE was wrapped. Both findings are resolved by explicit source-path validation and removed from the baseline |
| `C1-NP-F` | `SB04-038..039` | Inbox is normalized to an absolute path; remote `leafName` rejects blank, dot and separator-bearing names. Normal and suffixed candidates are therefore direct inbox children | Both candidates necessarily have `parent == inbox`; false positives |

Resource-handle review covered the flagged dataflows on all `C1` surfaces:
JDBC connections/statements/result sets, `Files.list`/`Files.walk` streams, Tika
input streams, CSV printers and fetch `FileChannel`s use try-with-resources.
There are no raw resource-pattern findings and no `SB04-001..039` instance
needs reclassification as a resource leak; the C1 raw-candidate count is
therefore zero. This conclusion is not a repo-wide manual resource audit.

### C2 representation ownership evidence

Из 49 `EI_EXPOSE_REP*` findings 44 обозначали реальные mutable aliases. Follow-up
`REP-FIX` устранил их null-preserving defensive snapshots: входные `null`, null
elements/values и порядок сохраняются до collect-all validation, caller mutation
не проникает внутрь record, а accessor не позволяет обратную mutation. Все 22
constructor findings и четыре adapter-accessor findings исчезли. 18 generated
`IocProperties` accessors остаются analyzer false positives: detector не выводит,
что private copied backing collection больше не имеет внешнего mutable owner.
Пять первоначальных immutable/lifecycle cases также остаются false positive.

| Evidence ref | Finding IDs | Semantic evidence | Disposition, owner and exit condition |
|---|---|---|---|
| `C2-REP-A` | `SB04-040..041`, `SB04-067..068` | Adapter-bound include/exclude lists сохранялись и возвращались без copy | `resolved-by-fix`; `REP-FIX` добавил null-preserving immutable snapshots и mutation-isolation regressions |
| `C2-REP-B` | `SB04-042`, `SB04-044`, `SB04-069..070` | CSV adapter records удерживали caller-owned transform list/accepted-type set | `resolved-by-fix`; nullable list/set snapshots и direct-construction regressions, findings отсутствуют |
| `C2-REP-C` | `SB04-043`, `SB04-045`, `SB04-066` | `Stream.toList`, `List.copyOf` в plan resolution и compact constructor event payload уже создают immutable snapshots | `false-positive`; review only if construction path перестанет копировать либо payload станет mutable |
| `C2-REP-D` | `SB04-046..063`, `SB04-071..088` | Spring-bound `IocProperties` records сохраняли collection/map aliases | Constructors `SB04-071..088` resolved; accessors `SB04-046..063` стали `false-positive` (`REP-FIX-FP`): real-binding, caller-isolation, unmodifiable-accessor и collect-all regressions доказывают null-preserving snapshots |
| `C2-REP-E` | `SB04-064..065` | `LazyServiceStorage` намеренно выдаёт owned shared datasource, а `TransportRegistry.Binding` хранит и делегирует тому же lifecycle-managed adapter | `false-positive`; review if resource ownership выйдет за bootstrap или registry начнёт моделироваться как value object |

Безусловный `copyOf` не является корректным механическим fix для binding
records: null и частично некорректные значения должны дойти до collect-all
semantic validation, а не превратиться в преждевременный constructor failure.
Поэтому `REP-FIX` использует defensive `ArrayList`/`LinkedHashMap`/`LinkedHashSet`
под unmodifiable view: это закрывает alias, сохраняя validation semantics.

### C2 exception-flow evidence

Все 16 исходных `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` findings и два
post-inventory случая — `policy-noise`, а не analyzer false positives: SpotBugs
верно распознаёт catch/rethrow unchecked exception, но его generic policy
запрещает конструкцию, которая является частью documented boundary contract.
Повторный аудит при этом обнаружил пять методов, где вторичная ошибка могла
заменить primary. `HARDEN-01` исправил `processClaimed`; `HARDEN-02` исправил
`LazyServiceStorage`, оба event listener и `PipelineRunner`. Regression tests
проверяют identity primary failure и точную suppressed-цепочку. В остальных
контрактах уже существующее cleanup/accounting/translation сохраняет исходный
runtime type, stack и cause.

| Evidence ref | Finding IDs | Semantic contract | Review trigger |
|---|---|---|---|
| `C2-EX-A` | `SB04-089..090` | CLI command пишет обязательный failure log и отдаёт тот же runtime failure Picocli boundary | Command/exit-code contract начинает требовать другого exception type |
| `C2-EX-B` | `SB04-091..092`, `SB04-094`, `SB04-096`, `SB04-099` | Failure path сначала закрывает/удаляет частично созданный ресурс либо фиксирует failed run, затем rethrow; `SB04-092/096` hardened | Cleanup становится asynchronous или меняет ownership/accounting contract |
| `C2-EX-C` | `SB04-093`, `SB04-095` | Event listener обновляет health/observer и propagates failure в keyed executor; observer failure теперь suppressed | Executor получает новый retry/swallow contract |
| `C2-EX-D` | `SB04-097..098` | Recovery сохраняет уже диагностированный failure или ровно один раз оборачивает недиагностированный, удерживая cause | Diagnostic taxonomy или recovery boundary изменяется |
| `C2-EX-E` | `SB04-100..104` | `PipelineRunner` разделяет primary unchecked failure и checked close/summary/observer failures, не позволяя вторичным ошибкам маскировать первичную | Меняется run-scope/observer/suppression protocol |

### C2 mixed-pattern evidence

| Evidence ref | Finding IDs | Semantic evidence | Disposition and exit condition |
|---|---|---|---|
| `C2-MIX-A` | `SB04-105..106` | Общий typed guard допускает только `DiskShare`; неверный share type получает existing permanent `NOT_FOUND/FAIL` disposition | `resolved-by-fix`; shared guard regression и оба findings отсутствуют |
| `C2-MIX-B` | `SB04-107` | `DiagnosticException` final; constructor по-прежнему fail-fast отклоняет null | `resolved-by-fix`; null-contract regression, finding отсутствует |
| `C2-MIX-C` | `SB04-108..109` | Redundant switch tail и dead null store удалены без изменения transition/cleanup contracts | `resolved-by-fix`; существующие ledger/slice regressions зелёные, findings отсутствуют |
| `C2-MIX-D` | `SB04-110..112` | Machine tokens/value normalization используют `Locale.ROOT` | `resolved-by-fix`; три Turkish-locale regressions, findings отсутствуют |
| `C2-MIX-E` | `SB04-113..114` | Health CLI fail-soft перехватывает только `JsonProcessingException` | `resolved-by-fix`; malformed parse/raw regressions, findings отсутствуют |
| `C2-MIX-F` | `SB04-115` | Java exception serialization boundary в архитектуре отсутствует; domain/platform diagnostics намеренно не привязаны к Java serialization | `false-positive`; review при появлении remote exception transport или Java object serialization |
| `IR-03` | `SB04-116` | Неиспользуемые `TransactionTemplate`/`inTransaction` соседствовали с неверным комментарием о single writer; `ING-10` доказал overlap startup recovery и poller при blind find→update terminal transitions | `resolved and verified`: helper удалён; lifecycle barrier, per-source-key serialization и atomic/CAS transitions реализованы в `ING-10` I0..I4; full reactor и report-integrity gates прошли |
| `C2-MIX-G` | `SB04-117` | Newline находится внутри SQL text block и является SQL whitespace, а не платформенным text output | `false-positive`; review only if строка станет user-visible output |
| `C2-MIX-H` | `SB04-118` | Failure description использует `%n` так же, как action platform separator | `resolved-by-fix`; exact analyzer output regression, finding отсутствует |

Первоначальный итог `C2`: все 79 оставшихся findings получили disposition — 55
`accepted-legacy`, 23 `false-positive`, 1 `fix-now` (`IR-03`). После повторного
exception-flow review 16 из этих 23 перенесены в точную категорию
`policy-noise`; это изменение taxonomy, а не raw report. Суммарно по `C1+C2`
initial triage дал 57 false positives, 55 accepted legacy, 3 fix-now и 3
companions `resolved-by-related-fix` = 118. C1 follow-up перевёл
`SB04-004..005` из false-positive disposition в `resolved-by-fix`, поэтому
актуальная раскладка исходного inventory: 39 false positives, 16 policy noise,
55 accepted legacy, 3 fix-now и 5 resolved findings. Ни один baseline selector
в `C2` не создавался.

## 8. Immediate-risk register

| Finding IDs | Risk | Reproduction/contract evidence | Proposed change | Tests | Decision/state |
|---|---|---|---|---|---|
| `SB04-013..014` (`IR-01`) | Provider-neutral structured logging can drop key-values or cause when a compliant `LoggingEventBuilder` returns a successor instead of mutating itself | Official SLF4J API requires consuming the return; current Logback-backed tests prove only the same-instance implementation | Thread returned builder through `addKeyValue` and `setCause`, then call `log` on the final builder; no logging taxonomy or field values change | Existing Logback test plus copy-returning fake-builder regression for fields and cause | Resolved in `C3`; both findings absent from the refreshed raw report |
| `SB04-021..023` (`IR-02`) | A syntactically valid but semantically invalid projection root path fails later with NPE; repeated nullable calls also keep two false-positive companions in the report | `IocProperties.Sink.Artifact.path` is only `@NotBlank`; `CsvArtifactProjection.tempPath` dereferences `target.getFileName()` without a guard | Reject non-leaf projection paths through collect-all config validation; locally extract/validate file name and parent once before temp creation | Configuration validation/binding test for root path plus adapter tests for leaf-without-parent and nested output | Resolved in `C3`; all three findings absent while parentless leaf output remains supported |
| `SB04-116` (`IR-03`) | File-ledger terminal state could be overwritten or left stale when startup recovery and poller overlapped; unused transaction helper revealed, but could not solve, the invalid single-writer assumption | Deterministic I0 characterization reproduced branch overlap; adapter TCK reproduced the competing terminal transition boundary | `autoStartup=false` coordinator, shared per-source-key guard, post-admission re-read and conditional file/JDBC transitions; dead helper removed | Coordinator latches, keyed-guard concurrency, 8-case TCK per adapter, restart regression, watched-inbox E2E and full verify | Resolved and verified; finding absent from refreshed reports |

### Post-C2 ING-10 report delta

These findings were introduced after the immutable 118-row C0 inventory. They
are tracked separately until C3 reconciles the current report into the reviewed
baseline; none is suppressed.

| Delta ID | Module / finding | Location and fingerprint | Disposition/evidence |
|---|---|---|---|
| `I4-SB-01` | `ioc-platform-concurrency` / `UL_UNRELEASED_LOCK_EXCEPTION_PATH` | `SynchronousKeyedExecutionGuard#execute`; `d8016a3601f558b990d04496f10ccf2d/0` | `resolved-by-fix`: release is isolated from user work; a deliberately corrupted-state regression proves that a release failure is suppressed onto an existing primary failure and remains primary after successful work. The current report no longer contains the signal |
| `I4-SB-02` | `ioc-platform-concurrency` / `VO_VOLATILE_INCREMENT` | `SynchronousKeyedExecutionGuard#lambda$0`; `5845659a1c7ed4aec897e08be5c8be7b/0` | `false-positive`: the increment is inside same-key `ConcurrentHashMap.compute`; `volatile` supports aggregate snapshot visibility, not compound mutation atomicity |
| `I4-SB-03` | `ioc-platform-concurrency` / `VO_VOLATILE_INCREMENT` | `SynchronousKeyedExecutionGuard#lambda$1`; `48f259f3ad4cd22ec8067afa01f08ca/0` | `false-positive`: the decrement is governed by the same per-key remapping lock and validated against the admitted state |
| `I4-SB-04` | `ioc-adapter-ingest` / `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `IngestionStartupCoordinator#run`; `d56f19992d2df7d7a5419935e72e36b4/0` | `policy-noise`: startup must rethrow the original recovery/start failure after stopping intake and publishing `FAILED`; swallowing or wrapping it would weaken fail-closed startup |
| `FUP-SB-01` | `ioc-application` / `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | `IngestionService#ingestGuarded`; `26ca8efc8ada84b88a4e9f1f98d7847d/0` | `policy-noise`, same contract as `C2-EX-B`: after mandatory physical-failure cleanup the original typed failure must reach the final diagnostic boundary unchanged; wrapping or swallowing it would lose exact root-cause delivery |

Если immediate risk не подтверждён, register остаётся пустым, но `C1` содержит
явное evidence, почему проверенные candidates безопасны в действующем contract.

## 9. Baseline register

| Baseline ID | Finding IDs | Exact selector | Kind | Rationale | Owner | Review/exit condition | State |
|---|---|---|---|---|---|---|---|
| `BL-SQL` | 10 findings in `C1-SQL-A/B/D/E/F` | Pattern + exact JDBC class/method | `false-positive` | Allow-list validated/quoted identifiers, bound values and code-owned migrations | `adapter-store-jdbc` | External migrations, grammar/allow-list expansion or new query-shape builder | `reviewed` |
| `BL-NIO` | 18 findings in `C1-NP-A/B/F` | `NP_*` + exact class/method | `false-positive` | Proven direct-child, configured-root or inbox-leaf provenance | Owning path modules | Path provenance or validated root/protocol changes | `reviewed`; two Tika findings from `C1-NP-E` resolved and removed |
| `BL-C1-CONTRACT` | `SB04-015..016` | Pattern + exact class/member | `false-positive` | Immutable flyweight and synchronous monitor-confined callback contracts | `adapter-sink-csv` | Async callback or new identity/lifecycle semantics | `reviewed` |
| `BL-REP-FP` | 5 findings in `C2-REP-C/E` | `EI_EXPOSE_REP*` + exact class/member | `false-positive` | Immutable snapshots or deliberately shared lifecycle resources | Owning application/bootstrap modules | Construction stops copying or ownership crosses bootstrap | `reviewed` |
| `BL-REP-SNAPSHOT-FP` | `SB04-046..063` | `EI_EXPOSE_REP` + exact generated accessor/field | `false-positive` | Null-preserving defensive copies have no external mutable owner; SpotBugs does not prove the wrapper/backing-copy relationship | `ioc-app/configuration` | Copy removal, mutable accessor or validation-semantics change | `reviewed` |
| `BL-EXCEPTION` | 18 findings in `C2-EX-A..E`, `I4-SB-04`, `FUP-SB-01` | `THROWS_*` + exact class/method | `policy-noise` | Generic checked-exception policy is inapplicable to documented unchecked boundaries; five audited methods required explicit secondary-failure hardening | Owning boundary modules | Async/retry/translation/swallow contract change | `reviewed` |
| `BL-MIX-FP` | `SB04-115`, `SB04-117` | Pattern + exact class/method | `false-positive` | No Java serialization boundary; SQL newline is grammar whitespace | Diagnostics/JDBC owners | Serialization added or SQL becomes user-visible | `reviewed` |
| `BL-GUARD` | `I4-SB-02..03` | `VO_*` + exact class/field | `false-positive` | Same-key `compute` serializes accounting; volatile serves visibility | `platform-concurrency` | Any accounting mutation leaves same-key `compute` | `reviewed` |

`Kind` принимает `false-positive`, `policy-noise` или `accepted-legacy`. Broad package,
category или pattern-only selector требует отдельного scope decision и по
умолчанию запрещён.

## 10. Run evidence

| Run | Commit/tree | Command | Modules/reports | Raw/accepted findings | Errors/missing classes | Duration | Result |
|---|---|---|---|---|---|---|---|
| Start snapshot | `5dd0fd4`, clean | prior canonical `make verify` | 19 module pairs + aggregate | 118 / 118 | 0 / 0 | see ledger | passed |
| `C0` clean baseline | `5dd0fd4`; только documentation worknote/ledger/matrix изменены | `make clean`, затем `make verify` | 24/24 reactor; 19 module pairs + aggregate | 118 / 118 | 0 / 0 | `02:01` Maven wall clock | passed |
| `C1` current-provider contract check | `5dd0fd4`; documentation-only tree | `make test-one MODULE=platform/platform-observability TEST=LogEventTest` | 5-project focused reactor; 4 tests | N/A | N/A | `00:04` wall clock | passed |
| `C1` canonical verification | `5dd0fd4`; documentation-only tree | `make verify` | 24/24 reactor; 19 module pairs + aggregate | 118 / 118 | 0 / 0 | `01:22` Maven wall clock | passed |
| `C1` SQL trust-boundary hardening | `5dd0fd4`; test/docs-only C1 tree | `make test-module MODULE=adapters/adapter-store-jdbc` | 10-project focused reactor; adapter module 81 tests | N/A | N/A | `00:18` wall clock | passed |
| `C1` hardening canonical verification | `5dd0fd4`; test/docs-only C1 tree | `make verify` | 24/24 reactor; 19 module pairs + aggregate | 118 / 118 | 0 / 0 | `01:30` Maven wall clock | passed |
| `C2` semantic-triage verification | `2632201`; documentation-only C2 tree | `/usr/bin/time make verify` | 24/24 reactor; 19 module pairs + aggregate | 118 / 118; no baseline | 0 / 0 | `01:32.52` process elapsed | passed |
| `ING-10/I4` verification | I0..I4 final tree | `make docs`, focused tests, then `make verify` | 24/24 reactor; 19 module pairs + aggregate | 121 / 121; no baseline; `SB04-116` absent | 0 / 0 | `01:30` Maven wall clock | passed |
| `ING-10 observability follow-up` | `17baded`; uncommitted follow-up tree | focused 39 tests, `make docs`, `make clean`, then `make verify` | 24/24 reactor; 19 module pairs + aggregate | 122 / 122; no baseline; `FUP-SB-01` classified | 0 / 0 | `01:37` Maven wall clock (`real 98.44 s`) | passed |
| `ING-10 observability repeat` | same uncommitted follow-up tree on clean-derived bytecode | `make docs`, then `make verify` | 24/24 reactor; 19 module pairs + aggregate | 122 / 122; identical to clean run | 0 / 0 | `01:31` Maven wall clock (`real 92.20 s`) | passed |
| `C1 review follow-up` | `6e8c8b8`; C1 fix/docs tree | focused JDBC reactor, `make docs`, then `make verify` | focused adapter 83 tests; full 24/24 reactor; 19 module pairs + aggregate | 120 / 120; `SB04-004..005` absent; no baseline | 0 / 0 | `01:54` Maven wall clock (`real 115.25 s`) | passed |
| `C2 audit follow-up` | post-`94b1b5d` working tree | focused guard/coordinator reactor, `make docs`, then timed `make verify` | focused 11-project reactor, 10 selected tests; full 24/24 reactor; 19 module pairs + aggregate | 119 / 119; `I4-SB-01` absent; no baseline | 0 / 0 | `01:41` Maven wall clock (`real 102.41 s`) | passed |
| `C3 pre-baseline fixes` | post-`75ca72a` C3 tree; baseline still empty | three focused test runs, then timed `make verify` | 5 `LogEventTest`, 5 `CsvArtifactProjectionTest`, 44 `IocPropertiesBindingTest`; full 24/24 reactor; 19 module pairs + aggregate | 114 / 114; exactly `SB04-013..014` and `SB04-021..023` absent | 0 / 0 | `02:28` Maven wall clock (`real 150.52 s`) | passed |
| `C3 reviewed baseline` | same uncommitted C3 tree with one inherited filter | `/usr/bin/time make verify`, then independent XML reconciliation | 24/24 reactor; 19 module pairs + aggregate, all 20 XML/HTML pairs present | 114 raw accepted / 0 visible in modules and aggregate | 0 / 0 | `02:17` Maven wall clock (`real 138.58 s`) | passed |
| `MIX-FIX canonical gate` | local-legacy remediation tree before final analyzer refinement | `make docs`, timed `make verify`, independent aggregate inspection | docs 450/0; 24/24 reactor; full tests and report integrity passed | aggregate exposed 2 remaining `DB`/`DLS` findings after stale selectors were removed | 0 / 0 | `01:33` Maven wall clock (`real 94.48 s`) | follow-up required |
| `MIX-FIX analyzer repeat` | final local-legacy remediation tree | focused ledger/slice tests, then SpotBugs report reactor with aggregate | 19 module pairs + aggregate | 0 visible; all 11 target findings absent, no new signal | 0 / 0 | incremental | passed |
| `C4 diagnostic clean/repeat` | initial `REP-FIX` tree with all 44 stale alias selectors removed | `make clean`, timed `make verify`, immediate timed repeat | 24/24; 836 tests, 2 external SMB skips; 19 module pairs + aggregate | clean report exposed 18 post-fix accessor false positives and 2 compiler-name-dependent VO selector misses | 0 / 0 | `01:35` / `01:32` Maven wall clock | baseline refinement required |
| `C4 final clean` | final `REP-FIX` tree and stable reviewed filter | `make clean`, then `/usr/bin/time -p make verify` | 24/24; 836 tests, 2 external SMB skips; 19 module pairs + aggregate | 77 accepted / 0 visible | 0 / 0 | `01:38` Maven wall clock (`real 99.63 s`) | passed |
| `C4 final immediate repeat` | same clean-derived bytecode and filter | `/usr/bin/time -p make verify` | 24/24; 836 tests, 2 external SMB skips; 19 module pairs + aggregate | 77 accepted / 0 visible; identical to clean run | 0 / 0 | `01:35` Maven wall clock (`real 96.80 s`) | passed |

Финальный evidence включает минимум один clean reactor run и один немедленный
повторный run после применения fixes/baseline.

### C0 structural reconciliation

- Aggregate XML: 118 `BugInstance`, 118 уникальных пар
  `instanceHash + instanceOccurrenceNum`; duplicates отсутствуют.
- Module XML: 19 ожидаемых reports, сумма 118 `BugInstance`.
- Каждый aggregate instance найден ровно в одном module report; missing и
  multi-mapped instances отсутствуют.
- Outputs: 20 XML/HTML пар — 19 module-local и одна aggregate.
- Aggregate summary: 628 production classes, `errors=0`, `missingClasses=0`.
- Priority: P1 — 1, P2 — 81, P3 — 36.
- Category: `BAD_PRACTICE` — 20, `CORRECTNESS` — 3, `I18N` — 3,
  `MALICIOUS_CODE` — 49, `MT_CORRECTNESS` — 1, `PERFORMANCE` — 1,
  `SECURITY` — 12, `STYLE` — 29.

Module counts:

| Module | Findings |
|---|---:|
| `platform-errors` | 0 |
| `platform-diagnostics` | 2 |
| `platform-etl` | 5 |
| `platform-events` | 0 |
| `platform-concurrency` | 0 |
| `platform-observability` | 2 |
| `platform-diagnostics-logging` | 0 |
| `ioc-domain` | 0 |
| `ioc-application` | 9 |
| `adapter-regex-re2j` | 0 |
| `adapter-psl` | 0 |
| `adapter-source-tika` | 2 |
| `adapter-sink-csv` | 16 |
| `adapter-manifest-json-jackson` | 0 |
| `adapter-store-jdbc` | 17 |
| `adapter-transport-smb` | 8 |
| `adapter-ingest` | 8 |
| `adapter-cli-picocli` | 4 |
| `ioc-app` | 45 |
| **Total** | **118** |

## 11. Журнал решений

| Date | ID | Checkpoint | Decision/evidence | Follow-up |
|---|---|---|---|---|
| 2026-08-01 | `D-001` | preparation | Создан отдельный execution worknote; итоговая authority остаётся у goal contract, ledger и status matrix | Начать `C0` с live context и clean report |
| 2026-08-01 | `D-002` | `C0` | Live context подтвердил branch `release-0.3.0`, HEAD `5dd0fd4` и синхронизацию с upstream. `git.dirty=true` вызван только новым worknote; поэтому прежнее `make verify` на том же HEAD имеет `verify.fresh=false` и будет обновлено canonical clean run | Воспроизвести raw reports после очистки всех reactor `target/` |
| 2026-08-01 | `D-003` | `C0` | Canonical build после `make clean` прошёл 24/24 за `02:01`; SpotBugs integrity gate подтвердил полный report set. Aggregate и module XML согласованы 118:118 без duplicate, missing или multi-mapped instances | Назначить `SB04-NNN` детерминированной сортировкой и зафиксировать fingerprint/source location |
| 2026-08-01 | `D-004` | `C0` | Созданы 118 строк `SB04-001..118`; обратная сверка подтвердила contiguous IDs и точное равенство inventory fingerprints aggregate XML. Семантические dispositions не присваивались | `C0=completed`; следующий отдельный проход — `C1` immediate-risk triage |
| 2026-08-01 | `D-005` | `C1` | Live context перед triage: branch `release-0.3.0`, HEAD `5dd0fd4`, upstream synchronized, `verify.fresh=true`; dirty tree содержит только незакоммиченные документы текущего work item | Проследить происхождение всех динамических SQL-фрагментов и зафиксировать поэкземплярное evidence до disposition |
| 2026-08-01 | `D-006` | `C1` | `SB04-001..039` полностью triaged: 34 false positives, 2 `fix-now`, 3 companions `resolved-by-related-fix`. `IR-01` — ignored returned SLF4J builder; `IR-02` — projection root path runtime NPE. SQL injection, in-contract race и raw resource leak не подтверждены; production code/filter не менялись | `C1=completed`; сохранить оба fix groups до `C3`, следующий semantic pass — `C2` |
| 2026-08-01 | `D-007` | `C1` | Документационный gate: 445 OK, 0 errors; focused `LogEventTest`: 4/4; финальный `make verify`: 24/24 reactor за `01:22`, verifier contracts и report-integrity gates прошли, aggregate содержит 118 findings без analyzer errors и missing classes | C1 evidence complete; ждать явного старта `C2` |
| 2026-08-01 | `D-008` | `C1 hardening` | Широкий вывод сужен до current production wiring; `SB04-007` reconciled с обеими XML locations. Добавлены шесть regression cases для identifier/type/PRAGMA allowlists, unreported dynamic ID-baseline path и bound runtime values; focused 10-project run прошёл, adapter module 81/81 | Выполнить docs gate и canonical `make verify`, затем переходить к `C2` |
| 2026-08-01 | `D-009` | `C1 hardening` | `make docs`: 445 OK, 0 errors; canonical `make verify`: 24/24 reactor за `01:30`, build-quality verifier и SpotBugs/CPD report-integrity gates прошли; aggregate/module findings согласованы 118:118 без analyzer errors или missing classes | Hardening evidence complete; повторить canonical gate после финальной записи evidence и оставить `C2` следующим checkpoint |
| 2026-08-01 | `D-010` | `C2` | `SB04-040..118` полностью triaged по первоначальной taxonomy: 55 accepted legacy, 23 false positives и 1 fix-now. Representation review отделил 44 real aliases от 5 immutable/lifecycle-owned exposures; все 16 exception-flow findings требуют unchecked boundary contract | Не создавать baseline до `C3`; для mutable config records сохранить ADR-0016 null/collect-all contract. `D-022` позднее уточняет exception-flow taxonomy и hardening evidence |
| 2026-08-01 | `D-011` | `C2` | `SB04-116` (`IR-03`) связал dead transaction helper в `JdbcIngestionLedger` с уже документированным `ING-10`: recovery и poller могут пересекаться, а terminal states обновляются blind find→write. Simple transaction wrapper не закрывает гонку | Рекомендовать explicit matrix reorder: единый `ING-10/IR-03` lifecycle + per-key + CAS hardening перед `C3`; production change ждать решения пользователя |
| 2026-08-01 | `D-012` | `C2` | Документационный gate: 445 OK, 0 errors; два последовательных canonical `make verify` прошли. Timed run: 24/24 reactor за `01:32.52`, 19 module report pairs + aggregate, 118 raw findings, analyzer errors/missing classes 0/0 | Записать evidence и повторить docs/full gate на финальном C2 tree; затем ждать explicit queue decision |
| 2026-08-01 | `D-013` | queue decision | Пользователь утвердил перенос единого `ING-10/IR-03` hardening перед `BUILD-SPOTBUGS-04/C3`; это отдельный correctness/concurrency predecessor, а не часть baseline mechanics | Сначала спроектировать и реализовать lifecycle barrier, per-source-key serialization и atomic ledger transitions с concurrent regressions; после verification вернуться в `C3` |
| 2026-08-02 | `D-014` | `ING-10/I0..I4` | Пять checkpoint changes реализовали и отдельно закоммитили characterization, startup barrier, shared keyed guard, monotonic adapter transitions и operational closure. Focused I4 run: 21 reactor projects, 50 selected tests, failures/errors/skips 0/0/0 | Выполнить docs/full reactor gate, обновить raw SpotBugs evidence и только затем продолжать `C3` |
| 2026-08-02 | `D-015` | `ING-10/I4 verification` | `make docs` прошёл 448 checks; первый full gate обнаружил misplaced application enum в interfaces-only port package, focused architecture/TCK rerun подтвердил исправление, финальный `make verify` прошёл 24/24 за `01:30`. Aggregate теперь содержит 121 raw finding: `SB04-116` исчез, четыре новых ING-10 signals классифицированы поэкземплярно без suppression | `ING-10/IR-03=verified`; вернуть очередь к `C3`, начиная с reconciliation текущих 121 findings |
| 2026-08-02 | `D-016` | `ING-10 observability follow-up` | Typed `ingest_recover` start/terminal timeline, duplicate disposition and exact transition-conflict delivery получили executable tests и generated catalog entries. Первый incremental report показал 123 findings из-за stale pre-clean bytecode; canonical clean run прошёл 24/24 за `01:37` и согласовал aggregate/modules на 122 findings, 0 analyzer errors/missing classes. Повторный run на clean-derived bytecode прошёл за `01:31` с теми же 122/122. Единственный follow-up signal `FUP-SB-01` первоначально классифицирован как false-positive exception-flow contract без suppression; `D-022` уточняет его как policy noise | Follow-up evidence закрыт; оставить `BUILD-SPOTBUGS-04/C3` следующим checkpoint |
| 2026-08-02 | `D-017` | `C1 review follow-up` | `SEC-INP-3` синхронизирован как `Enforced + Monitored`; configured/internal identifier regex contracts названы раздельно. `JdbcStorageHealthProbe` принимает private result-typed enums и выполняет только literal PRAGMA через exhaustive switches. Focused JDBC report содержит 14 findings и 0 для health probe; canonical 24-project run согласовал 120/120, analyzer errors/missing classes 0/0 | `SB04-004..005=resolved-by-fix`; оставить `C3` следующим checkpoint с уменьшенной baseline surface |
| 2026-08-02 | `D-018` | `C2 audit follow-up` | Primary work failure больше не маскируется release-инвариантом; `users` и lifecycle threading contracts записаны рядом с кодом; startup runner получил явный highest precedence; shared-guard composition contract закреплён; dependency docs ссылаются на POM authority вместо трёх независимых inventory. Focused 10/10 и full 24/24 прошли; current SpotBugs reports согласованы на 119/119, `I4-SB-01` исчез | `I4-SB-01=resolved-by-fix`; четыре post-inventory false positives остаются видимыми без suppression; перейти к `C3` |
| 2026-08-02 | `D-019` | `C3 start` | Live context: clean `75ca72a`, upstream ahead 1, `verify.fresh=true`; aggregate/module reports согласованы на 119 findings, analyzer errors/missing classes 0/0. Из трёх fix-now groups `IR-03` уже resolved; остаются `IR-01` (2 findings) и `IR-02` (3 findings) | Сначала реализовать оба узких fix group с regression tests и подтвердить raw report delta; только затем создавать reviewed baseline для остатка |
| 2026-08-02 | `D-020` | `C3 fixes` | `IR-01` теперь потребляет каждый returned SLF4J builder; copy-returning provider regression подтверждает fields/cause на финальном `log`. `IR-02` collect-all preflight отклоняет filesystem root, adapter сохраняет parentless leaf и выдаёт явную boundary error для root. Focused tests зелёные; полный reactor дал ровно ожидаемые 114/114 без новых findings, analyzer errors/missing classes 0/0 | `SB04-013..014` и `SB04-021..023=resolved-by-fix`; сформировать baseline только для оставшихся 114 reviewed findings |
| 2026-08-02 | `D-021` | `C3 baseline` | Один root-inherited filter содержит 109 exact pattern+class+member selectors для 114 reviewed instances; первоначальная taxonomy: 55 accepted legacy + 59 false positives. Aggregate source inspection подтвердил merge module XML без отдельной filter surface. Full reactor и независимая сверка дали 0 visible findings во всех 19 module reports и aggregate, 0 analyzer errors/missing classes, полный XML/HTML set | `C3=completed`; перейти к отдельному `C4` clean + immediate repeat, не переводя report-only control в blocking mode |
| 2026-08-02 | `D-022` | `THROWS_* review follow-up` | Все 18 exact findings переклассифицированы из analyzer false positive в `policy-noise`: detector видит реальный catch/rethrow, но generic policy неприменима к documented unchecked boundaries. Аудит подтвердил 11 уже защищённых occurrences и выявил пять методов/семь occurrences для hardening. `processClaimed`, `LazyServiceStorage`, event listeners и `PipelineRunner` теперь сохраняют identity primary failure и добавляют accounting/close/observer failure как suppressed; focused и affected-reactor tests зелёные | Baseline selectors не меняются; taxonomy становится 55 accepted legacy + 41 false positives + 18 policy noise. Следующий remediation block — `C2-MIX-A..E/H` |
| 2026-08-02 | `D-023` | `C2 mixed remediation` | Все 11 local accepted-legacy findings `SB04-105..114/118` устранены: SMB share получает shared typed guard и non-retryable existing taxonomy; exception final; redundant/dead code удалён; machine identities locale-neutral; JSON fallback ловит только Jackson failures; operator text использует platform newline. Семь новых regressions плюс существующие ledger/slice tests прошли. Первый canonical aggregate честно показал второй dead null store и одинаковые cases внутри switch; оба устранены, focused tests повторены, финальный module+aggregate report содержит 0 visible/new signals, errors/missing classes 0/0 | 11 stale selectors удалены; baseline стал 103 findings / 98 selectors / 44 accepted legacy. Перейти к 44 mutable aliases |
| 2026-08-02 | `D-024` | `representation remediation` | Все 44 real `EI_EXPOSE_REP*` aliases закрыты null-preserving immutable snapshots в adapter и Spring-binding records. Direct-construction и real-binding regressions проверяют caller isolation, immutable accessors, сохранение null и передачу invalid values в collect-all validation. Clean compilation подтвердила исчезновение 22 constructor и 4 adapter-accessor findings; 18 generated `IocProperties` accessors остаются analyzer false positives | Удалить 26 obsolete selectors, сохранить 18 exact accessor selectors как `REP-FIX-FP`; baseline 77 findings / 72 selectors до C4 stability fix |
| 2026-08-02 | `D-025` | `C4 stability audit` | Первый clean C4 run выявил два видимых `VO_VOLATILE_INCREMENT`: baseline ссылался на compiler-generated `lambda$0/1`, тогда как clean javac создал `lambda$execute$0`/`lambda$release$1`. Семантическая disposition не изменилась; два хрупких selectors заменены одним stable pattern + exact class + field selector | Baseline 77 findings / 71 selectors; повторить C4 с clean bytecode и immediate rerun |
| 2026-08-02 | `D-026` | `C4/C5 closure` | Final clean и immediate repeat прошли 24/24 с одинаковыми 77 accepted / 0 visible findings, 19 module XML/HTML pairs + aggregate, analyzer errors/missing classes 0/0. Оба запуска выполнили 836 tests без failures/errors и с двумя ожидаемыми external SMB skips | `C4/C5=completed`; `BUILD-SPOTBUGS-04=verified`, следующий work item — `BUILD-SPOTBUGS-05` blocking ratchet |
| 2026-08-03 | `D-027` | `IS2 follow-up` | Повторный concurrency review подтвердил отсутствие живого race в исходном monitor-confined synchronous protocol, но выявил устранимую temporal coupling. `CsvArtifactSliceWriter` больше не хранит callback session в поле и не реализует `SnapshotRowConsumer`: локальная materialization передаётся reader напрямую; regression покрывает failure cleanup и следующий успешный stage. Первый full run fail-closed поймал новый compiler-generated `RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE` от try-with-resources; явный non-throwing cleanup в `finally` устранил его без baseline entry | `SB04-016=resolved-by-fix`; stale identity/selector удалены. Focused reactor и повторный 24/24 `make verify` прошли: 837 tests, 2 external skips, 76 accepted / 0 visible, errors/missing classes 0/0 |
| 2026-08-03 | `D-028` | `Tika NP hardening follow-up` | Негативный root regression опроверг прежнюю посылку: текущий provider открывает filesystem root, и старый код получал внутренний NPE на nullable `getFileName()`. NPE не протекал наружу, но SpotBugs signal был валиден. `TikaSourceReader` теперь валидирует leaf через единый `resourceName` guard до открытия/parser invocation; фактический `SourceReader` Javadoc синхронизирован с typed-failure contract | `SB04-029..030=resolved-by-fix`; identities/selectors удалены. Focused test — 3/3, focused Maven verify + SpotBugs — green, 0 source-adapter findings. Current baseline 74 findings / 68 selectors; full `make verify` intentionally not rerun |

## 12. Рабочий change journal

| Change set | Scope | Files/tests | Commit | State |
|---|---|---|---|---|
| `C0-C2 evidence` | Reproducible inventory, full semantic triage и SQL trust-boundary hardening | worknote, build-quality ledger, status matrix, KNOWN-ISSUES; six JDBC security regression cases, focused module runs and canonical verification | `0b99c2b` | committed |
| `ING-10/I0..I4` | Characterization, lifecycle barrier, keyed execution, monotonic transitions and operational closure | production code, reusable TCK, focused concurrency/restart/E2E regressions and durable docs | `f4f011e`, `c3a03e2`, `a44d10f`, `7ce5f8f`, I4 final checkpoint | checkpointed |
| `ING-10 observability follow-up` | Typed recovery lifecycle, duplicate disposition and exact transition-conflict diagnostic | production observer/application code, focused logging/diagnostic regressions, generated catalogs and durable docs | `6e8c8b8` | committed and verified |
| `C1 review follow-up` | Security registry sync, compiler-closed health PRAGMA and test hygiene | health probe, JDBC repository tests, security/threat model, build-quality execution evidence | `94b1b5d` | committed and verified |
| `C2 audit follow-up` | Exception preservation, explicit threading/startup/guard contracts and dependency-map drift reduction | concurrency and coordinator production/tests, application/platform/root docs, refreshed SpotBugs evidence | `75ca72a` | committed and verified |
| `THROWS hardening 1` | Preserve ingestion processing failure when failed-run accounting also fails | `IngestionService`, exact identity/suppressed regression | `1edaf8c` | committed and focused verified |
| `THROWS hardening 2` | Preserve migration/fetch/publish/pipeline failures across close and operational-observer failures | bootstrap/platform production code and exact identity/suppressed regressions | `7075813` | committed and affected-reactor verified |
| `C3 fixes and baseline` | Consume copy-returning SLF4J builders, reject projection filesystem roots and accept only reviewed residual findings | production code/tests/docs, one exact inherited SpotBugs filter and module/aggregate baseline evidence | `8dfc88b`, `0014fb1`, `2ea59e7` | committed and verified |
| `THROWS disposition` | Separate generic boundary-policy noise from secondary-failure defects | worknote, ledger and status matrix | `b98c2eb` | committed |
| `MIX-FIX` | Resolve 11 small accepted-legacy findings and remove their selectors | production code/tests/docs and baseline | `eee527f` | committed and verified |
| `REP-FIX` | Resolve 44 mutable aliases without weakening Spring collect-all validation | adapter/bootstrap value records, mutation/binding/validation regressions and baseline | this change | implementation and C4 clean/repeat verified |
| `IS2 follow-up` | Remove avoidable writer-level callback state while preserving synchronous filesystem serialization | `CsvArtifactSliceWriter`, focused failure-isolation regression, exact baseline and evidence | this change | focused and full reactor verified |

## 13. Completion checklist

- [x] Все 118 исходных findings имеют стабильный ID и disposition.
- [x] Immediate correctness/resource/concurrency risks исправлены или явно не подтверждены evidence.
- [x] Каждый оставленный finding имеет точный selector, rationale, owner и exit condition.
- [x] Module executions и aggregate используют один reviewed baseline.
- [x] Analyzer/report-integrity failures остаются fail-closed.
- [x] Clean и повторный reactor runs детерминированы.
- [x] Build-quality ledger содержит C3 summary и suppression register.
- [x] Status matrix переводит `BUILD-SPOTBUGS-04` в `verified`.
- [x] Следующий work item `BUILD-SPOTBUGS-05` имеет достаточный entry evidence.
