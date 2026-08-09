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

Долгоживущая механика build-quality, её Maven lifecycle, scope-контракты и
процедуры сопровождения описаны в [build-quality.md](../../../dev/build-quality.md).
Этот ledger хранит только release-specific план, измерения и evidence для 0.3.0
и не является постоянным руководством после закрытия release line.

## Принятый evaluation scope

| Control | Scope 0.3.0 | Начальный режим | Возможный результат |
|---|---|---|---|
| SpotBugs | Все применимые production Java modules | Report only | Blocking no-new-findings check в `verify` |
| PMD CPD | Repository-wide production-source duplication | Report only | Diagnostic control + semantic dispositions |
| PMD source analysis | Поимённые rules в 19 production source roots | Report-only evaluation | `Adopt / Adopt with a reduced ruleset / Defer / Reject` |
| Maven `dependency:analyze-only` | Dependency hygiene evaluation | Report only | `Adopt / Adopt with exclusions / Defer` |
| PIT | Только `core/ioc-domain`; ведётся в R030-TEST | Diagnostic pilot | `Adopt / Extend / Defer / Reject` |

## Tool evaluation

| Control | Version/config | Local command | CI/report artifact | Runtime | Signal/noise | Owner | Stage |
|---|---|---|---|---:|---|---|---|
| SpotBugs | Maven Plugin `4.10.3.0`, engine `4.10.3`; `effort=Max`, `threshold=Low`, production bytecode only; exact raw-baseline gate | `make verify` | Per applicable module: raw XML + filtered XML/HTML; raw and filtered aggregates under `build-support/spotbugs-report/target/`; CI artifact `spotbugs-reports-<run>` | `120 s` adoption full-reactor wall; `+12 s` / `+11.1%` against the same-session pre-ratchet `108 s` run | Current full-reactor evidence: 65 reviewed findings (47 false positives + 18 policy noise), 61 generated narrow selectors, 0 visible | `R030-BUILD` | `blocking` |
| PMD CPD aggregate | Maven Plugin `3.28.0`, bundled PMD `7.17.0`; Java production sources, `minimumTokens=75`, identifiers/literals/annotations significant | `make clean && make verify` | `build-support/cpd-report/target/cpd/`; CI artifact `cpd-report-<run>` | `95.52 s` clean reactor wall; CPD module `2.703 s` | 11 raw matches / 10 semantic findings; 7 debt candidates, 3 retained clusters | `R030-BUILD` + `R030-QUAL` | `report-only` |
| PMD source-analysis evaluation | Maven Plugin `3.28.0`; explicit PMD `7.26.0` is the P1 compatibility candidate; named rules only | Not implemented in P0 | None in P0 | Not measured | P0 inventory complete; raw signal/noise pending P1/P2 | `R030-BUILD` | `planned` (`P0` complete) |
| Maven dependency analysis | Maven Dependency Plugin `3.11.0`; fast direct goal + opt-in full `dependency-analysis` profile; default bytecode analyzer | `make dependency-analysis` | Local console/report ledger; deliberately absent from regular CI | Fast sequential package + analysis observed at `5.313–7.677 s` Maven / `6.75–8.72 s` process; full profile timing below | 14 direct POM mismatches corrected; residual `56 / 34 / 12` candidate occurrences are test-aggregate, starter, SPI and transitive-runtime noise | `R030-BUILD` | `report-only`, blocking adoption deferred |

Допустимые rollout stages: `planned`, `report-only`, `triaged`, `baselined`,
`blocking` и `tightening`.

## Initial work-item queue

| Work item | Outcome | Mode | Entry dependency | State |
|---|---|---|---|---|
| `BUILD-SPOTBUGS-01` | Reproducible reactor-wide production-bytecode report, scope/cost inventory and raw findings | Report only; no mass remediation or merge gate | `R030-BASE` verified | `verified` |
| `BUILD-CPD-02` | Repository-wide production-source report and evidence-based `minimumTokens` calibration | Diagnostic/report only | `BUILD-SPOTBUGS-01` closed, unless matrix explicitly reorders independent tooling | `verified` |
| `BUILD-DEPS-03` | Semantic disposition of the captured dependency candidates and `Adopt / Adopt with exclusions / Defer` decision | Evaluation only | `BUILD-CPD-02` closed | `verified` |
| `BUILD-SPOTBUGS-04` | Finding triage, immediate-risk fixes, narrow reviewed baseline and deterministic rerun | Triage/baseline | `BUILD-SPOTBUGS-01` report | `verified` (`C0..C5` completed) |
| `BUILD-SPOTBUGS-05` | Accepted no-new-findings signal wired into canonical Maven `verify` | Blocking ratchet | `BUILD-SPOTBUGS-04` closed | `verified` |
| `BUILD-PMD-06` | Bounded evaluation of named PMD source-analysis rules and explicit adoption disposition | P0 inventory, then report-only measurement and triage | `BUILD-SPOTBUGS-04` closed and explicit status-matrix activation | `in-progress` (`P0` completed; `P1` pending) |

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
| Exact `NP_*` + class + method, 9 findings | Child paths from verified NIO listings | The nullable JDK return is constrained by direct-child provenance at each call site | Owning ingest/store modules | Remove when control flow becomes analyzer-provable; review if a path ceases to be a direct child | `C1-NP-A`; lifecycle/remote-inbox and SMB findings were hardened and removed under `NP-HARDEN-01` / `SMB-PLAN-01` |
| Exact singleton pattern + class/member, 1 finding | Immutable `ArtifactFilter.NONE` flyweight | The named empty instance has no singleton lifecycle contract | `adapter-sink-csv` | Review if `ArtifactFilter` gains identity/lifecycle semantics | `C1-COR-A` |
| Exact `EI_EXPOSE_REP*` + class/member, 5 findings | Immutable snapshots and lifecycle-owned bootstrap resources | Construction already copies the collection, or the object deliberately exposes the same managed resource | Owning application/bootstrap modules | Remove when analyzer recognizes the copy; review if construction stops copying or lifecycle ownership crosses the bootstrap boundary | `C2-REP-C/E` |
| Exact `EI_EXPOSE_REP` + generated accessor/field, 18 findings | Null-preserving Spring-bound configuration snapshots | Defensive copy removes the external mutable owner and the accessor is unmodifiable; SpotBugs does not prove the wrapper/backing-copy relationship | `ioc-app/configuration` | Review if copying/accessor semantics or collect-all validation changes | `REP-FIX-FP` |
| Exact `THROWS_*` + class + method, 18 findings | CLI, pipeline, ingestion, recovery and startup failure boundaries | `policy-noise`: SpotBugs correctly sees catch/rethrow, but its generic policy is inapplicable to documented unchecked boundaries; five methods were hardened so close/accounting/observer failures remain suppressed behind the primary | Owning CLI/application/adapter/bootstrap modules | Remove if the method no longer performs boundary work; review any async, retry, translation or swallow contract | `C2-EX-A..E`, `I4-SB-04`, `FUP-SB-01`, `D-022` |
| Exact `SE_BAD_FIELD` / `VA_FORMAT_STRING_USES_NEWLINE` + class + method, 2 findings | Non-serialized diagnostics and SQL text-block whitespace | The warned boundary does not exist, or the newline is SQL grammar rather than user-visible text | `platform-diagnostics`, `adapter-store-jdbc` | Review if Java serialization is introduced or SQL text becomes user-visible output | `C2-MIX-F/G` |
| Exact `VO_VOLATILE_INCREMENT` + class + field, 2 findings | Same-key admission/release accounting | Both mutations occur under `ConcurrentHashMap.compute` for the same key; `volatile` is only for snapshot visibility. Compiler-generated lambda names are deliberately excluded from the selector | `platform-concurrency` | Remove if accounting changes; review any mutation outside same-key `compute` | `I4-SB-02..03`, `D-025` |

The checked-in filter contains 61 exact selectors for the remaining 65 findings. Four
extra occurrences share the same stable pattern/class/member selector with another reviewed
occurrence; SpotBugs filters cannot address an instance hash. No selector is
package-, category- or pattern-wide. Analyzer errors, omitted modules and missing
reports remain integrity failures and are never represented as suppressions.

## SpotBugs findings

| Pattern/category | Scope | Count | Highest risk | False-positive class | Disposition/evidence |
|---|---|---:|---|---|---|
| `EI_EXPOSE_REP` + `EI_EXPOSE_REP2` | Immutable snapshots and lifecycle-owned bootstrap resources | 23 | P2 | Analyzer не распознаёт immutable snapshot либо intentional lifecycle ownership | 5 исходных `C2-REP-C/E` + 18 post-fix accessors — false positives; все 44 real aliases устранены `REP-FIX`, 26 obsolete selectors удалены |
| `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `Path.getFileName()` под direct-child repository invariants | 9 | P2 | Nullable JDK API без знания direct-child provenance | `C1-NP-A`: false positives; lifecycle/remote-inbox и SMB upload-plan findings, три projection-path findings из `IR-02` и два Tika findings из `C1-NP-E` устранены validation/adapter fixes |
| `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | Public/application boundaries с documented unchecked failures | 18 | P3 | Generic policy noise, не analyzer false positive | `C2-EX-A..E` плюс два post-inventory boundary cases: cleanup/accounting/observer work сохраняют runtime type, cause и stack; семь occurrences получили explicit secondary-failure hardening |
| `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` + `SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING` | JDBC schema, migration и query-shape SQL | 10 | P1/R10 | Analyzer не различает controlled adapter metadata и untrusted input | `C1-SQL-A/B/D/E/F`: configured/internal identifiers валидируются и quote-ятся, values bind'ятся, migrations code-owned; два health PRAGMA findings устранены typed/literal fix |
| `SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR` | `ArtifactFilter.NONE` flyweight | 1 | P2 | Named shared immutable instance, не singleton contract | `C1-COR-A`: false positive; оба `RV_RETURN_VALUE_IGNORED` устранены `IR-01` fix |
| `IS2_INCONSISTENT_SYNC` | `CsvArtifactSliceWriter.active` | 0 | P2/R17 | Исходный synchronous monitor-confined contract не содержал живого race, но shared callback state создавал temporal coupling | `C1-CON-A`: resolved-by-fix; локальная materialization передаётся reader напрямую, failure-isolation regression закрепляет отсутствие cross-operation contamination |
| Остальные 3 patterns (`VA`, `VO`, `SE`) | Несколько production modules | 4 | P2 | SQL formatting context, serialization-neutral diagnostics и guarded concurrency accounting | 4 false positives; 11 local legacy findings из `C2-MIX-A..E/H` устранены и удалены из baseline |

После representation remediation, `IS2`, Tika и nullable-path hardening остаются 65 reviewed findings;
priority P1 — 1, P2 — 38, P3 — 26. Category: `MALICIOUS_CODE` — 23,
`STYLE` — 9, `BAD_PRACTICE` — 20, `SECURITY` — 10, `MT_CORRECTNESS` — 2,
`CORRECTNESS` — 1. Исторический raw C3 snapshot до remediation — 114.

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

`BUILD-SPOTBUGS-04/C2` завершил семантический triage оставшихся 79 findings.
Первоначальная taxonomy была 55 `accepted-legacy`, 23 `false-positive` и 1
`fix-now`; `D-022` переклассифицировал 16 exception-flow cases в
`policy-noise`. Общий disposition исходных 118 findings после follow-up:
39 false positives, 16 policy noise, 55 accepted legacy, 3 fix-now и 5 resolved
findings. Representation debt пока не имеет известных mutation call sites; его
исправление требует null-preserving copies, сохраняющих ADR-0016 collect-all
validation. Все 16 exception-flow findings сохраняют исходный runtime failure;
аудит дополнительно закрепил suppressed accounting/close/observer failures в
пяти методах.

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

Оставшиеся 59 false-positive и 18 policy-noise findings покрыты одним
versioned filter: 71 точный pattern + class + method/field selector. Шесть
дополнительных occurrences находятся в уже выбранных точных методах; instance
hash не является частью SpotBugs filter grammar. Root-inherited module execution
применяет filter один раз, а aggregate объединяет эти module XML без второй
baseline copy. Canonical `make verify` прошёл 24/24 за `02:17`; независимая
сверка подтвердила 19 module XML/HTML pairs плюс aggregate, 0 visible findings,
`errors=0`, `missingClasses=0`. Findings остаются report-only; новый unmatched
signal будет виден, но станет blocking ratchet только в `BUILD-SPOTBUGS-05`.

Follow-up `MIX-FIX` устранил все 11 локальных accepted-legacy findings
`SB04-105..114/118`: SMB share type теперь проверяется явно и получает
non-retryable taxonomy, `DiagnosticException` final, redundant/dead code удалён,
machine tokens используют `Locale.ROOT`, Jackson fallback ловит только
`JsonProcessingException`, operator description использует `%n`. Семь новых
focused regressions и существующие transition/resource tests зелёные; после
удаления 11 selectors все 19 module reports содержат 0 visible findings.

Follow-up `REP-FIX` устранил все 44 real mutable aliases `SB04-040..042/044/046..063/067..088`.
Adapter и Spring-bound records создают null-preserving unmodifiable snapshots,
не превращая invalid binding data в constructor failure до collect-all
validation. Real-binding и direct-construction regressions проверяют caller
isolation, immutable accessors, сохранение null elements/values и полный набор
validation violations. Все 22 constructor и четыре adapter-accessor findings
исчезли; 18 generated `IocProperties` accessors остаются exact false positives,
поскольку analyzer не выводит ownership private backing copies. Baseline уменьшен
до 77 findings / 71 selector без accepted legacy. Последующий `IS2` follow-up
устранил shared callback state и уменьшил baseline до 76 findings / 70
selectors. Последующий Tika path follow-up устранил два реальных nullable
dereference и уменьшил текущий baseline до 74 findings / 68 selectors. Первый clean C4 run также
обнаружил хрупкие compiler-generated lambda names в двух concurrency selectors;
они заменены одним stable pattern + exact class + field selector.

Последующий nullable-path hardening сохранил исходные provenance proofs, но
перенёс гарантии в исполняемый код: lifecycle adapter отклоняет empty directory
roots и явно проверяет target parent, а remote inbox containment сравнивается от
trusted non-null root. Четыре записи `SB04-018..019/038..039` удалены; текущий
baseline содержит 70 findings / 65 selectors.

SMB upload-plan follow-up заменил `peek`-валидацию и три повторных nullable
dereference единым immutable plan: validated leaf и exact temporary remote path
вычисляются один раз и затем совместно используются upload и size verification.
Пять записей `SB04-033..037` удалены; текущий baseline содержит 65 findings /
61 selectors.

Final C4 clean и immediate repeat воспроизвели один результат: 24/24 reactor,
836 tests, 0 failures/errors, 2 external SMB skips, 19 module XML/HTML pairs плюс
aggregate, 77 accepted / 0 visible findings, analyzer errors/missing classes 0/0.
`BUILD-SPOTBUGS-04` закрыт как `verified`; это entry evidence для отдельного
blocking adoption в `BUILD-SPOTBUGS-05`.

### BUILD-SPOTBUGS-05 blocking adoption

Обычный Maven `verify` теперь выполняет один unfiltered SpotBugs analysis на
каждом из 19 production-модулей и пишет `spotbugs-raw.xml`. Checked-in
`spotbugs-accepted-findings.xml` хранит 65 отдельных identities: module, type,
hash/occurrence, priority/rank/category, primary class/member/JVM descriptor,
source path/bytecode anchor, disposition, owner, evidence, rationale, review
condition и narrow presentation selector. Source line остаётся advisory.

Root `validate` проверяет baseline schema и генерирует единственный operational
`FindBugsFilter` с 61 selectors в `target/build-quality`; tracked filter удалён.
SpotBugs workflow `Filter` строит module-local filtered XML без второго анализа,
после чего `default.xsl` формирует HTML. Enforcement выполняется только по raw
XML, поэтому новый occurrence с уже принятым hash/методом не скрывается
presentation filter.

Поздний aggregate gate требует точного равенства baseline и raw instances,
нулевых analyzer errors/missing classes, пустых filtered reports и multiset
равенства raw aggregate объединению 19 module reports. New, stale, moved и
metadata-drifted findings блокируют build. Root harness проходит 4 scope happy / 18
negative и 4 baseline happy / 24 negative scenarios. Baseline schema `2`
заменяет две общие review-категории каталогом конкретных triggers: каждая запись
ссылается на условие изменения внешнего инварианта или boundary contract;
generic, unknown, duplicate и unused triggers блокируют root `validate`.
Proposal-only команда читает уже созданные raw module reports и пишет под
`target/` только new candidate identities и stale acceptance IDs, намеренно не
генерируя disposition/owner/evidence/rationale/review/suppression и не изменяя
tracked baseline. Fixture закрепляет эти свойства и отказ для output вне
`target/`; неудачная попытка также удаляет прежний generated proposal, чтобы он
не выглядел актуальным. Отдельная target-local
mutation, удалившая acceptance `SB04-089`, завершилась exit `1` и показала raw
finding как новый.

Adoption `make verify` прошёл 24/24 за `02:00`; последующий `IS2` follow-up run —
за `01:40`: 182 suites / 837 tests / 0 failures / 0 errors / 2 external SMB
skips, 19 raw XML с 76 findings, 19 filtered XML/HTML с нулём visible findings,
оба aggregate и analyzer errors/missing classes `0/0`. Измеренная при adoption
добавочная стоимость относительно same-session pre-ratchet run `01:48`
составляет `12 s` (`11.1%`). Полный
execution evidence и дальнейший operating procedure находятся в
[BUILD-SPOTBUGS-05 worknote](../build-spotbugs-05-worknote.md).

Post-adoption Tika path hardening removed `SB04-029..030` after a focused root
regression proved that the provider can open a filesystem root before the null
file-name dereference. The focused source-adapter test and Maven verify/SpotBugs
run passed with zero adapter findings; that step reduced the tracked baseline to
74 identities and 68 selectors. The later nullable-path hardening removed four
additional analyzer-opaque flows, leaving 70 identities and 65 selectors.
The later SMB upload-plan hardening removed five more flows, leaving 65
identities and 61 selectors.

Clean-checkout follow-up выявил скрытый контракт Maven Plugin: у
`spotbugs-aggregate` значение `spotbugsXmlOutputFilename` одновременно задаёт
путь поиска module XML и путь собственного aggregate XML. Старый raw execution
писал aggregate XML в каталог filtered execution и на чистом GitHub runner
падал с `NoSuchFileException`, хотя warm local workspace проходил. Module и
aggregate raw XML перенесены в единый независимый
`target/spotbugs-raw/spotbugs-raw.xml`, filtered XML/HTML остаются в
`target/spotbugs/`. Root `validate` теперь фиксирует обе стороны discovery/output
контракта тремя negative mutations. Focused clean 22-project reactor прошёл за
`01:37` с 74 accepted / 0 visible findings. Полный clean 24-project `make verify`
и немедленный incremental repeat прошли за `02:38` и `02:23`: 182 suites / 838
tests / 0 failures / 0 errors / 2 external SMB skips, 19 raw module XML, 19
filtered XML/HTML и оба aggregate views, 74 accepted / 0 visible.

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
список анализирует все текущие production Java source paths одним invocation
(508 в последнем полном report; 499 в adoption snapshot), поэтому
межмодульные совпадения видны, а `ioc-application-tck`,
tests, Maven-generated roots, build outputs и build-support POMs не попадают в
scope. Checked-in vendor/generated trees в repository отсутствуют; явные
`**/vendor/**` и `**/generated/**` selectors являются дополнительным guard.

Native machine-readable XML и Doxia HTML формируются в
`build-support/cpd-report/target/cpd/`. Findings не вызывают failure: goals
`cpd-check`/`aggregate-cpd-check` не подключены. Ошибка analyzer/report renderer
останавливает Maven. `initialize` заранее удаляет предыдущий `target/cpd`, а
после analysis verifier требует non-empty XML/HTML, проверяет CPD XML
namespace/root и HTML document marker, затем сверяет полный набор unique XML
paths с текущими Java files всех analyzed roots. Поэтому stale, empty, malformed,
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
XML. Adoption clean run подтвердил 499 unique production source paths; последний
полный report после ING-10 и SpotBugs follow-ups содержит 508 paths при тех же
11 duplications. Оба snapshots не содержат TCK/generated/vendor references и
имеют оба report formats.

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

## PMD source-analysis evaluation

Status matrix активировала `BUILD-PMD-06` после semantic closure SpotBugs.
Это отдельное bounded evaluation, а не расширение принятого CPD control и не
решение о постоянном adoption.

`P0` завершён 2026-08-09 документационным inventory без изменения Maven/CI:

- подтверждён текущий Maven PMD Plugin `3.28.0` с bundled PMD `7.17.0`;
- PMD `7.26.0` выбран как compatibility candidate для `P1`, потому что это
  текущий стабильный engine с более свежими fixes на рассматриваемой rule
  surface; несовместимость не разрешает silent fallback;
- scope зафиксирован как те же 19 production `src/main/java` roots, что CPD;
  root, три build-only POMs, `ioc-application-tck`, tests и generated/vendor
  sources имеют явное exclusion disposition;
- default ruleset и целые categories отклонены: четыре интересующие категории
  локального PMD `7.17.0` содержат 222 rules и смешивают разные policy;
- поимённые candidates разделены на dead/unused, correctness/resource,
  complexity и allocation/performance tracks; charset/locale portability
  включена в correctness track;
- test rules, security/SAST, style/documentation, broad architecture metrics и
  multithreading rules не входят в initial evaluation;
- baseline, suppressions, code fixes, `pmd:check`, обычный `make verify` и CI в
  `P0` не изменялись.

Следующий checkpoint `P1` должен отдельно доказать совместимость Maven PMD
Plugin `3.28.0` + PMD `7.26.0`, точный 24-project disposition, deterministic
XML/HTML, fail-closed report integrity и стоимость. Findings остаются
report-only. После per-track triage `P3` обязан принять одно из решений:
`Adopt`, `Adopt with a reduced ruleset`, `Defer` или `Reject`.

Полный rules inventory, rationale, build-topology hypothesis и exit criteria:
[BUILD-PMD-06 worknote](../build-pmd-06-worknote.md).

## Deferred tool boundaries

Следующие controls не требуют evaluation для закрытия 0.3.0:

- GitHub CodeQL и SAST/SecOps;
- Spotless и Checkstyle;
- japicmp и Revapi;
- Error Prone и NullAway;
- SonarQube и Qodana;
- PMD rules вне поимённого `BUILD-PMD-06` candidate inventory.

Их отсутствие не является missing evidence текущего `R030-BUILD`.

PMD source-analysis evaluation теперь ведётся отдельным `BUILD-PMD-06` и входит
в текущую очередь `R030-BUILD`. Это не разрешает default/category-wide rules
или автоматический adoption: только зафиксированный P0 ruleset проходит P1/P2,
после чего требуется явное P3 disposition.

## Completion

- [x] SpotBugs report воспроизводим
- [x] SpotBugs signal/noise/cost оценены
- [x] SpotBugs production-module scope подтверждён
- [x] Immediate-risk SpotBugs findings исправлены
- [x] SpotBugs baseline filters узкие и обоснованы
- [x] SpotBugs blocking gate стабильно входит в Maven `verify`
- [x] SpotBugs запрещает новые findings принятого signal
- [x] PMD CPD aggregate report воспроизводим
- [x] CPD threshold откалиброван на repository evidence
- [x] Существенные CPD findings переданы в R030-QUAL
- [x] Существенные CPD findings имеют semantic disposition
- [x] PMD CPD diagnostic configuration и ownership приняты
- [x] PMD source-analysis P0 scope и поимённый candidate ruleset зафиксированы
- [ ] PMD source-analysis report/cost/signal evidence получены
- [ ] PMD source-analysis adoption disposition принято
- [x] Maven dependency-analysis report воспроизводим
- [x] Dynamic/framework false positives проверены
- [x] Maven dependency-analysis adoption decision принят
- [x] Adopted ratchets и suppressions документированы (`Defer`: ratchet/suppressions отсутствуют)
- [x] Status matrix обновлена
