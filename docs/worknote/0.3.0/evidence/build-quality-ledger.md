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
| SpotBugs | TBD | TBD | XML + HTML: TBD | TBD | TBD | TBD | `planned` |
| PMD CPD aggregate | TBD | TBD | TBD | TBD | TBD | TBD | `planned` |
| Maven dependency analysis | Maven Dependency Plugin `3.9.0`; default bytecode analysis | `./mvnw -B -ntp -T 1C verify dependency:analyze-only` | Local console/report ledger; CI adoption undecided | Maven `47.745 s` including `verify`; sequential `-DskipTests` attribution `4.671 s` | Broad candidate signal; substantial parent-test, starter, transitive API, SPI and processor noise | `R030-BUILD` | `report-only` |

Допустимые rollout stages: `planned`, `report-only`, `triaged`, `baselined`,
`blocking` и `tightening`.

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

| Scope/module | Analyzed | Findings | Immediate fixes | Baseline filters | Clean rerun | Blocking evidence |
|---|---|---:|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD | TBD |

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
| TBD | TBD | TBD | TBD | TBD | TBD |

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

- [ ] SpotBugs report воспроизводим
- [ ] SpotBugs signal/noise/cost оценены
- [ ] SpotBugs production-module scope подтверждён
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
