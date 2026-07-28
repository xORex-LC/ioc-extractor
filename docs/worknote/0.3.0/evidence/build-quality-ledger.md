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
| PMD CPD aggregate | TBD | TBD | TBD | TBD | TBD | TBD | `planned` |
| Maven dependency analysis | Maven Dependency Plugin `3.9.0`; default bytecode analysis | `./mvnw -B -ntp -T 1C verify dependency:analyze-only` | Local console/report ledger; CI adoption undecided | Maven `47.745 s` including `verify`; sequential `-DskipTests` attribution `4.671 s` | Broad candidate signal; substantial parent-test, starter, transitive API, SPI and processor noise | `R030-BUILD` | `report-only` |

Допустимые rollout stages: `planned`, `report-only`, `triaged`, `baselined`,
`blocking` и `tightening`.

## Initial work-item queue

| Work item | Outcome | Mode | Entry dependency | State |
|---|---|---|---|---|
| `BUILD-SPOTBUGS-01` | Reproducible reactor-wide production-bytecode report, scope/cost inventory and raw findings | Report only; no mass remediation or merge gate | `R030-BASE` verified | `verified` |
| `BUILD-CPD-02` | Repository-wide production-source report and evidence-based `minimumTokens` calibration | Diagnostic/report only | `BUILD-SPOTBUGS-01` closed, unless matrix explicitly reorders independent tooling | `planned` |
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
запускает JDK-only integrity verifier. Registry `spotbugs-scope.tsv` содержит
disposition всех 23 reactor projects: 19 `analyzed`, root/TCK/`coverage-report`
как 3 `excluded` и сам `spotbugs-report` как один `aggregate`.
Verifier требует точного равенства registry и root `<modules>`, сверяет
artifactId/packaging и явный `skip=true` для каждого исключённого child project,
а также требует равенства 19 `analyzed` artifacts и dependencies report-модуля.
Ожидаемые XML/HTML пути затем выводятся из этого же registry. Поэтому новый
reactor-модуль fail-closed до явного disposition, а обычный `verify` не может
успешно завершиться при пропущенном, пустом или неожиданном report.

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
| `ioc-application-tck` | no | N/A | N/A | N/A | yes | Reusable JUnit/AssertJ test-contract library, не runtime production code |

Чистый 23-project reactor run сформировал 19 non-empty module XML/HTML пар и
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
удвоили baseline. Негативные проверки подтвердили оба слоя fail-closed
контракта: registry без существующего reactor-модуля и report POM без
соответствующей ordering dependency были отклонены как set mismatch, а временно
отсутствующий
`platform-errors/target/spotbugs/spotbugs.html` был отклонён с точным путём,
после чего файл восстановлен.

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

| Finding | Occurrences | Shared knowledge/behavior | Semantic differences | Disposition | Rationale | R030-QUAL finding |
|---|---|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD | TBD |

CPD report не является списком автоматических refactorings. Каждая существенная
находка проходит duplication triage из
[R030-QUAL](../goals/R030-QUAL-code-health.md).

## CPD configuration calibration

| Candidate `minimumTokens` | Finding count | Noise classes | Missed known duplicate | Runtime | Decision |
|---:|---:|---|---|---:|---|
| TBD | TBD | TBD | TBD | TBD | TBD |

Принятый threshold обосновывается repository evidence. Generated/vendor
exclusions перечисляются точными paths/selectors.

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

## Completion

- [x] SpotBugs report воспроизводим
- [x] SpotBugs signal/noise/cost оценены
- [x] SpotBugs production-module scope подтверждён
- [ ] Immediate-risk SpotBugs findings исправлены
- [ ] SpotBugs baseline filters узкие и обоснованы
- [ ] SpotBugs `check` стабильно входит в Maven `verify`
- [ ] SpotBugs запрещает новые findings принятого signal
- [ ] PMD CPD aggregate report воспроизводим
- [ ] CPD threshold откалиброван на repository evidence
- [ ] Существенные CPD findings переданы в R030-QUAL
- [ ] Существенные CPD findings имеют semantic disposition
- [ ] PMD CPD diagnostic configuration и ownership приняты
- [ ] Maven dependency-analysis report воспроизводим
- [ ] Dynamic/framework false positives проверены
- [ ] Maven dependency-analysis adoption decision принят
- [ ] Adopted ratchets и suppressions документированы
- [ ] Status matrix обновлена
