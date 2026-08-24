# Инженерная безопасность

Этот документ задаёт проектную политику Security Engineering и Secure Software
Development Lifecycle (Secure SDLC) для `ioc-extractor`. Он описывает, что
защищается, какие контроли действуют, где проходят security-gates, какое
evidence подтверждает их выполнение и как развивается security-контур проекта.

Политика является живым правилом для всего проекта. Она не является
сертификацией соответствия стандарту и не обещает отсутствие уязвимостей.
Задача политики — сделать security-решения явными, проверяемыми и
воспроизводимыми для разработчиков, операторов и автоматизации.

Это не vulnerability disclosure policy. Если проекту понадобится публичный
канал конфиденциального сообщения об уязвимостях, поддерживаемые версии и
правила раскрытия будут описаны отдельно в `.github/SECURITY.md`.

Организация документа опирается на четыре outcome-группы
[NIST SSDF](https://csrc.nist.gov/projects/ssdf): подготовить процесс, защитить
software artifacts, производить защищённое ПО и реагировать на найденные
уязвимости. [OWASP SAMM](https://owaspsamm.org/model/) используется как карта
областей зрелости — Governance, Design, Implementation, Verification и
Operations, а не как заявленный compliance level.

## Связь с остальной документацией

- [PRINCIPLES.md](PRINCIPLES.md) задаёт общие инженерные принципы;
- [ARCHITECTURE.md](ARCHITECTURE.md) и [BOUNDARIES.md](BOUNDARIES.md) описывают
  архитектурные границы и их автоматическое enforcement;
- [RELEASE-PROCESS.md](RELEASE-PROCESS.md) потребляет security-gates при выборе,
  сборке, публикации и deployment release candidate;
- [THREAT-MODEL.md](THREAT-MODEL.md) трассирует активы × границы доверия ×
  угрозы × контроли и делает явным остаточный риск (risk-based STRIDE per
  boundary);
- [KNOWN-ISSUES.md](KNOWN-ISSUES.md) регистрирует конкретные обнаруженные gaps и
  осознанные seams, но не каждую возможную идею из roadmap;
- [ADR/](ADR/) фиксирует значимые и труднообратимые security-решения;
- локальные scratch-notes могут хранить временный triage и исследование, но не
  входят в публикуемую документацию и не заменяют tracked policy, issue или
  release evidence.

## 1. Область действия и активы

Политика распространяется на весь путь от source до эксплуатации:

- исходный код, Git history, Maven reactor и GitHub Actions workflows;
- сторонние Maven dependencies, Maven plugins и GitHub Actions;
- bootable jar, release metadata, checksums и deployment tooling;
- операторскую конфигурацию и secrets, включая SMB credentials и NVD API key;
- входные документы, извлечённые IOC, SQLite business/service state, CSV/export
  projections и manifests;
- daemon process, локальный actuator и удалённые SMB integrations;
- diagnostics, logs, health и security evidence.

Основные защищаемые свойства:

| Свойство | Что недопустимо |
|---|---|
| Конфиденциальность | утечка credentials, query tokens, чувствительных IOC или operator data в Git, logs и reports |
| Целостность | незаметная подмена source, dependency, release artifact, manifest, checksum или durable state |
| Доступность | неограниченное потребление CPU/RAM/disk/threads, бесконечные retries или parser bombs |
| Прослеживаемость | release/deployment без связи с commit, version, digest и проверочным evidence |
| Управляемость | скрытые suppressions, неизвестная конфигурация, необъяснённый finding или незафиксированный accepted risk |

### 1.1. Границы доверия

Следующие входы считаются недоверенными или частично доверенными:

1. **Документы и IOC.** Файл может быть повреждённым, намеренно сложным или
   содержать данные, опасные для parser/regex/logging paths.
2. **Удалённое SMB-хранилище.** Имена, metadata, содержимое и доступность
   контролируются внешней системой; credentials являются секретом.
3. **Dependencies и build tooling.** Maven artifacts, plugins и Actions — код
   третьих сторон, исполняемый при build или runtime.
4. **GitHub Actions boundary.** Workflow code, runner, caches, artifacts,
   `GITHUB_TOKEN` и environment secrets имеют разные уровни доверия и должны
   получать минимально необходимые права.
5. **Операторская конфигурация.** Она привилегирована, но может содержать
   ошибки. Неизвестное или несогласованное `ioc.*` значение должно приводить к
   понятному startup failure до полезной работы.
6. **Release delivery, локальный host и output consumers.** Published bytes
   переходят из CI к оператору, runtime state живёт в host filesystem, а
   CSV/export покидает приложение. Checksum, host permissions и безопасность
   downstream consumer являются разными контролями и проверяются отдельно.

Actuator по умолчанию слушает loopback. Публичный HTTP API, web UI,
authentication/authorization boundary и multi-tenant execution в текущий
supported contract не входят. Их появление является изменением attack surface
и требует отдельного security review до реализации.

## 2. Принципы

1. **Risk before tool.** Сначала фиксируются актив, угроза и требуемый outcome;
   затем выбирается scanner, test или runtime control.
2. **Defense in depth.** Один scanner, framework default или сетевой периметр
   не считаются достаточной защитой.
3. **Least privilege.** Процессы, users, tokens, secrets и remote accounts
   получают только необходимые права и только на необходимое время.
4. **Secure and explicit defaults.** Неизвестная конфигурация, нарушенная
   integrity и отсутствие обязательного evidence не должны тихо продолжать
   release или полезную работу.
5. **Untrusted data stays at the boundary.** Core не зависит от parser,
   transport и storage frameworks; внешние библиотеки изолируются adapters.
6. **No secret in source or telemetry.** Secrets не коммитятся, не передаются
   literal-аргументами и не выводятся в logs/reports. Чувствительные значения
   редактируются до INFO и выше.
7. **CVSS is a signal, not a verdict.** Версия и score определяют приоритет
   исследования, но applicability подтверждается dependency path, scope,
   vendor advisory, используемым API и фактической exposure.
8. **Exceptions are reviewable.** Suppression и accepted risk имеют узкий
   scope, rationale, owner, срок или trigger пересмотра.
9. **Build once, promote the same bytes.** Published version связывается с
   immutable tag, commit и digest; production artifact не пересобирается между
   средами.
10. **Evidence over memory.** Зелёный статус подтверждается tests, reports,
    checksums и ссылками на workflow runs, а не устным воспоминанием.

## 3. Модель состояния контролей

Наличие конфигурации ещё не означает действующий контроль. Реестр (§4) — самая
изменяемая часть этого документа; принципы (§2), lifecycle (§6) и правила (§13)
стабильны. В реестре используются следующие состояния:

| Состояние | Значение |
|---|---|
| **Enforced** | автоматизировано и останавливает нарушающий build/start/release path |
| **Monitored** | автоматически обнаруживает сигнал, но disposition принимает человек |
| **Manual** | обязательный процесс существует, но выполняется по checklist |
| **Configured** | implementation готова, но operational activation/evidence ещё не подтверждены |
| **Designed** | контракт принят, automation ещё не реализована полностью |
| **Planned** | направление известно и имеет activation trigger, но обязательства реализации пока нет |
| **Not applicable** | контроль неприменим к текущей attack surface; причина и trigger пересмотра зафиксированы |
| **Accepted risk** | риск признан применимым и временно принят с owner и review condition |

`Planned` и `Not applicable` не являются «зелёными» контролями. `Accepted risk`
не превращает vulnerability в false positive.

## 4. Реестр текущих контролей

### 4.1. Governance, architecture и data boundaries

| ID | Контроль | Состояние | Enforcement / evidence |
|---|---|---|---|
| `SEC-GOV-1` | Project-wide security policy и control registry | Manual | этот tracked документ, карта в [README.md](README.md), обязательный review вместе с изменением security boundary |
| `SEC-GOV-2` | Baseline threat model (risk-based STRIDE per boundary), asset×threat×control трассировка | Manual | [THREAT-MODEL.md](THREAT-MODEL.md); living baseline пересматривается при новой границе доверия или изменении supported deployment/output consumer |
| `SEC-ARC-1` | Inward dependency rule и изоляция внешних libraries в adapters | Enforced | module graph, ArchUnit и Maven reactor; [BOUNDARIES.md](BOUNDARIES.md) |
| `SEC-CFG-1` | Strict `ioc.*` startup boundary | Enforced | unknown-key preflight, typed binding, semantic validation и `CONFIG.*` failure analysis; [ADR-0016](ADR/0016-config-preflight-strict-binding.md) |
| `SEC-INP-1` | RE2-safe regex contract | Enforced | RE2/J по умолчанию, JDK fallback и RE2-compatible patterns; [processing.md](dev/processing.md) |
| `SEC-INP-2` | Bounded resource policy для document parsing | Planned | gap зарегистрирован как `SRC-2` в [KNOWN-ISSUES.md](KNOWN-ISSUES.md); activation — отдельный hardening slice |
| `SEC-INP-3` | Параметризованный durable-write contract | Enforced + Monitored | шесть regression-тестов останавливают build при ослаблении известных trust boundaries: business values bind'ятся, configured identifiers соответствуют `[A-Za-z][A-Za-z0-9_]*`, internal identifiers — `[A-Za-z_][A-Za-z0-9_]*` и quote-ятся, SQL types allowlisted; health PRAGMA закрыты typed enum + literal execute-sites. SpotBugs автоматически сигнализирует о новых non-constant JDBC entry points, а exact-baseline gate блокирует их до исправления или явного reviewed disposition |
| `SEC-INP-4` | Bounded managed CSV intake и source authority ceiling | Enforced | strict charset/dialect/header recognition, snapshot/field/record/row/column bounds, formula rejection, declarative artifact/routing/merge ceiling и safe row diagnostics; [dataframe import](dev/dataframe-import.md) |
| `SEC-OUT-1` | CSV spreadsheet formula-injection applicability contract | Manual + Enforced defaults | для shipped default profile path `not_applicable`: marker matches начинаются с `БИБ`/`Письмо`, остальные колонки ограничены indicator/value/const contract; универсальной output-neutralization нет, configuration-dependent seam отслеживается как `OUT-2`; будущий фикс обязан учитывать machine-consumer semantics |
| `SEC-LOG-1` | Redaction чувствительных IOC/URL components и transport credentials | Enforced | `SensitiveLogValueSanitizer`, diagnostic formatter и regression tests; [observability.md](dev/observability.md) |
| `SEC-DATA-1` | Atomic durable protocols и checksummed immutable export slices | Enforced | ledgers, manifests, `_SUCCESS`, checksum verification и recovery contracts; [sync.md](dev/sync.md) |
| `SEC-DATA-2` | Managed-import ownership, immutable evidence и exactly-once promotion | Enforced | local atomic move либо SMB server-side rename, private hashed snapshot, sealed stage, one-transaction promotion и dataframe receipt; protected source/report terminal unit |

### 4.2. Dependencies, CI и supply chain

| ID | Контроль | Состояние | Enforcement / evidence |
|---|---|---|---|
| `SEC-SCA-1` | Централизованные dependency versions и build hygiene | Enforced | root [dependencyManagement и Maven Enforcer](../pom.xml), `./mvnw verify` |
| `SEC-SCA-2` | Dependabot dependency/malware alerts, security updates и weekly version updates для Maven/Actions | Configured | GitHub settings + [dependabot.yml](../.github/dependabot.yml); activation подтверждается после обработки default branch и первого cycle |
| `SEC-SCA-3` | OWASP Dependency-Check aggregate по effective reactor graph | Configured | local scan проверен; [weekly/manual workflow](../.github/workflows/dependency-security.yml) и candidate gate требуют operational proof в default branch |
| `SEC-SCA-4` | Узкие Dependency-Check suppressions без stale rules | Enforced | tracked [dependency-check-suppressions.xml](../dependency-check-suppressions.xml), `failBuildOnUnusedSuppressionRule=true` |
| `SEC-SCA-5` | Independently trusted artifact verification для Maven dependencies/plugins | Planned | desired outcome — проверять resolved bytes по project-owned trusted digest/provenance, а не только repository-provided checksum ([Maven Resolver distinction](https://maven.apache.org/resolver/expected-checksums.html)); механизм и безопасный update workflow выбираются отдельным design spike. Trigger — trusted CI release builder, публикация reactor-модулей или внешний supply-chain requirement |
| `SEC-CI-1` | Tests, golden E2E, boundaries и docs links на push/PR | Enforced | Maven gate: `./mvnw verify`; отдельный `doc-links` job: [ci.yml](../.github/workflows/ci.yml) |
| `SEC-CI-2` | NVD secret isolation и read-only security job | Configured | job явно привязан к Environment `SECURITY CHECKS`, `NVD_API_KEY` доступен только explicit update step; следующий offline scan не получает secret, token permissions ограничены `contents: read`; enforcement действует при запуске security workflow |
| `SEC-CI-3` | Явные минимальные workflow permissions и immutable full-SHA pinning Actions | Enforced | все workflows default к `contents: read`, write выдаётся только release job; remote Actions закреплены полным SHA с version comment, а tools contract отклоняет tag refs; Dependabot сохраняет update path |
| `SEC-CI-4` | Dependency Review на PR dependency changes | Planned | trigger — добавление blocking PR control после baseline/dry run |
| `SEC-CI-5` | Secret scanning и push protection | Planned | trigger — подтверждённая доступность функции для repository и согласованный response workflow |
| `SEC-CI-6` | Внешний Codecov coverage reporting | Designed | signal-only upload/history/PR annotations поверх project-owned JaCoCo XML; status не является required и outage не блокирует PR/release; remote Action подчиняется `SEC-CI-3`, token/permissions/data-sharing фиксируются до activation |
| `SEC-VER-1` | Java/GitHub Actions SAST | Planned | сначала non-blocking baseline и noise triage; blocking policy вводится отдельно; generic SpotBugs из build-quality контура не закрывает этот security control без отдельно принятого security ruleset |
| `SEC-VER-2` | Generic web DAST | Not applicable | нет публичного HTTP/auth surface; пересмотреть при появлении внешнего network API/UI |
| `SEC-VER-3` | Негативный/fuzz/property корпус вокруг document parsing, paths, manifests и resource budgets | Planned | минимальные malformed fixtures хранятся в test resources, крупные/ресурсоёмкие случаи генерируются test builders/scripts; resource assertions и hostile-path cases связывают evidence с `SEC-INP-2`/`SRC-2` и §10 |

### 4.3. Release и runtime

| ID | Контроль | Состояние | Enforcement / evidence |
|---|---|---|---|
| `SEC-REL-1` | Version → tag → commit → build metadata → SHA-256 identity chain | Configured | Slices 2–5 реализовали embedded/CLI/deployment identity, checksum verification и [tag/manual draft workflow](../.github/workflows/release.yml); первый tag run остаётся operational proof до состояния `Enforced` |
| `SEC-REL-2` | Exact-candidate dependency scan до tag | Configured | manual `Dependency Security` run является release gate; первый remote run ещё требуется |
| `SEC-REL-3` | SBOM как release asset | Planned | trigger — выбран consumer/use case; формат и retention фиксируются до включения |
| `SEC-REL-4` | Build provenance/attestation | Planned | trigger — доверенная CI release build и consumer verification; ориентир — [SLSA](https://slsa.dev/spec/v1.2/) |
| `SEC-OPS-1` | Dedicated non-root daemon и systemd sandbox/resource limits | Enforced для packaged deployment | `NoNewPrivileges`, filesystem/device/kernel/namespace restrictions, `LimitCORE`, memory/CPU/task limits; [packaging README](../packaging/README.md#systemd-hardening) |
| `SEC-OPS-2` | Actuator не публикуется наружу по умолчанию | Enforced defaults | loopback bind, health/info only, без shutdown endpoint; [application.yml](../bootstrap/ioc-app/src/main/resources/application.yml) |
| `SEC-OPS-3` | Least-privilege SMB operation и credential redaction | Manual + Enforced parts | operator guides минимальных прав, host-owned secret config, redacted settings/log context; [remote sync](guides/remote-storage-sync.md), [managed import](guides/dataframe-import.md) |

## 5. Dependency security contract

Dependabot и Dependency-Check решают разные задачи и применяются вместе:

- **Dependabot** использует GitHub dependency graph/advisory data, создаёт
  alerts и предлагает security/version update PR;
- **Dependency-Check** анализирует effective Maven graph через NVD/CPE,
  формирует воспроизводимый HTML/JSON report и применяет project suppression
  contract;
- **Maven Enforcer и dependency tree** обнаруживают build hygiene/conflict
  проблемы, но не заменяют vulnerability database;
- ни один инструмент не доказывает runtime applicability автоматически.

### 5.1. Где запускается проверка

| Контур | Trigger | NVD dependency | Роль |
|---|---|---|---|
| Обычный PR/branch CI | push / pull request | нет | быстрый deterministic feedback: tests, boundaries, docs |
| Dependabot | GitHub service | управляется GitHub | непрерывные alerts и update proposals |
| Scheduled security | weekly | да, key + cache | обнаружить новый advisory/CPE drift |
| Release candidate | manual на exact commit | да, обязательна | blocker перед final version/tag |
| Tag release | `v*` | не должен впервые обращаться к NVD | build/publish уже проверенного candidate |

PR CI намеренно не зависит от live NVD: outage, rate limit или повреждённый
cache не должны останавливать обычную разработку. Недоступность NVD во время
release candidate gate является отсутствием обязательного evidence, а не
доказательством безопасности.

### 5.2. Update policy

Dependabot PR не merge'ится автоматически только потому, что создан ботом.
Перед merge применяются те же правила, что для ручного dependency change:

1. определить direct/transitive path и затронутые modules;
2. прочитать vendor release/security notes;
3. отделить security remediation от несвязанных maintenance upgrades;
4. проверить compatibility и изменения transitive graph;
5. выполнить targeted tests и полный `verify` пропорционально риску;
6. для security change повторить Dependency-Check или подтвердить advisory
   disposition другим воспроизводимым evidence.

Major/minor update не считается безопасным только из-за отсутствия CVE. Patch
update не считается безрисковым только из-за размера номера версии.

## 6. Vulnerability lifecycle

Каждый finding проходит один и тот же процесс:

```text
detect
  → identify dependency path and scope
  → verify affected versions and advisory
  → assess applicability and exposure
  → choose disposition
  → remediate or record exception
  → verify tests/tree/scan
  → retain evidence
  → prevent recurrence where practical
```

### 6.1. Triage

Минимальный набор вопросов:

1. Какой exact artifact/version и dependency path найден?
2. Это runtime, build plugin, test/TCK или unused surface?
3. Совпадает ли finding по PURL/package либо только по эвристическому CPE?
4. Подтверждает ли vendor advisory эту версию и компонент?
5. Содержится ли уязвимый class/API/feature в shipped artifact?
6. Использует ли проект уязвимый путь?
7. Доступен ли путь недоверенному input или внешнему actor?
8. Существует ли patched version и каков migration radius?

### 6.2. Disposition

Допустимые результаты:

| Disposition | Когда применяется | Где фиксируется |
|---|---|---|
| `remediated` | dependency/code/config обновлены, повторная проверка зелёная | commit/PR + scan evidence |
| `false_positive` | scanner ошибочно сопоставил artifact/CPE/CVE | узкая suppression с evidence |
| `not_applicable` | компонент затронут, но уязвимый path отсутствует в supported deployment | triage note/issue; suppression только если finding иначе остаётся |
| `accepted_risk` | vulnerability применима, но временно не исправляется | tracked issue с owner/review condition; при необходимости release notes |

`deferred` без owner, причины и trigger не является disposition.

### 6.3. Severity и release policy

- Critical/High runtime findings являются release blockers, пока не получат
  проверенный disposition.
- Candidate scan падает на `CVSS >= 7.0`; threshold обеспечивает enforcement,
  но не заменяет applicability review.
- Medium findings triage'ятся до релиза и либо исправляются, либо получают
  явный disposition и follow-up.
- Low findings рассматриваются в maintenance cadence, если exposure не повышает
  их фактический риск.
- Активно эксплуатируемая или доступная через недоверенный input vulnerability
  имеет приоритет над формальной очередью score categories.

Фиксированных календарных SLA документ пока не обещает. При появлении команды
или внешнего support contract сроки реакции добавляются вместе с owner/escalation
моделью, а не копируются из чужой политики.

## 7. Suppression и accepted-risk contract

Suppression предназначена только для доказанного scanner mismatch или
зафиксированной неприменимости. Она не используется, чтобы сделать build
зелёным при реально применимой уязвимости.

Каждое правило в `dependency-check-suppressions.xml` обязано содержать:

- точный PURL/hash/artifact selector вместо широкой group regex, когда это
  возможно;
- конкретные CVE/vulnerability identifiers;
- disposition и краткое техническое rationale;
- ссылку на vendor advisory или эквивалентное evidence;
- `until` либо ясный review trigger;
- условие досрочного пересмотра при upgrade, изменении CPE mapping или
  supported usage.

`failBuildOnUnusedSuppressionRule=true` делает stale rule ошибкой security
scan. Глобальные подавления по score, vendor family или всему group без
поартефактного обоснования запрещены.

Если риск применим, он регистрируется как issue/known issue с owner и review
condition. Suppression может технически скрыть повторяющийся scanner finding,
но не должна переименовывать `accepted_risk` в `false_positive`.

## 8. Security gates по жизненному циклу

| Этап | Обязательные действия | Блокирующее условие | Evidence |
|---|---|---|---|
| Design нового boundary | определить assets, trust boundary, abuse/resource cases | неизвестна модель доверия или ownership | design doc/ADR/issue |
| Code/PR | tests, boundaries, config/docs consistency | красный `verify`, нарушенная граница, secret в diff | CI run и review |
| Dependency change | tree, advisory/release notes, compatibility tests | необработанный новый Critical/High или непонятный graph drift | PR + scan/tree summary |
| Weekly security | Dependabot + aggregate Dependency-Check | job failure требует triage, но не ломает обычный PR CI | GitHub alert/run/report |
| Release candidate | fresh exact-commit scan, unused suppression check, full release gate | нет отчёта либо есть untriaged Critical/High | workflow URL + HTML/JSON artifact |
| Publication | tag/version/commit identity, build once, SHA-256 | identity mismatch или изменившиеся bytes | GitHub Release assets |
| Deployment | checksum, host-owned config/secrets, sandbox, health | digest mismatch, invalid config, failed storage health | deployment log/health/digest |
| Incident | contain, preserve evidence, rotate exposed secret, remediate, review recurrence | incident не имеет owner/status | issue/advisory/postmortem |

## 9. Secrets и GitHub Actions

1. Secret хранится в GitHub Environment/repository secret либо в host-owned
   runtime config, но не в POM, YAML defaults, shell history example или Git.
2. Workflow явно передаёт secret только тому step/input, которому он нужен.
3. `NVD_API_KEY` доступен только explicit Dependency-Check update step через
   Environment `SECURITY CHECKS`; offline scan и другие steps job не получают
   его как environment variable.
4. `GITHUB_TOKEN` получает явные минимальные `permissions`. Расширение прав
   задаётся на минимальном job, а не на весь workflow.
5. Значение secret не выводится для диагностики. При подозрении на раскрытие
   secret отзывается/ротируется, а logs и workflow history проверяются.
6. Privileged triggers (`pull_request_target`, chained `workflow_run`) не
   выполняют недоверенный checkout/code без отдельного threat review.
7. Remote Actions рассматриваются как исполняемые dependencies и закрепляются
   полным commit SHA; version comment сохраняет читаемую release identity, а
   Dependabot обновляет обе части. `SEC-CI-3` автоматически отклоняет tag refs:
   GitHub называет full-length SHA единственным immutable способом закрепить
   Action в [Secure use reference](https://docs.github.com/en/actions/reference/security/secure-use).
8. Если внешняя платформа поддерживает OIDC, short-lived credentials
   предпочтительнее долгоживущего cloud secret. Этот механизм активируется
   только вместе с реальной cloud integration.
9. Неавторитетный внешний reporting service не становится единственным
   носителем blocking gate. Его outage не блокирует PR/release, если
   project-owned локально воспроизводимый check прошёл; иная enforcement policy
   требует отдельной registry decision и documented outage behavior.

## 10. Недоверенные документы, runtime и DAST applicability

Основная текущая attack surface — не web UI, а parsing недоверенных документов,
file/SMB boundaries и long-running daemon:

- regex идёт через RE2/J по умолчанию, а patterns остаются RE2-compatible;
- Tika и parser dependencies изолированы в source adapter;
- parser failures превращаются в controlled diagnostics, но общий resource
  budget ещё не реализован — это открытый `SRC-2`;
- daemon ограничен systemd sandbox и cgroup resource controls;
- actuator bind'ится на loopback и не включает shutdown endpoint;
- SMB credentials редактируются, а оператору предписываются минимальные remote
  permissions.

Поэтому generic web DAST сейчас имеет состояние `Not applicable`: публичного
HTTP/authentication surface нет. До появления такого boundary полезнее
negative/fuzz/resource testing для:

- oversized, nested, malformed и decompression-heavy documents;
- parser timeout/cancellation и memory/disk budgets;
- path traversal и hostile remote names;
- corrupted manifests/checksums и interrupted atomic protocols;
- log injection и утечки credential/query data;
- retry storms и resource exhaustion.

DAST пересматривается при появлении внешнего REST API, web UI, authentication,
multi-tenant endpoint или иной удалённо вызываемой business operation. Тогда до
enforcement фиксируются staging target, authentication method, test data,
reset/cleanup contract и допустимый scan impact.

## 11. Evidence, metrics и retention

Security evidence должно позволять восстановить решение без локального cache:

- GitHub alert или workflow run URL;
- exact commit SHA и effective dependency version/path;
- HTML/JSON Dependency-Check report как workflow artifact;
- vendor advisory и applicability rationale;
- targeted/full test results;
- suppression/accepted-risk record;
- release tag, build metadata и SHA-256 для опубликованных bytes.

Heavy generated reports не коммитятся в Git без отдельной причины. Scheduled
workflow хранит reports ограниченное время; release evidence связывается с
candidate run и GitHub Release, чтобы временный worknote не был единственным
источником.

Полезные показатели, когда появится необходимость в trend review:

- число и возраст untriaged Critical/High alerts;
- время от alert до первого disposition;
- число active/expired/unused suppressions;
- backlog Dependabot PR и доля failed security runs;
- повторное появление одного root cause;
- доля release candidates с полным identity/security evidence.

Метрика не должна стимулировать скрытие findings или массовые suppressions.

## 12. Roadmap развития

Roadmap определяет порядок и triggers, но не выдаёт будущий контроль за
реализованный.

### Уровень 0 — удержать текущий baseline

- активировать Dependabot/workflow в default branch;
- выполнить первый manual Dependency Security run;
- проверить report/cache artifacts и weekly cadence;
- учесть хрупкость: для public repository GitHub автоматически отключает
  scheduled workflows после 60 дней без repository activity
  ([GitHub Docs](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows));
  weekly-скан может перестать идти без project-owned сигнала, поэтому candidate
  scan остаётся обязательным release gate;
- продолжать exact-candidate scan перед release;
- поддерживать suppression contract и актуальность этой политики.

### Уровень 1 — защитить изменения dependencies и workflows

- добавить Dependency Review для PR, сначала в observation/dry-run режиме;
- добавить ownership/review для `.github/workflows`, release и suppression
  boundaries;
- включить secret scanning/push protection вместе с понятным response process,
  если функция доступна repository.

### Уровень 2 — release supply chain

- развивать два независимых outcome: проверяемую authenticity
  (signature/provenance + реальный verifier и trust-root у consumer) и composition
  transparency (SBOM/CycloneDX + выбранный consumer/VEX path); наличие одного не
  заменяет второе, а порядок активации определяется реальным consumer contract;
- публиковать SBOM рядом с jar/checksum после выбора формата, consumer и retention;
- добавлять artifact signing только вместе с key/identity lifecycle и проверкой
  подписи при получении/deployment, чтобы подпись не была декоративным asset;
- завершить build-once identity chain из [RELEASE-PROCESS.md](RELEASE-PROCESS.md);
- оценить provenance/attestation по SLSA после появления trusted CI release
  builder и стороны, которая действительно проверяет attestations.

### Уровень 3 — SAST и threat-informed verification

- запустить Java/GitHub Actions SAST в non-blocking режиме;
- получить baseline и разобрать noise до введения blocking policy;
- блокировать только согласованные severity/confidence classes;
- поддерживать [THREAT-MODEL.md](THREAT-MODEL.md) baseline и расширять его при
  новом parser, transport, network API, auth boundary или durable/wire format;
- реализовать `SEC-VER-3`: negative/fuzz/property корпус прежде всего вокруг
  document parsing, paths, manifests и resource budgets.

### Уровень 4 — DAST и operational response

- активировать DAST после появления поддерживаемой внешней network attack
  surface;
- определить incident ownership, containment и communication runbook;
- создать `.github/SECURITY.md` и private reporting path до появления внешней
  пользовательской аудитории;
- добавить runtime alerting/use cases только вместе с supported log consumer.

## 13. Правила изменения security-контура

Для разработчика или автоматизированного агента действуют правила:

1. Перед dependency, workflow, release, parser, transport, secret или network
   boundary change прочитать этот документ и затронутые capability docs.
2. Не ослаблять gate, threshold, redaction или validation только ради зелёного
   build.
3. Не добавлять suppression до проверки dependency path, artifact content,
   vendor advisory и supported usage.
4. Не смешивать security remediation с несвязанным массовым upgrade без
   согласованного scope.
5. Новый действующий контроль обновляет реестр и связанные docs в том же
   change. Если контроль только спроектирован, его state остаётся `Designed` или
   `Planned`.
6. Конкретный выявленный gap получает issue/`KNOWN-ISSUES` entry; общая идея без
   текущей applicability остаётся roadmap item с activation trigger.
7. Труднообратимое решение или новый compatibility promise оформляется ADR.
8. Security report не считается итогом без human applicability disposition.

Политика пересматривается перед каждым release, после существенного изменения
attack surface или security incident. Если релизов и таких изменений долго нет,
maintainer выполняет периодический review не реже одного раза в квартал.

## 14. Нормативные ориентиры

- [NIST SP 800-218 — Secure Software Development Framework](https://csrc.nist.gov/projects/ssdf)
- [OWASP SAMM model](https://owaspsamm.org/model/)
- [OWASP Dependency-Check Maven configuration](https://jeremylong.github.io/DependencyCheck/dependency-check-maven/configuration.html)
- [OWASP Dependency-Check suppression guidance](https://jeremylong.github.io/DependencyCheck/general/suppression.html)
- [GitHub Actions Secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)
- [SLSA specification](https://slsa.dev/spec/v1.2/)

Эти документы являются ориентирами, а не заявлением о полном соответствии.
Project policy, threat model и фактическое evidence остаются определяющими для
конкретного решения.
