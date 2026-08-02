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
| Maven dependency analysis | Maven Dependency Plugin `3.11.0`; fast direct goal + opt-in full `dependency-analysis` profile; default bytecode analyzer | `make dependency-analysis` | Local console/report ledger; deliberately absent from regular CI | Fast sequential package + analysis observed at `5.313–7.677 s` Maven / `6.75–8.72 s` process; full profile timing below | 14 direct POM mismatches corrected; residual `56 / 34 / 12` candidate occurrences are test-aggregate, starter, SPI and transitive-runtime noise | `R030-BUILD` | `report-only`, blocking adoption deferred |

Допустимые rollout stages: `planned`, `report-only`, `triaged`, `baselined`,
`blocking` и `tightening`.

## Initial work-item queue

| Work item | Outcome | Mode | Entry dependency | State |
|---|---|---|---|---|
| `BUILD-SPOTBUGS-01` | Reproducible reactor-wide production-bytecode report, scope/cost inventory and raw findings | Report only; no mass remediation or merge gate | `R030-BASE` verified | `verified` |
| `BUILD-CPD-02` | Repository-wide production-source report and evidence-based `minimumTokens` calibration | Diagnostic/report only | `BUILD-SPOTBUGS-01` closed, unless matrix explicitly reorders independent tooling | `verified` |
| `BUILD-DEPS-03` | Semantic disposition of the captured dependency candidates and `Adopt / Adopt with exclusions / Defer` decision | Evaluation only | `BUILD-CPD-02` closed | `verified` |
| `BUILD-SPOTBUGS-04` | Finding triage, immediate-risk fixes, narrow legacy baseline and deterministic rerun | Triage/baseline | `BUILD-SPOTBUGS-01` report | `in-progress` (`C0..C3` completed; `C4` deterministic rerun next) |
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
| Exact SQL patterns + class + method, 10 findings | JDBC schema/migration and repository query-shape builders | Identifiers are allow-list validated and quoted, values are bound, and migrations are code-owned; no untrusted SQL grammar reaches execute sites | `adapter-store-jdbc` | Remove on literal query replacement; review any external migration source, identifier grammar/allow-list expansion or new query-shape builder | `C1-SQL-A/B/D/E/F`; exact `SB04-*` IDs in `spotbugs-baseline-exclude.xml` |
| Exact `NP_*` + class + method, 20 findings | Child paths from verified NIO listings, configured lifecycle roots, Tika source protocol and remote inbox leaves | The nullable JDK return is constrained by a stronger proven path provenance at each call site | Owning source/ingest/store/transport modules | Remove when control flow becomes analyzer-provable; review if a path ceases to be a direct child or its validated root/source protocol changes | `C1-NP-A/B/E/F` |
| Exact concurrency/singleton patterns + class/member, 2 findings | Synchronous CSV callback and immutable `ArtifactFilter.NONE` flyweight | Callback access is monitor-confined by the synchronous port contract; the named empty instance has no singleton lifecycle contract | `adapter-sink-csv` | Review if callbacks become asynchronous or `ArtifactFilter` gains identity/lifecycle semantics | `C1-CON-A`, `C1-COR-A` |
| Exact `EI_EXPOSE_REP*` + class/member, 5 findings | Immutable snapshots and lifecycle-owned bootstrap resources | Construction already copies the collection, or the object deliberately exposes the same managed resource | Owning application/bootstrap modules | Remove when analyzer recognizes the copy; review if construction stops copying or lifecycle ownership crosses the bootstrap boundary | `C2-REP-C/E` |
| Exact `EI_EXPOSE_REP*` + class/member, 44 findings | Spring-bound configuration and adapter value records | Real mutable aliases with no current mutation call site; safe remediation requires null-preserving copies compatible with collect-all binding | `ioc-app/configuration`, `adapter-ingest`, `adapter-sink-csv` | Replace with null-preserving defensive copies plus binding and mutation-isolation regressions | `C2-REP-A/B/D` |
| Exact `THROWS_*` + class + method, 18 findings | CLI, pipeline, ingestion, recovery and startup failure boundaries | Required cleanup/accounting/diagnostics occur before the original unchecked failure is propagated with type, cause and stack intact | Owning CLI/application/adapter/bootstrap modules | Remove if the method no longer performs boundary work; review any async, retry, translation or swallow contract | `C2-EX-A..E`, `I4-SB-04`, `FUP-SB-01` |
| Exact mixed patterns + class + method, 11 findings | SMB share casts, diagnostic constructor, local dead/duplicate code, locale-sensitive tokens, CLI parse fallback and line formatting | Reviewed non-critical legacy quality debt with a specific local remediation path | Owning adapter/platform/bootstrap modules | Apply the per-group exit in `C2-MIX-A..E/H`, then delete the selector | `C2-MIX-A..E/H` |
| Exact `SE_BAD_FIELD` / `VA_FORMAT_STRING_USES_NEWLINE` + class + method, 2 findings | Non-serialized diagnostics and SQL text-block whitespace | The warned boundary does not exist, or the newline is SQL grammar rather than user-visible text | `platform-diagnostics`, `adapter-store-jdbc` | Review if Java serialization is introduced or SQL text becomes user-visible output | `C2-MIX-F/G` |
| Exact `VO_VOLATILE_INCREMENT` + class + method/field, 2 findings | Same-key admission/release accounting | Both mutations occur under `ConcurrentHashMap.compute` for the same key; `volatile` is only for snapshot visibility | `platform-concurrency` | Remove if accounting changes; review any mutation outside same-key `compute` | `I4-SB-02..03` |

The checked-in filter contains 109 exact selectors for these 114 findings. Five
extra occurrences share the same pattern, class and method with another reviewed
occurrence; SpotBugs filters cannot address an instance hash. No selector is
package-, category- or pattern-wide. Analyzer errors, omitted modules and missing
reports remain integrity failures and are never represented as suppressions.

## SpotBugs findings

| Pattern/category | Scope | Count | Highest risk | False-positive class | Disposition/evidence |
|---|---|---:|---|---|---|
| `EI_EXPOSE_REP` + `EI_EXPOSE_REP2` | Spring-bound configuration records и adapter objects | 49 | P2 | Framework binding / intentional mutable representation | `C2`: 44 real mutable aliases приняты как legacy debt; 5 immutable/lifecycle-owned exposures — false positives; broad suppression запрещён |
| `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `Path.getParent()` / `getFileName()` под repository path invariants | 20 | P2 | Nullable JDK API без знания validated-root invariants | `C1-NP-A/B/E/F`: false positives; три projection-path findings из `IR-02` устранены validation/adapter fix |
| `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | Public/application boundaries с documented unchecked failures | 18 | P3 | Deliberate exception contract | `C2-EX-A..E` плюс два post-inventory boundary cases: cleanup/accounting/observer work сохраняют runtime type, cause и stack |
| `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` + `SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING` | JDBC schema, migration и query-shape SQL | 10 | P1/R10 | Analyzer не различает controlled adapter metadata и untrusted input | `C1-SQL-A/B/D/E/F`: configured/internal identifiers валидируются и quote-ятся, values bind'ятся, migrations code-owned; два health PRAGMA findings устранены typed/literal fix |
| `SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR` | `ArtifactFilter.NONE` flyweight | 1 | P2 | Named shared immutable instance, не singleton contract | `C1-COR-A`: false positive; оба `RV_RETURN_VALUE_IGNORED` устранены `IR-01` fix |
| `IS2_INCONSISTENT_SYNC` | `CsvArtifactSliceWriter.active` | 1 | P2/R17 | SpotBugs не знает synchronous callback contract `SnapshotRowConsumer` | `C1`: false positive; `stage` удерживает monitor, production reader вызывает callbacks inline, asynchronous callback запрещён port contract |
| Остальные 9 patterns (`DM`, `VA`, `REC`, `BC`, `VO`, `SE`, `DLS`, `DB`, `CT`) | Несколько production modules | 15 | P2 | Mixed style, legacy serialization и локальные quality/concurrency candidates | `C2` и post-inventory review: 11 accepted legacy, 4 false positives; `IR-03` устранён до baseline |

Сырой pre-baseline snapshot C3: 114 findings; priority P1 — 1, P2 — 78,
P3 — 35. Category: `MALICIOUS_CODE` — 49, `STYLE` — 26,
`BAD_PRACTICE` — 22, `SECURITY` — 10, `MT_CORRECTNESS` — 3, `I18N` — 3,
`CORRECTNESS` — 1.

`BUILD-SPOTBUGS-04/C1` присвоил disposition первым 39 findings: 34
`false-positive`, 2 `fix-now` и 3 `resolved-by-related-fix`. Два узких change
groups отложены до предусмотренного `C3`: `IR-01` должен потреблять returned
SLF4J builder; `IR-02` должен отклонять projection path без leaf name и убрать
повторные nullable calls. Текущий Logback provider маскирует `IR-01`, а `IR-02`
fail-safe до записи и не затрагивает canonical DB; критичный security/resource/
concurrency risk не подтверждён, поэтому очередь не меняется. Production code и
filter/suppression baseline в `C1` не изменялись; подробное evidence находится
во временном execution worknote. Последующий C1 hardening сузил security-вывод
до current production wiring и закрепил его шестью regression cases: SQL-shaped
artifact/column/type/PRAGMA inputs fail closed, неотмеченный dynamic
`JdbcArtifactIdBaseline` path валидирует имя, а SQL-shaped runtime values
сохраняются как данные. Configured names используют строгий
`[A-Za-z][A-Za-z0-9_]*`, а повторная проверка adapter-owned internal names —
`[A-Za-z_][A-Za-z0-9_]*`; оба набора исключают SQL grammar. Follow-up закрыл
String-based health PRAGMA двумя private result-typed enums и exhaustive
literal switches: `SB04-004..005` исчезают из raw report, а не переходят в
baseline. Review обязателен при external migrations, расширении SQL allowlists
или появлении нового query-shape builder.

`BUILD-SPOTBUGS-04/C2` завершил семантический triage оставшихся 79 findings:
55 `accepted-legacy`, 23 `false-positive` и 1 `fix-now`. Общий disposition
всех 118 findings после `C1+C2` изначально: 57 false positives, 55 accepted
legacy, 3 fix-now и 3 companions `resolved-by-related-fix`. После C1 follow-up
`SB04-004..005` стали `resolved-by-fix`, поэтому актуально: 55 false positives,
55 accepted legacy, 3 fix-now и 5 resolved findings. Representation debt пока не
имеет известных mutation call sites; его исправление требует null-preserving
copies, сохраняющих ADR-0016 collect-all validation. Все 16 exception-flow
findings сохраняют исходный runtime failure после обязательного cleanup или
accounting.

`SB04-116` (`IR-03`) получил утверждённый отдельный hardening до `C3`. Dead
`TransactionTemplate` helper удалён, а не подключён как ложное лечение.
`ING-10` теперь закрывает исходную систему причин: recovery-before-intake,
единый per-source-key guard, fresh-state re-read и expected-state/CAS terminal
transitions в file/JDBC adapters. Общий concurrent TCK допускает ровно одного
победителя `archive`/`fail`; coordinator, restart и watched-inbox regressions
закрепляют lifecycle. Suppression baseline при этом не менялся.

Отдельный observability follow-up закрепил lifecycle как typed
`ingest_recover` start/terminal operation, duplicate как disposition существующего
`source_ingest` и неожиданный ledger result как точный
`INGEST.STATE_TRANSITION_CONFLICT`. Diagnostic delivery остаётся exactly-once:
уже выпущенный `INGEST.RECOVERY_FAILED` не эмитится повторно на startup boundary.
Это не control-event path и не изменение correctness mechanism.

`ING-10/I4` verification прошёл полный 24-project reactor и оба report-integrity
gate. `SB04-116` исчез из отчёта. Четыре новых instance появились в изменённом
коде: `UL_UNRELEASED_LOCK_EXCEPTION_PATH` и два
`VO_VOLATILE_INCREMENT` в synchronous keyed guard, а также
`THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` в startup coordinator. Focused
concurrency regressions и ручная проверка exception paths классифицировали их
как false positives: lock освобождается в `finally`, mutations сериализуются
same-key `ConcurrentHashMap.compute`, а coordinator обязан повторно бросить
startup failure после fail-closed cleanup. Они остаются видимыми без suppression
до current-report reconciliation в `C3`. Aggregate изменился с 118 до 121 raw
findings (минус resolved `SB04-116`, плюс четыре проверенных instances),
`errors=0`, `missingClasses=0`.

Observability follow-up добавил один P3
`THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` в `IngestionService#ingestGuarded`:
метод после обязательного physical-failure cleanup повторно бросает исходный
typed failure, чтобы final boundary доставил точный diagnostic. Это тот же
проверенный exception-flow contract, что `C2-EX-B`, и он классифицирован как
`false-positive` без suppression. Clean reactor snapshot содержит 122/122
aggregate/module findings в 19 reports, `errors=0`, `missingClasses=0`;
немедленный incremental repeat воспроизвёл тот же результат.

C1 follow-up заменил String-параметры health PRAGMA закрытыми enum по типу
результата и exhaustive switches с literal `executeQuery` sites. Canonical
reactor прошёл 24/24 за `01:54`; `SB04-004..005` отсутствуют и в module, и в
aggregate report. Текущий snapshot содержит 120/120 findings в 19 module
reports, `errors=0`, `missingClasses=0`; suppression baseline не менялся.
Одновременно `SEC-INP-3` переведён из расплывчатого `Manual + Enforced parts`
в точное `Enforced + Monitored`: известные trust boundaries защищены шестью
regression-тестами и typed/literal PRAGMA boundary, а discovery нового
non-constant JDBC site остаётся report-only до `BUILD-SPOTBUGS-05`.

C2 audit follow-up устранил diagnostic seam в synchronous guard: release
failure больше не может заменить primary work failure и проверяется
corrupted-state regressions. `I4-SB-01` исчез из текущего SpotBugs report;
same-key `compute` mutation invariant, single-writer lifecycle, highest-priority
startup runner и shared-guard composition contract зафиксированы рядом с кодом.
Точный direct dependency inventory application-модуля оставлен единственному
authority — его POM; архитектурные карты описывают роли и ссылаются на него.
Focused 10/10 и полный 24-project reactor прошли. Текущий snapshot содержит
119/119 findings в 19 module reports, `errors=0`, `missingClasses=0`; четыре
post-inventory false positives остаются видимыми без suppression до `C3`.

`BUILD-SPOTBUGS-04/C3` устранил оставшиеся immediate-risk groups до baseline:
`IR-01` потребляет каждый returned SLF4J builder, а provider-contract regression
проверяет copy-returning implementation; `IR-02` отклоняет filesystem root в
collect-all configuration preflight и на adapter boundary, сохраняя допустимый
parentless leaf output. Raw report сократился с 119 до 114 ровно на пять
ожидаемых findings без новых signals.

Оставшиеся 55 accepted-legacy и 59 false-positive findings покрыты одним
versioned filter: 109 точных pattern + class + method/field selectors. Пять
дополнительных occurrences находятся в уже выбранных точных методах; instance
hash не является частью SpotBugs filter grammar. Root-inherited module execution
применяет filter один раз, а aggregate объединяет эти module XML без второй
baseline copy. Canonical `make verify` прошёл 24/24 за `02:17`; независимая
сверка подтвердила 19 module XML/HTML pairs плюс aggregate, 0 visible findings,
`errors=0`, `missingClasses=0`. Findings остаются report-only; новый unmatched
signal будет виден, но станет blocking ratchet только в `BUILD-SPOTBUGS-05`.

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

`BUILD-DEPS-03` зафиксировал официальный Maven Dependency Plugin
[`3.11.0`](https://maven.apache.org/plugins/maven-dependency-plugin/plugin-info.html).
Goal
[`analyze-only`](https://maven.apache.org/plugins/maven-dependency-plugin/analyze-only-mojo.html)
thread-safe, по умолчанию привязан к `verify` и анализирует уже собранные main +
test classes. Root `dependency-analysis` profile наследуется всеми reactor
projects: 20 functional JAR-модулей анализируются, включая reusable
`ioc-application-tck`, а root и три reporting POM явно выводят
`Skipping pom project`.

Полный profile run после `clean`,
`./mvnw -B -ntp -T 1C -Pdependency-analysis verify`, завершил
24-project reactor за `02:17 min` Maven wall / `138.80 s`
process (`899.98 s` user, `65.13 s` system). Основной developer facade
`make dependency-analysis` выполняет последовательную быструю форму
`./mvnw -B -ntp -T 1 -DskipTests package dependency:analyze-only`, которая
заняла `5.313 s` Maven / `6.75 s` process; follow-up facade run —
`7.677 s` Maven / `8.72 s` process. Оба запуска воспроизвели `56 / 34 / 12`.
Это не изолированный plugin
benchmark: command включает package/test-compile lifecycle, а `-DskipTests`
пропускает только execution тестов. `-Dmaven.test.skip=true` здесь недопустим,
поскольку убрал бы test bytecode из scope.

Исходный report Plugin `3.11.0` точно воспроизвёл baseline Plugin `3.9.0`:
`68 used-undeclared / 36 declared-unused / 12 non-test-scoped-test-only`.
Source, tests, resources, effective POM и dependency trees подтвердили 14
прямых POM mismatches:

| Correction | Modules | Closed raw findings | Evidence |
|---|---|---:|---|
| Удалены действительно неиспользуемые direct dependencies | `ioc-application`: `ioc-platform-errors`; `adapter-sink-csv`: `commons-io` | 2 unused | Нет main/test imports, resource, SPI или reflection use; reactor compile/tests сохраняют consumer graph |
| Объявлены напрямую используемые inward coordinates | `adapter-cli-picocli`, `ioc-application-tck`: `ioc-platform-diagnostics` | 2 used-undeclared | Production/TCK main sources импортируют diagnostic API |
| Jackson adapter объявляет все импортируемые Jackson API artifacts | `adapter-manifest-json-jackson`: `jackson-core`, `jackson-annotations` | 2 used-undeclared | Codec main source импортирует обе API families напрямую |
| SLF4J API объявлен в модулях с production logging imports | `adapter-cli-picocli`, `adapter-ingest`, `ioc-app` | 3 used-undeclared | Main sources импортируют `Logger`, `LoggerFactory` или SLF4J event API |
| Logback core объявлен для прямых test imports | `platform-observability`, `platform-diagnostics-logging` | 2 used-undeclared | Tests используют `ListAppender` напрямую |
| Composition root объявляет напрямую используемые runtime APIs | `ioc-app`: `logback-classic`, `HikariCP`, `jakarta.validation-api` | 3 used-undeclared | Main sources реализуют Logback encoder, владеют Hikari datasource lifecycle через JDBC-adapter factory и объявляют validation constraints |

После corrections report стабильно содержит `56 / 34 / 12`. Новых findings
не появилось. Остаток семантически классифицирован:

| Raw category | Occurrences | Classification | Disposition |
|---|---:|---|---|
| `used-undeclared` | 27 | JUnit Jupiter и ArchUnit aggregate artifacts против их API/core transitives | Retain parent/test aggregate model; bytecode analyzer не понимает dependency aggregation |
| `used-undeclared` | 21 | Spring Boot, Spring Integration и Picocli starter APIs | Retain focused starters/integration families; не дублировать каждый managed component как direct dependency |
| `used-undeclared` | 5 | Logback/Jackson runtime transitives, на которые ссылаются только consumer-module tests | Retain runtime graph; test-scope override мог бы перехватить compile transitive |
| `used-undeclared` | 3 | `ioc-platform-etl`, доступный тестам через production inward dependencies | Retain inward graph; не добавлять test edge только ради analyzer |
| `declared-unused` | 24 | Parent JUnit/AssertJ stack и ArchUnit aggregate | Retain shared test stack; module-by-module duplication не улучшает runtime graph |
| `declared-unused` | 8 | Spring Boot/Integration/Picocli starters | Retain: starters владеют runtime/autoconfiguration family, а не одним bytecode reference |
| `declared-unused` | 1 | Spring configuration processor | Retain optional build-time discovery dependency |
| `declared-unused` | 1 | Tika standard parser package | Retain SPI/ServiceLoader parser implementations; adapter contract and golden runs exercise them |
| `non-test-scoped-test-only` | 10 | Runtime transitive Logback/Jackson/Picocli/Spring components referenced непосредственно в tests | Retain compile/runtime transitives required by adapters; warning не относится к direct declaration текущего модуля |
| `non-test-scoped-test-only` | 2 | Transitive `ioc-platform-etl` references in tests | Retain production inward dependency path |

Asymmetric enforcement для только `used-undeclared` отклонён: 48 из 56
таких occurrences приходятся на test aggregates и starter APIs. Даже
односторонний gate потребовал бы широкой inherited suppression policy и
остался бы шумным для новых модулей.

Глобальная замена parent `junit-jupiter` aggregate на `junit-jupiter-api`,
`junit-jupiter-params` и runtime engine также оценена и отклонена. API
используется в 18 из 20 functional modules, params — только в 5, а engine
загружается динамически. Общие parent declarations в основном
перенесли бы signal из `used-undeclared` в `declared-unused`, а не убрали
его. Помодульное объявление остаётся возможной будущей
пересборкой test-stack ownership, но не частью текущей adoption.

Reusable `ioc-application-tck` не имеет `non-test-scoped-test-only` finding:
его main TCK classes используют `junit-jupiter-api` и AssertJ, а analyzer
видит compile-scope aggregate/API asymmetry. Любая future publication admission
всё равно требует повторной dependency-scope проверки: transitive compile graph
становится external consumer contract.

Соседний goal
[`analyze-dep-mgt`](https://maven.apache.org/plugins/maven-dependency-plugin/analyze-dep-mgt-mojo.html)
оценён командой `./mvnw -B -ntp -T 1 -DskipTests package dependency:analyze-dep-mgt -Dmdep.analyze.ignore.direct=false`.
Прогон
завершился `BUILD SUCCESS`; все 24 reactor projects вывели `None` для
resolved dependency/dependencyManagement mismatches. Это дешёвый сигнал
неэффективного version management, но не доказательство graph convergence;
`dependencyConvergence` остаётся отдельным долгом.

Hikari disposition также явная: JDBC adapter владеет factory, pool
configuration и SQLite PRAGMA mechanics; outer bootstrap владеет lazy lifecycle и
Spring wiring concrete datasource. Hikari разрешён в adapters/bootstrap, поэтому
текущая direct bootstrap dependency не создаёт нового architecture finding.

Если blocking adoption будет пересмотрен, каждое исключение должно содержать
точные coordinates, rationale, owner и review condition. Broad group exclusion
не принимается, если возможна более узкая запись.

## Adoption decisions

### Maven dependency analysis — `Defer` blocking adoption

- **Decision:** `Defer` permanent regular-lifecycle/CI adoption; retain the
  pinned opt-in `dependency-analysis` report profile.
- **Evidence:** 14 direct POM mismatches were actionable, but the remaining 102
  occurrences are explained by deliberate test aggregates, starters,
  SPI/build-time discovery or necessary runtime transitives. Enforcing only
  `used-undeclared` was rejected too: 48 of its 56 occurrences are aggregate or
  starter noise.
- **Accepted signal/rules:** `make dependency-analysis` remains a bounded
  release/dependency-change review command. Analyzer/process failure fails that
  invocation; findings remain visible and advisory.
- **Baseline/ratchet:** no warning baseline and no `failOnWarning`; ordinary
  `make verify` does not activate the profile.
- **Suppression policy:** no ignores were added. Inherited coordinate ignores
  would also hide a genuinely accidental transitive dependency in a new module;
  duplicated per-module plugin configuration would create a second dependency
  registry with poor cost/signal value.
- **Runtime/CI impact:** zero regular build/CI cost. The primary fast command and
  optional full-profile costs are recorded above; both produce console + ledger
  evidence, not a CI artifact.
- **Owner:** `R030-BUILD`.
- **Revisit condition:** a plugin/analyzer gains module-aware baseline/ratchet
  semantics, the parent test-stack ownership is redesigned, any module enters
  publication admission, or repeated real dependency regressions demonstrate
  that a maintained exclusion registry has better signal than review.

SpotBugs имеет отдельное решение `Adopt` в 0.3.0. CPD остаётся diagnostic
control с условиями возможного будущего no-new-duplication ratchet; dependency
analysis по итогам текущего evidence не получает такой ratchet.

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
- [x] Immediate-risk SpotBugs findings исправлены
- [x] SpotBugs baseline filters узкие и обоснованы
- [ ] SpotBugs `check` стабильно входит в Maven `verify`
- [ ] SpotBugs запрещает новые findings принятого signal
- [x] PMD CPD aggregate report воспроизводим
- [x] CPD threshold откалиброван на repository evidence
- [x] Существенные CPD findings переданы в R030-QUAL
- [x] Существенные CPD findings имеют semantic disposition
- [x] PMD CPD diagnostic configuration и ownership приняты
- [x] Maven dependency-analysis report воспроизводим
- [x] Dynamic/framework false positives проверены
- [x] Maven dependency-analysis adoption decision принят
- [x] Adopted ratchets и suppressions документированы (`Defer`: ratchet/suppressions отсутствуют)
- [x] Status matrix обновлена
