---
title: "0.3.0 build-quality evidence"
version: "0.3.0"
goal_id: "R030-BUILD"
status: "Baseline captured"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-BUILD — Build-quality evidence

Contract: [R030-BUILD](../goals/R030-BUILD-build-quality.md).

Этот ledger хранит изменяемые reports, findings и adoption decisions.
Нормативный scope и rollout rules находятся только в goal contract.

## Принятый evaluation scope

| Control | Scope 0.3.0 | Начальный режим | Возможный результат |
|---|---|---|---|
| SpotBugs | Все применимые production Java modules | Report only | Blocking no-new-findings check в `verify` |
| PMD CPD | Repository-wide production-source duplication | Report only | Diagnostic control + semantic dispositions |
| Maven `dependency:analyze-only` | Dependency hygiene evaluation | Report only | `Adopt / Adopt with exclusions / Defer` |
| PIT | Только `core/ioc-domain`; ведётся в R030-TEST | Diagnostic pilot | `Adopt / Extend / Defer / Reject` |

## Tool evaluation

| Control | Version/config | Local command | CI/report artifact | Runtime | Signal/noise | Owner | Stage |
|---|---|---|---|---:|---|---|---|
| SpotBugs | Maven Plugin `4.10.3.0`, engine `4.10.3`; `effort=Max`, `threshold=Low`, production bytecode only | `make clean && make verify` | Per applicable module: `target/spotbugs/spotbugs.xml` + `spotbugs.html`; aggregate: `build-support/spotbugs-report/target/spotbugs/`; CI artifact `spotbugs-reports-<run>` | `93.00 s` clean reactor wall time; `+34.61 s` / `+59.3%` к BASE | 118 raw findings / 19 patterns; заметны framework, nullable-API и controlled-SQL classes of noise | `R030-BUILD` | `report-only` |
| PMD CPD aggregate | Maven Plugin `3.28.0`, bundled PMD `7.17.0`; Java production sources, `minimumTokens=75`, identifiers/literals/annotations significant | `make clean && make verify` | `build-support/cpd-report/target/cpd/`; CI artifact `cpd-report-<run>` | `95.52 s` clean reactor wall; CPD module `2.703 s` | 11 raw matches / 10 semantic findings; 7 debt candidates, 3 retained clusters | `R030-BUILD` + `R030-QUAL` | `report-only` |
| Maven dependency analysis | Maven Dependency Plugin `3.9.0`; default bytecode analysis | `./mvnw -B -ntp -T 1C verify dependency:analyze-only` | Local console/report ledger; CI adoption undecided | Maven `47.745 s` including `verify`; sequential `-DskipTests` attribution `4.671 s` | Broad candidate signal; substantial parent-test, starter, transitive API, SPI and processor noise | `R030-BUILD` | `report-only` |

Допустимые rollout stages: `planned`, `report-only`, `triaged`, `baselined`,
`blocking` и `tightening`.

## Initial work-item queue

| Work item | Outcome | Mode | Entry dependency | State |
|---|---|---|---|---|
| `BUILD-SPOTBUGS-01` | Reproducible reactor-wide production-bytecode report, scope/cost inventory and raw findings | Report only; no mass remediation or merge gate | `R030-BASE` verified | `verified` |
| `BUILD-CPD-02` | Repository-wide production-source report and evidence-based `minimumTokens` calibration | Diagnostic/report only | `BUILD-SPOTBUGS-01` closed, unless matrix explicitly reorders independent tooling | `verified` |
| `BUILD-DEPS-03` | Semantic disposition of the captured dependency candidates and `Adopt / Adopt with exclusions / Defer` decision | Evaluation only | `BUILD-CPD-02` closed | `planned` |
| `BUILD-SPOTBUGS-04` | Finding triage, immediate-risk fixes, narrow legacy baseline and deterministic rerun | Triage/baseline | `BUILD-SPOTBUGS-01` report | `planned` |
| `BUILD-SPOTBUGS-05` | Accepted no-new-findings signal wired into canonical Maven `verify` | Blocking ratchet | `BUILD-SPOTBUGS-04` closed | `planned` |

The queue is sequential for operator/agent clarity, not a technical claim that
the controls depend on each other. A confirmed immediate correctness, resource
or concurrency risk may move `BUILD-SPOTBUGS-04` forward through an explicit
status-matrix decision.

## `BASE-QUALITY-06` — baseline действующих controls

Baseline подтверждает, что проект уже контролирует воспроизводимость,
архитектуру и publication mechanics, но пока не запускает универсальный
production bytecode analyzer или duplicate-code detector.

| Existing control | Executable evidence | Baseline result | Follow-up |
|---|---|---|---|
| Maven Wrapper | `.mvn/wrapper/maven-wrapper.properties`; Make/CI invoke `./mvnw` | Wrapper `3.3.4`, Maven `3.9.9` | Preserve |
| Maven Enforcer | Root and module POM executions | Plugin `3.6.3`; toolchain, duplicate declaration and selected boundary bans pass | Preserve; do not imply dependency convergence |
| ArchUnit | Bootstrap/domain/application architecture tests | Version `1.3.0`; current rules pass | Preserve |
| Reactor CI | `.github/workflows/ci.yml` → `tools/ci/build.sh` | Canonical `verify` path is wired | Preserve |
| Documentation checks | `DocumentationConventionTest`, `tools/ci/docs.sh`, `doc-links` job | Java conventions and Markdown links are checked | Preserve |
| Dependency security | Scheduled/manual workflow and `tools/ci/security-*.sh` | Separate Dependency-Check control exists | Keep outside code-quality analysis |
| Release workflow | `.github/workflows/release.yml` and release leaf scripts | Tag, clean build, immutability, packaging and documentation gates exist | Preserve |

### Baseline compiler и test-runtime warnings

Чистый `make verify` на `a0f2235` завершил все 22 reactor projects. Maven
зафиксировал `57.263 s`; процесс занял `58.39 s` real time с maximum RSS
`1784708 KiB`.

| Signal | Count/scope | Meaning | Disposition |
|---|---:|---|---|
| Ordinary javac source warning | `0` | No source-level compiler warning was emitted | Retain as baseline |
| Implicit annotation-processor discovery advisory | `2`, `ioc-app` main + test compile | A future javac may stop implicit processor discovery | Evaluate explicit processor configuration under `R030-BUILD`; not a BASE fix |
| SLF4J no-provider warning group | `5`, isolated module test JVMs | Those test classpaths fall back to NOP logging | Review logging/assertion needs under `R030-TEST`; do not add providers blindly |
| Mockito self-attachment / dynamic Byte Buddy agent group | `1`, `ioc-app` tests | Current JDK permits a mechanism that a future JDK will disable by default | Evaluate explicit test-agent configuration under `R030-BUILD`/`R030-TEST` |
| Surefire warning summary | `1` suite, `2` skipped tests | External SMB contract fixture is absent | Already dispositioned in the test-quality ledger; not a compiler-quality warning |

Ни одно предупреждение не было подавлено; baseline gate не изменял POM,
dependencies или исходный код.

### Baseline Maven dependency analysis

Отдельный reactor-вызов `dependency:analyze-only` невоспроизводим: downstream
modules не могут разрешить reactor-local artifacts, потому что standalone goal
не собирает и не предоставляет их. Поэтому принята следующая
evaluation-команда:

```bash
./mvnw -B -ntp -T 1C verify dependency:analyze-only
```

Она успешно завершилась с Maven Dependency Plugin `3.9.0`. Второй,
последовательный запуск с `-DskipTests` использован для получения не
перемешанной по модулям карты findings:

| Module | Used undeclared | Declared unused | Non-test scope, test-only |
|---|---:|---:|---:|
| `platform-errors` | 0 | 2 | 0 |
| `platform-diagnostics` | 1 | 1 | 0 |
| `platform-etl` | 1 | 1 | 0 |
| `platform-events` | 1 | 1 | 0 |
| `platform-concurrency` | 1 | 1 | 0 |
| `platform-observability` | 2 | 1 | 0 |
| `platform-diagnostics-logging` | 4 | 1 | 1 |
| `ioc-domain` | 3 | 2 | 0 |
| `ioc-application` | 2 | 2 | 0 |
| `ioc-application-tck` | 2 | 1 | 0 |
| `adapter-regex-re2j` | 0 | 2 | 0 |
| `adapter-psl` | 2 | 1 | 0 |
| `adapter-source-tika` | 1 | 2 | 0 |
| `adapter-sink-csv` | 2 | 2 | 1 |
| `adapter-manifest-json-jackson` | 3 | 1 | 0 |
| `adapter-store-jdbc` | 1 | 1 | 0 |
| `adapter-transport-smb` | 1 | 1 | 0 |
| `adapter-ingest` | 12 | 2 | 5 |
| `adapter-cli-picocli` | 8 | 3 | 0 |
| `ioc-app` | 21 | 8 | 5 |
| **Coordinate occurrences** | **68** | **36** | **12** |

Сырые totals не являются количеством дефектов. Основные noise classes:

- объявленный в parent агрегат `junit-jupiter` против прямого использования
  `junit-jupiter-api` и `junit-jupiter-params`;
- Spring Boot starters против напрямую используемых transitive Spring APIs;
- обнаружение Tika parsers через ServiceLoader/SPI;
- Spring configuration processing и другие build-time discovery механизмы;
- ArchUnit, Jackson, SLF4J и Logback APIs, доступные через managed integration
  families или test infrastructure.

Есть и правдоподобные кандидаты direct-dependency/scope:
`ioc-platform-errors` в `ioc-application` и `commons-io` в
`adapter-sink-csv`. Но сырого bytecode output недостаточно для удаления или
изменения scope. Каждое принятое изменение требует проверки source, resources,
reflection, SPI, starter и module boundaries.

Verbose dependency tree содержит один mediated conflict в test graph
`ioc-app`: `org.ow2.asm:asm:9.7.1` исключён в пользу разрешённой версии
`9.10.1`. Текущие правила Enforcer не включают `dependencyConvergence`;
это evidence не объявляет graph convergence-clean.

**BASE disposition:** оставить Maven dependency analysis в `report-only`
evaluation. После semantic triage `R030-BUILD` должен выбрать `Adopt`,
`Adopt with exclusions` или `Defer`. Этот baseline не разрешает
`failOnWarning`, удаление dependencies, broad exclusions или новое convergence
rule.

## SpotBugs rollout

Выбраны актуальные стабильные
[SpotBugs Maven Plugin `4.10.3.0`](https://spotbugs.github.io/spotbugs-maven-plugin/summary.html)
и engine `4.10.3`; обе версии зафиксированы явно в root parent. Требования плагина
([JDK 11+, Maven 3.6.3+](https://spotbugs.github.io/spotbugs-maven-plugin/plugin-info.html))
совместимы с project baseline JDK 21 / Maven 3.9.9. FindSecBugs не подключался.

Execution `analyze-production-bytecode` наследуется Java-модулями от root
parent и запускает goal `spotbugs` в phase `verify`. Это намеренно не
`spotbugs:check`: findings не блокируют build, но analyzer/process error
блокирует его через `failOnError=true`. SpotBugs выполняет один XML-only анализ;
полный native XML содержит package/class statistics, а Maven AntRun `3.2.0`
применяет поставляемый engine stylesheet `default.xsl` и создаёт module-local
HTML без повторного анализа bytecode. Root AntRun удаляет прежние module-local
SpotBugs XML/HTML в phase `initialize`, поэтому incremental `verify` не может
удовлетворить integrity gate артефактом предыдущего запуска. Обновление
`spotbugs.version` MUST подтвердить, что engine по-прежнему предоставляет
`default.xsl` и обе report-формы создаются.

Отдельный финальный build-only модуль `build-support/spotbugs-report` выполняет
goal `spotbugs-aggregate` в phase `verify`, создаёт общий XML/HTML и после этого
запускает поздний report-integrity режим общего JDK-only
`build-support/build-quality/BuildQualityVerifier`. Registry
`spotbugs-scope.tsv` содержит
disposition всех 24 reactor projects: 19 `analyzed`, root/TCK/two соседних
build-only POM как 4 `excluded` и сам `spotbugs-report` как один `aggregate`.
Root-only execution того же verifier в phase `validate` требует точного
равенства registry и root `<modules>`, сверяет artifactId/packaging и явный
`skip=true` для каждого исключённого child project, а также требует равенства
19 `analyzed` artifacts и dependencies report-модуля. Он выполняется до
дочерних проектов, поэтому новый reactor-модуль fail-closed до явного
disposition примерно в начале build, а не после analyzer run. Ожидаемые
XML/HTML пути затем выводятся из этого же registry, их XML/HTML structure
проверяется, и обычный `verify` не может успешно завершиться при пропущенном,
пустом, malformed или неожиданном report.

| Scope/module | Analyzed | Findings | Immediate fixes | Baseline filters | Clean rerun | Blocking evidence |
|---|---|---:|---|---|---|---|
| `platform-errors` | yes | 0 | 0 | 0 | yes | N/A: report only |
| `platform-diagnostics` | yes | 2 | 0 | 0 | yes | N/A: report only |
| `platform-etl` | yes | 5 | 0 | 0 | yes | N/A: report only |
| `platform-events` | yes | 0 | 0 | 0 | yes | N/A: report only |
| `platform-concurrency` | yes | 0 | 0 | 0 | yes | N/A: report only |
| `platform-observability` | yes | 2 | 0 | 0 | yes | N/A: report only |
| `platform-diagnostics-logging` | yes | 0 | 0 | 0 | yes | N/A: report only |
| `ioc-domain` | yes | 0 | 0 | 0 | yes | N/A: report only |
| `ioc-application` | yes | 9 | 0 | 0 | yes | N/A: report only |
| `adapter-regex-re2j` | yes | 0 | 0 | 0 | yes | N/A: report only |
| `adapter-psl` | yes | 0 | 0 | 0 | yes | N/A: report only |
| `adapter-source-tika` | yes | 2 | 0 | 0 | yes | N/A: report only |
| `adapter-sink-csv` | yes | 16 | 0 | 0 | yes | N/A: report only |
| `adapter-manifest-json-jackson` | yes | 0 | 0 | 0 | yes | N/A: report only |
| `adapter-store-jdbc` | yes | 17 | 0 | 0 | yes | N/A: report only |
| `adapter-transport-smb` | yes | 8 | 0 | 0 | yes | N/A: report only |
| `adapter-ingest` | yes | 8 | 0 | 0 | yes | N/A: report only |
| `adapter-cli-picocli` | yes | 4 | 0 | 0 | yes | N/A: report only |
| `ioc-app` | yes | 45 | 0 | 0 | yes | N/A: report only |
| **Итого production runtime** | **19 modules** | **118** | **0** | **0** | **yes** | **N/A: report only** |
| root parent | no | N/A | N/A | N/A | yes | Packaging `pom`; bytecode отсутствует |
| `coverage-report` | no | N/A | N/A | N/A | yes | Build-only `pom`; только JaCoCo aggregate |
| `spotbugs-report` | no | N/A | N/A | N/A | yes | Build-only `pom`; SpotBugs aggregate + integrity gate |
| `cpd-report` | no | N/A | N/A | N/A | yes | Build-only `pom`; только repository-wide source CPD report |
| `ioc-application-tck` | no | N/A | N/A | N/A | yes | Reusable JUnit/AssertJ test-contract library, не runtime production code |

Чистый 24-project reactor run сформировал 19 non-empty module XML/HTML пар и
aggregate XML/HTML. Aggregate содержит те же 118 findings и статистику 628
production classes; во всех module XML и aggregate `errors=0`,
`missingClasses=0`. Root parent, TCK и `coverage-report` SpotBugs reports не
создают; `spotbugs-report` создаёт только aggregate. Замер выполнен тем же
`make verify`, который использует canonical build leaf: wall time `93.00 s`,
user CPU `644.09 s`, system CPU `29.86 s`, peak RSS `1,270,644 KiB`. По
отношению к BASE clean quality run (`58.39 s`, `1,784,708 KiB`) wall-time cost
составляет `+34.61 s` / `+59.3%`; измеренный peak RSS не вырос. Parallel reactor,
отдельные SpotBugs JVM и два serializing aggregate mojos делают это end-to-end
CI-cost measurement, а не сумму module-local analyzer durations.

Повторный `make verify` без `clean` сохранил aggregate на 118 findings / 628
classes: stale module и aggregate outputs были удалены в `initialize` и не
удвоили baseline. Общий verifier защищён synthetic-reactor harness: четыре
happy paths и 15 негативных сценариев покрывают новый/stale reactor scope,
manifest/POM drift, packaging/disposition/skip contracts, ordering
dependencies, CPD source/config scope и missing/unexpected/malformed reports.
Каждый негативный сценарий требует exit `1` и точный diagnostic fragment; тест
выполняется root-only в `validate` и не мутирует checkout. Ручные проверки
пропущенного registry entry, ordering dependency и module HTML сохранены как
первичное evidence дефекта, но больше не являются единственной защитой.

### SpotBugs suppression register

| Selector/pattern | Scope | Rationale | Owner | Review/exit condition | Evidence |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

Пустой register означает отсутствие принятых suppressions. Analyzer error,
пропущенный module или отсутствующий report не регистрируются как false
positive.

## SpotBugs findings

| Pattern/category | Scope | Count | Highest risk | False-positive class | Disposition/evidence |
|---|---|---:|---|---|---|
| `EI_EXPOSE_REP` + `EI_EXPOSE_REP2` | Spring-bound configuration records и adapter objects | 49 | P2 | Framework binding / intentional mutable representation | Semantic triage в `BUILD-SPOTBUGS-04`; broad suppression запрещён |
| `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | В основном `Path.getParent()` / `getFileName()` под repository path invariants | 23 | P2 | Nullable JDK API без знания validated-root invariants | Проверить каждый edge case в `BUILD-SPOTBUGS-04` |
| `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | Public/application boundaries с documented unchecked failures | 16 | P3 | Deliberate exception contract | Review API/Javadoc; сейчас не блокировать |
| `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` + `SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING` | JDBC schema, migration и PRAGMA SQL | 12 | P1/R10 | Analyzer не различает controlled adapter metadata и untrusted input | P1 проверен: PRAGMA строится из `SqlitePragmaPolicy` constants; injection не подтверждён. Остальные — semantic triage |
| `RV_RETURN_VALUE_IGNORED` + `SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR` | SLF4J fluent builder; `ArtifactFilter.NONE` flyweight | 3 | P2 | Mutating fluent return / named shared instance, не singleton contract | Initial false-positive candidates; подтвердить при triage |
| `IS2_INCONSISTENT_SYNC` | `CsvArtifactSliceWriter.active` | 1 | P2/R17 | SpotBugs не знает synchronous callback contract `SnapshotRowConsumer` | Immediate race не подтверждён; contract и README требуют synchronous callbacks |
| Остальные 9 patterns (`DM`, `VA`, `REC`, `BC`, `UPM`, `SE`, `DLS`, `DB`, `CT`) | Несколько production modules | 14 | P2 | Mixed style, legacy serialization/finalizer model и локальные quality candidates | Поэкземплярный triage в `BUILD-SPOTBUGS-04` |

Сводка priority: P1 — 1, P2 — 81, P3 — 36. Сводка category:
`MALICIOUS_CODE` — 49, `STYLE` — 29, `BAD_PRACTICE` — 20, `SECURITY` — 12,
`I18N` — 3, `CORRECTNESS` — 3, `PERFORMANCE` — 1, `MT_CORRECTNESS` — 1.

В первичном review не подтверждён критичный correctness/resource/concurrency
risk, поэтому очередь status matrix не меняется. Findings не исправлялись,
filter/suppression baseline не создавался; полная semantic disposition относится
к `BUILD-SPOTBUGS-04`.

При adoption отдельно фиксируются accepted rules/severities, baseline format,
new-code ratchet, узкие suppressions и их review conditions.

## PMD CPD findings

`BUILD-CPD-02` использует
[Maven PMD Plugin `3.28.0`](https://maven.apache.org/plugins/maven-pmd-plugin/)
с bundled PMD `7.17.0`. Финальный `build-support/cpd-report` выполняет
официальный goal
[`aggregate-cpd`](https://maven.apache.org/plugins/maven-pmd-plugin/aggregate-cpd-mojo.html)
в phase `verify` после 19 production dependencies. Fail-closed
`cpd-scope.tsv` даёт disposition всем 24 reactor projects; JDK-only verifier
в root `validate` сверяет registry с root reactor и POM metadata, а analyzed
set — одновременно с 19 ordering dependencies и 19 configured
`src/main/java` roots. Положительный
список анализирует 499 уникальных production Java source paths одним
invocation, поэтому межмодульные совпадения видны, а `ioc-application-tck`,
tests, Maven-generated roots, build outputs и build-support POMs не попадают в
scope. Checked-in vendor/generated trees в repository отсутствуют; явные
`**/vendor/**` и `**/generated/**` selectors являются дополнительным guard.

Native machine-readable XML и Doxia HTML формируются в
`build-support/cpd-report/target/cpd/`. Findings не вызывают failure: goals
`cpd-check`/`aggregate-cpd-check` не подключены. Ошибка analyzer/report renderer
останавливает Maven. `initialize` заранее удаляет предыдущий `target/cpd`, а
после analysis verifier требует non-empty XML/HTML, проверяет CPD XML
namespace/root и HTML document marker, затем сверяет 499 unique XML paths с
текущими Java files всех analyzed roots. Поэтому stale, empty, malformed,
incomplete или scope-drifted report не может завершить `verify`.

| Finding | Occurrences | Shared knowledge/behavior | Semantic differences | Disposition | Rationale | R030-QUAL finding |
|---|---|---|---|---|---|---|
| Config-path tokenization/reflection, 317 tokens | `IocEnvironmentPropertyMatcher`; `IocUnknownConfigurationPreflight` | Одна форма config path, index и reflected element type | Matcher классифицирует env names; preflight проверяет admissible property shape | `deduplicate` | Highest divergence risk: strict binding должен иметь один authoritative path model; реализация требует characterization tests | `QUAL-CPD-01` |
| Extraction diagnostic fields, 157 tokens | `ExtractCommand`; `FileSourceMessageHandler` | Одинаковая проекция `ExtractionResult` summary в `LogEvent` | CLI и daemon принадлежат разным driving adapters и имеют разные lifecycle/owners | `defer` | Общий inward helper создал бы нежелательную observability dependency; сначала выбрать legal boundary | `QUAL-CPD-02` |
| Control-event MDC, 107 + 80 tokens | Три bootstrap listeners | Общие event metadata/correlation fields | Handler-specific source, slice, target и endpoint tails | `defer` | Возможен package-private bootstrap helper, но сначала проверить typed ECS contract и читаемость handlers | `QUAL-CPD-03` |
| SHA-256 length framing, 105 tokens | `ArtifactSchemaFingerprint`; `ExportPlan` | Один length-prefixed digest encoding invariant | Fingerprints покрывают разные export inputs | `deduplicate` | Encoding knowledge должно иметь один application/export owner | `QUAL-CPD-04` |
| Diagnostic-code enum implementation, 102 tokens | `SchemaDiagnosticCodes`; `StorageDiagnosticCodes` | Общий interface boilerplate | Category, impact, IDs и message semantics различны | `retain` | Java enum boilerplate не является shared policy; abstraction ухудшит локальную явность | `QUAL-CPD-05` |
| Completed-slice directory traversal, 99 tokens | Два метода `FileSystemCompletedSliceCatalog` | Одинаковая safe profile-directory traversal | Один путь возвращает verified manifests, другой только eligible names | `deduplicate` | Допустима только private adapter-local traversal seam, сохраняющая разные validators/results | `QUAL-CPD-06` |
| SMB connect/authentication setup, 82 tokens | `SmbjChangeNotifySessionFactory`; `SmbjShareClientFactory` | Одинаковые connect/auth/share и password-wipe mechanics | Factories создают разные higher-level session owners | `deduplicate` | Security/resource-sensitive setup должен расходиться как можно меньше и оставаться внутри SMB adapter | `QUAL-CPD-07` |
| Graceful executor shutdown, 79 tokens | `DaemonExportScheduler`; `PeriodicDaemonCycle` | Один timeout/shutdown/interrupt protocol | Разные scheduling state и recovery responsibilities | `defer` | Выносить только после lifecycle/concurrency review; generic helper не должен скрыть ownership | `QUAL-CPD-08` |
| Legacy ledger property parsing, 78 tokens | `FileIngestionLedger`; `LegacyLedgerImporter` | Общая legacy serialized shape | Runtime file ledger и one-way JDBC migration имеют разные owners и retirement path | `retain` | Межмодульное извлечение продлит legacy contract; пересмотреть при retirement file ledger | `QUAL-CPD-09` |
| Maintenance scheduler lifecycle, 75 tokens | `DaemonMaintenanceScheduler`; `DaemonSliceRetentionScheduler` | Малый start/stop scheduling pattern | Разные cadence, operation и failure policy | `retain` | Независимый lifecycle boilerplate; abstraction не уменьшает policy duplication | `QUAL-CPD-10` |

CPD report не является списком автоматических refactorings. Каждая существенная
находка проходит duplication triage из
[R030-QUAL](../goals/R030-QUAL-code-health.md).

## CPD configuration calibration

| Candidate `minimumTokens` | Finding count | Noise classes | Missed known duplicate | Warm standalone runtime | Decision |
|---:|---:|---|---|---:|---|
| 50 | 37 | Короткие getters/records, small JDBC/path branches, repeated intra-method fragments | none in reviewed range | `2.65 s` | Reject: слишком много micro-pattern noise для initial semantic queue |
| 75 | 11 | Остались lifecycle/framework fragments, но каждый cluster обозрим | none among accepted substantial seams | `2.65 s` | **Adopt**: сохраняет 75–99-token operational seams и крупные knowledge duplicates |
| 100 | 5 | В основном крупные knowledge/behavior blocks | Listener MDC, SMB setup, scheduler/legacy seams | First-run cost included dependency/skin resolution; not comparable | Reject: теряет несколько review-worthy operational clusters |
| 125 | 2 | Только два крупнейших блока | Diagnostic mapping, fingerprint framing и все 75–107-token seams | `1.13 s` | Reject: слишком низкая чувствительность |
| 150 | 2 | То же, что 125 | То же, что 125 | `2.58 s` | Reject: дополнительной ценности нет |

Принятый threshold обосновывается repository evidence. Generated/vendor
exclusions перечисляются точными paths/selectors.

Runtime различия warm calibration находятся внутри шума короткого standalone
invocation; threshold выбран по semantic signal, не по скорости. Первый запуск
с загрузкой PMD/Doxia dependencies намеренно не используется как steady-state
cost.

Canonical clean 24-project reactor завершился за `95.52 s` process wall
(`646.69 s` user, `31.32 s` system, peak RSS `1,686,464 KiB`); Maven показал
`01:34 min`, а финальный `ioc-cpd-report` занял `2.703 s`. Сравнение с
предыдущим clean SpotBugs-only reactor (`93.00 s`) даёт ориентировочный
end-to-end прирост `+2.52 s` / `+2.7%`. Peak RSS и parallel scheduling между
отдельными runs не считаются изолированной CPD attribution; steady-state
standalone execution занимал `1.13–2.65 s`.

Общая автоматическая matrix, описанная в SpotBugs rollout, отдельно покрывает
CPD production-source/config drift, accidental `includeTests=true` и malformed
XML. Финальный clean run подтвердил 499 unique production source paths,
11 duplications, отсутствие TCK/generated/vendor references и наличие обоих
report formats.

## Maven dependency-analysis findings

| Module | Finding type | Dependency | Dynamic/framework evidence | Disposition | Work item |
|---|---|---|---|---|---|
| Parent/test stack | `used-undeclared / declared-unused` | `junit-jupiter*`, AssertJ | Parent aggregate dependency creates repeated module-local analyzer asymmetry | Calibrate before adoption; no module-by-module churn from raw output | `R030-BUILD` |
| `ioc-application` | `declared-unused` candidate | `ioc-platform-errors` | No direct source import found; boundary/transitive intent still requires review | Semantic validation required | `R030-BUILD` |
| `adapter-sink-csv` | `declared-unused` candidate | `commons-io` | No direct source import found; resource/runtime use still requires review | Semantic validation required | `R030-BUILD` |
| Spring adapter/bootstrap modules | all three types | Spring starters and transitive APIs | Framework wiring, autoconfiguration, reflection and managed integration families | Classify per coordinate; broad group exclusion is not accepted | `R030-BUILD` |
| `adapter-source-tika` | `declared-unused` candidate | Tika parser package | Parser implementations are discovered through SPI | Expected dynamic-use candidate; verify with adapter contract corpus | `R030-BUILD` |

Исключение содержит точные coordinates, rationale, owner и review condition.
Broad group exclusion не принимается, если возможна более узкая запись.

## Adoption decisions

### Control — decision

- **Decision:** `Adopt | Adopt with exclusions | Defer | Reject`
- **Evidence:**
- **Accepted signal/rules:**
- **Baseline/ratchet:**
- **Suppression policy:**
- **Runtime/CI impact:**
- **Owner:**
- **Revisit condition:**

SpotBugs имеет принятое решение `Adopt` в 0.3.0; этот шаблон фиксирует точный
signal и допустимые exclusions. Для CPD decision описывает diagnostic
configuration и условия возможного будущего no-new-duplication ratchet.

## Deferred tool boundaries

Следующие controls не требуют evaluation для закрытия 0.3.0:

- GitHub CodeQL и SAST/SecOps;
- Spotless и Checkstyle;
- japicmp и Revapi;
- Error Prone и NullAway;
- SonarQube и Qodana;
- полный PMD ruleset помимо CPD.

Их отсутствие не является missing evidence текущего `R030-BUILD`.

Полный PMD ruleset не принят вместе с CPD: unused-code, complexity,
object-creation/performance и error-prone categories требуют отдельного
signal/noise evaluation. Revisit по умолчанию следует после
`BUILD-SPOTBUGS-04`; раньше — только по concrete risk/gap evidence и явному
изменению status matrix.

## Completion

- [x] SpotBugs report воспроизводим
- [x] SpotBugs signal/noise/cost оценены
- [x] SpotBugs production-module scope подтверждён
- [ ] Immediate-risk SpotBugs findings исправлены
- [ ] SpotBugs baseline filters узкие и обоснованы
- [ ] SpotBugs `check` стабильно входит в Maven `verify`
- [ ] SpotBugs запрещает новые findings принятого signal
- [x] PMD CPD aggregate report воспроизводим
- [x] CPD threshold откалиброван на repository evidence
- [x] Существенные CPD findings переданы в R030-QUAL
- [x] Существенные CPD findings имеют semantic disposition
- [x] PMD CPD diagnostic configuration и ownership приняты
- [ ] Maven dependency-analysis report воспроизводим
- [ ] Dynamic/framework false positives проверены
- [ ] Maven dependency-analysis adoption decision принят
- [ ] Adopted ratchets и suppressions документированы
- [ ] Status matrix обновлена
