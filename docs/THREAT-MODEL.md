# Модель угроз

Базовая threat model проекта `ioc-extractor`. Документ связывает **активы ×
границы доверия × угрозы × контроли** и делает явным **остаточный риск** —
то, что реестр контролей [SECURITY-ENGINEERING.md](SECURITY-ENGINEERING.md)
перечисляет как контроли, но не трассирует к конкретным угрозам.

**Состояние: initial baseline.** Это ручной governance-контроль для того, что
существует сегодня, а не исчерпывающий анализ или доказательство отсутствия
уязвимостей. Он пересматривается при появлении новой границы доверия
(новый parser, transport, network API, auth boundary, durable/wire format) —
см. триггеры в конце и правило 6 из
[SECURITY-ENGINEERING.md](SECURITY-ENGINEERING.md#13-правила-изменения-security-контура).

## Метод

Используется **risk-based STRIDE review по границам доверия**
([OWASP Threat Modeling Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Threat_Modeling_Cheat_Sheet.html))
(Spoofing,
Tampering, Repudiation, Information disclosure, Denial of service, Elevation of
privilege) — по одной таблице на границу из
[раздела 1.1 SECURITY-ENGINEERING](SECURITY-ENGINEERING.md#11-границы-доверия).
Это стрим Threat Assessment в терминах OWASP SAMM. Для каждой угрозы
фиксируется вектор, действующий контроль (по стабильному ID) и остаточный
пробел. Отсутствие контроля не скрывается — оно становится строкой gap.

Обозначения статуса контроля наследуются из
[модели состояний](SECURITY-ENGINEERING.md#3-модель-состояния-контролей):
`Enforced` / `Planned` / `Not applicable` и т.д. Пробел без действующего
контроля помечается **GAP** и должен иметь либо `Planned`-контроль, либо запись
в [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

Модель перечисляет материальные сценарии, а не создаёт строку для каждой
теоретической комбинации STRIDE. Матрица покрытия ниже делает явными категории,
которые были рассмотрены и признаны нематериальными в текущем supported scope.

## Активы

Полный перечень — в [разделе 1 SECURITY-ENGINEERING](SECURITY-ENGINEERING.md#1-область-действия-и-активы).
Ключевые для модели: недоверенные входные документы и извлечённые IOC; canonical
SQLite truth и service-ledger'ы; CSV/export projections и manifests; SMB
credentials и NVD API key; release artifact + checksum identity chain; daemon
process и loopback actuator.

## Контекст и потоки данных

Level-0 DFD показывает security-релевантные процессы, хранилища и внешние
сущности. Обозначения `B1`–`B6` соответствуют границам ниже.

```text
 [untrusted document] ── B1 ──┐
                              │
 [remote SMB source] ── B2 ───┤
                              ▼
                      [IOC runtime process]
                       │       │       │
                       │       │       └──▶ [logs / diagnostics]
                       │       └───────────▶ [SQLite truth + ledgers]
                       └───────────────────▶ [CSV/export + manifest]
                                                    │
                                                    └── B6 ──▶ [SMB/downstream consumer]

 [Maven repositories] ── B3 ──┐
                               ├──▶ [CI build] ──▶ [release jar + checksum]
 [GitHub/workflows] ──── B4 ───┘                         │
                                                         └── B6 ──▶ [operator host]

 [operator config + secrets] ───────── B5 ─────────────▶ [IOC runtime process]
```

### Trust assumptions

- maintainer, оператор и host administrator привилегированы и считаются
  доверенными, но подверженными ошибкам; компрометация их учётных записей или
  самого host не устраняется приложением;
- supported deployment — single-tenant Linux host с dedicated service user и
  host-owned конфигурацией; другие локальные пользователи не должны иметь права
  записи в runtime/config/release directories;
- входные документы, remote names/metadata/content и Maven/Actions code не
  становятся доверенными после пересечения границы;
- CSV — прежде всего machine-consumed reputation-list, но downstream operator
  может открыть его в spreadsheet; защита не должна молча менять машинную
  семантику артефакта;
- отдельные source-файлы не имеют криптографической подписи отправителя;
  целостность release artifact и export slices — разные контракты;
- публичный HTTP/API, authentication boundary и multi-tenant execution не входят
  в supported scope; loopback actuator не считается публичной attack surface.

## Матрица STRIDE-покрытия

`✓` означает, что материальный сценарий раскрыт в таблице границы; `—` —
категория рассмотрена, но отдельного материального сценария в текущем scope не
выявлено. Это не утверждение, что категория невозможна после изменения системы.

| Граница | S | T | R | I | D | E |
|---|---:|---:|---:|---:|---:|---:|
| B1 — документы и IOC | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| B2 — SMB | — | ✓ | — | ✓ | ✓ | ✓ |
| B3 — dependencies/build tooling | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| B4 — GitHub Actions | — | ✓ | — | ✓ | ✓ | ✓ |
| B5 — операторская конфигурация | — | ✓ | — | ✓ | — | ✓ |
| B6 — release/host/output consumer | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

## Граница 1 — недоверенные документы и IOC

Главная attack surface. Файл может быть повреждённым, намеренно сложным или
содержать данные, опасные для parser/regex/logging/output paths. Извлечённые
значения — тоже недоверенные и текут дальше в storage и CSV.

| Угроза | Вектор | Контроль | Остаточный риск |
|---|---|---|---|
| **T** — вредоносный regex-ввод (ReDoS) | подача строк с катастрофическим backtracking | `SEC-INP-1`: RE2/J по умолчанию, линейное время, RE2-совместимые паттерны | JDK-fallback движок теоретически уязвим — активируется только явным `engine: jdk`, оператор берёт риск осознанно |
| **D** — resource exhaustion при парсинге | oversized / глубоко вложенные / decompression-heavy документы, parser bombs | частично: `SEC-OPS-1` systemd cgroup limits как внешний потолок | **GAP:** нет единого parser-level budget (max bytes / ratio / depth / wall-clock). `SEC-INP-2` = `Planned`; долг `SRC-2` |
| **T/D** — corrupted input → неверный durable write | битый документ проходит частично | `SEC-DATA-1`: atomic protocols, prepare→checkpoint→commit, failure-policy; parser failure → controlled diagnostic | нет частичного durable write по контракту; экстремальные ресурсные кейсы см. GAP выше |
| **T/I** — SQL injection из IOC/metadata | недоверенное значение меняет структуру durable query | `SEC-INP-3`: business values bind'ятся через `JdbcClient`/`PreparedStatement`; configured/internal identifiers проходят отдельные allow-list validators + quoting; health PRAGMA ограничены typed enum и literal execute-sites; известные границы закреплены regression-тестами | новые non-constant JDBC entry points видны в raw SpotBugs report и блокируются exact-baseline gate до исправления или явного reviewed disposition |
| **I** — утечка сырого IOC/credential в логи | значение попадает в лог на INFO+ | `SEC-LOG-1`: `SensitiveLogValueSanitizer`, raw IOC только на DEBUG/TRACE, redaction | — |
| **T** — CSV formula injection в downstream spreadsheet | operator расширяет marker-regex так, что совпавший заголовок недоверенного документа после нормализации получает spreadsheet-dangerous prefix и попадает в `source` | `SEC-OUT-1`: для shipped default profile путь **not applicable** — marker matches начинаются с `БИБ`/`Письмо`, остальные колонки ограничены indicator/value/const contract | **Латентный:** универсальной output-neutralization нет; безопасность default profile держится на конфиге маркеров. `OUT-2`; будущий фикс должен учитывать machine-consumer semantics, а не безусловно менять значения |
| **S** — подмена атрибуции source | документ подставляет ложный marker-заголовок | атрибуция детерминирована по ближайшему предшествующему маркеру; источник — сам документ (по дизайну доверяем содержимому в вопросе provenance) | не является security-границей: source — descriptive provenance, не authz-решение |

Spreadsheet behavior зависит от consumer и включает больше случаев, чем четыре
ASCII-символа; поэтому будущий output contract должен опираться на явно выбранный
consumer, а не на универсальную «экранирующую» замену
([OWASP CSV Injection](https://owasp.org/www-community/attacks/CSV_Injection)).

## Граница 2 — удалённое SMB-хранилище

Имена, metadata, содержимое и доступность контролируются внешней системой;
credentials — секрет.

| Угроза | Вектор | Контроль | Остаточный риск |
|---|---|---|---|
| **T** — path traversal через враждебные remote-имена | `../` или абсолютные пути в listing | safe-leaf extraction + null-safe normalized inbox-containment в fetch path; transport остаётся за портом; regression-корпус отклоняет dot, parent и trailing-separator имена до download | fuzz-корпус hostile remote names пока отсутствует (`SEC-VER-3`) |
| **I** — утечка SMB credentials | вывод в лог/диагностику/health | `SEC-OPS-3`: redacted settings/log context, host-owned secret config | — |
| **E** — избыточные права remote-аккаунта | over-privileged SMB user | `SEC-OPS-3`: operator guide минимальных прав (Manual) | Manual-контроль: зависит от дисциплины оператора |
| **D** — недоступность/retry storm | сервер отдаёт ошибки/таймауты | keyed single-flight, bounded retry/backoff, reconcile backstop; `SEC-OPS-1` cgroup | — |
| **T** — подмена fetched-контента | MITM или подменённый файл на шаре | packaging template рекомендует `encrypt: true` для SMB3; downstream повторно применяет input controls границы 1 | transport-настройка остаётся Manual; при `encrypt: false` оператор принимает доверие к сети; per-file signature источника не поддерживается |

## Граница 3 — dependencies и build tooling

Maven artifacts, plugins и Actions — исполняемый сторонний код (build и runtime).

| Угроза | Вектор | Контроль | Остаточный риск |
|---|---|---|---|
| **T** — известная уязвимость в dependency | vulnerable transitive/direct artifact | `SEC-SCA-1..4`: centralized versions, Enforcer, Dependabot, weekly + candidate Dependency-Check | сила зависит от operational activation (`SEC-SCA-2/3` = `Configured`) |
| **S/T** — компрометация Maven-артефакта/плагина | artifact с ожидаемыми coordinates/version содержит иные байты | версии закреплены, transport checksums проверяют целостность загрузки | **GAP:** нет project-owned independently trusted digest/provenance verification. `SEC-SCA-5` = `Planned`; механизм выбирается отдельно |
| **T** — компрометация GitHub Action | mutable tag ref переезжает на вредоносный коммит | `SEC-CI-3`: full-SHA pinning + version comment, tools contract отклоняет tag refs, Dependabot обновляет | — |
| **D/E** — сторонний build code истощает runner или использует его права | dependency/plugin выполняется во время build | pinning версий/SHA, read-only permissions, isolated hosted runner | Maven artifacts/plugins пока не имеют independent trusted digest verification (`SEC-SCA-5`) |
| **I** — false green из-за скрытого suppression | широкое подавление прячет applicable finding | `SEC-SCA-4`: узкие selectors, `failBuildOnUnusedSuppressionRule=true`, suppression contract §7 | — |
| **R** — недоказуемая disposition finding'а | «проверено» без evidence | `SEC-VER-*` + §11: workflow URL, report artifact, applicability rationale | Manual-дисциплина |

## Граница 4 — GitHub Actions

Workflow code, runner, caches, artifacts, `GITHUB_TOKEN` и environment secrets
имеют разные уровни доверия.

| Угроза | Вектор | Контроль | Остаточный риск |
|---|---|---|---|
| **E** — избыточные права токена | workflow-wide write | `SEC-CI-3`: default `contents: read`, write только на release job | — |
| **I** — утечка secret в лог/artifact | echo секрета, широкий scope | `SEC-CI-2`/§9: step-local `NVD_API_KEY`, Environment `SECURITY CHECKS`, secret не выводится | — |
| **T** — untrusted checkout через privileged trigger | `pull_request_target`, chained `workflow_run` | §9 п.6: privileged triggers не выполняют недоверенный код без threat review; release использует `persist-credentials: false` | — |
| **T** — release-integrity: подмена опубликованных байтов | rerun заменяет asset иными байтами | build-once + byte-equality gate в publish-draft; digest promotion | — |
| **D** — тихая смерть scheduled security-скана | для public repository GitHub может отключить scheduled workflow после 60 дней без repository activity | — | **GAP:** оператор не получает project-owned сигнал о пропуске weekly-скана. Roadmap L0; обязательный candidate scan остаётся release backstop |

## Граница 5 — операторская конфигурация

Привилегирована, но может содержать ошибки. `ioc.*` — доверенный, но валидируемый вход.

| Угроза | Вектор | Контроль | Остаточный риск |
|---|---|---|---|
| **T/E** — неизвестное/несогласованное значение продолжает работу | опечатка в `ioc.*`/`IOC_*`, устаревший ключ | `SEC-CFG-1`: strict startup preflight на всех каналах, typed binding, `CONFIG.*` failure analysis | — |
| **I** — секрет в POM/YAML defaults/Git | commit credential | §9 п.1: secret только в Environment/host-owned config; `SEC-LOG-1` redaction | нет автоматического detecting-контроля: `SEC-CI-5` secret scanning = `Planned` |
| **E** — daemon от привилегированного пользователя | запуск от root | `SEC-OPS-1`: dedicated non-root user, `NoNewPrivileges`, systemd sandbox | — |
| **E** — actuator наружу | публичный bind health/info | `SEC-OPS-2`: loopback bind, health/info only, без shutdown endpoint | появление публичного HTTP — изменение attack surface, требует отдельного review (`SEC-VER-2` пересматривается) |

## Граница 6 — release delivery, локальный host и output consumers

Published bytes покидают CI, runtime хранит durable state на host, а CSV/export
покидает приложение. Эти потоки имеют разных consumers и не должны смешивать
checksum, signature, host permissions и spreadsheet safety в один контроль.

| Угроза | Вектор | Контроль | Остаточный риск |
|---|---|---|---|
| **S/T** — подмена release artifact | jar/checksum загружены не из ожидаемого release либо заменены после build | build-once identity chain: tag + commit + embedded build-info + published SHA-256; byte-equality gate | checksum доказывает bytes только при доверенном expected digest; signature/provenance остаются Planned |
| **R** — невозможно доказать происхождение deployment | неизвестно, какой commit/jar запущен | version/build-info, deployment ID, release evidence и checksum verification | operator evidence пока Manual |
| **T/I/E** — локальный пользователь меняет config/DB/artifacts или читает secrets | небезопасные ownership/mode либо запуск от root | dedicated service user, host-owned config, systemd sandbox, least privilege | host/root compromise вне application scope; host hardening и backups остаются обязанностью оператора |
| **D** — заполнение диска runtime/output данными | входы, ledgers, exports или logs растут без внешнего quota | maintenance/retention policies, bounded diagnostics, systemd resource controls | filesystem quota и capacity monitoring не принадлежат приложению; operator control |
| **T/E** — CSV исполняется как формула downstream consumer'ом | machine artifact открыт в spreadsheet после активации свободного text path | default-profile applicability contract `SEC-OUT-1`; CSV structural quoting | универсальная spreadsheet-neutralization отсутствует; `OUT-2` |

## Сводка остаточного риска

`Risk` — качественная оценка текущего остаточного риска как сочетания likelihood
и impact (`Low` / `Medium` / `High`), а не CVSS, календарный SLA или подмена
candidate scan. Она пересматривается вместе с exposure и controls.

| # | Остаточный риск | STRIDE | Risk | Disposition / запись |
|---|---|---|---|---|
| 1 | Parser resource budget не реализован | D | Medium | reduce: `SEC-INP-2` (Planned), `SRC-2` |
| 2 | CSV formula injection латентен по конфигу маркеров | T/E | Low для default profile | not applicable для shipped defaults; seam `SEC-OUT-1`/`OUT-2` |
| 3 | Maven dependencies/plugins не проверяются по independently trusted digest | T/E | Medium | reduce: `SEC-SCA-5` (Planned), механизм выбирается отдельным design spike |
| 4 | Нет систематического negative/fuzz-корпуса вокруг parsing/paths/manifests | T/D | Medium | reduce: `SEC-VER-3` (Planned) |
| 5 | Scheduled security-скан public repository может тихо отключиться | D | Low | candidate gate — backstop; project-owned monitoring Planned |
| 6 | Нет secret scanning / push protection | I | Medium | reduce: `SEC-CI-5` (Planned) |
| 7 | Ряд remote/config/host-контролей — Manual | T/I/E | Low–Medium | operator-owned; `SEC-OPS-3`, guides и release evidence |
| 8 | Release artifact не имеет signature/provenance | S/T/R | Medium | Planned Level 2; SHA-256 + trusted release channel — текущий контроль |

Модель не утверждает отсутствие уязвимостей. Release-blocking status определяется
актуальным candidate scan, фактической exposure и disposition по политике
[SECURITY-ENGINEERING.md](SECURITY-ENGINEERING.md#63-severity-и-release-policy),
а не самим наличием этой таблицы.

## Триггеры пересмотра

Модель обновляется в том же change, что и:

- новый parser/format, transport, network API, auth boundary, durable/wire
  format или output-канал;
- расширение набора `section-markers` или появление free-text выходной колонки
  (пересмотреть `SEC-OUT-1`);
- публикация reactor-модулей или переход на trusted CI release builder
  (активирует `SEC-SCA-5`, provenance);
- изменение supported host model, release channel или downstream output consumer;
- любой security incident, изменивший фактическую exposure.

## Связанные документы

- [SECURITY-ENGINEERING.md](SECURITY-ENGINEERING.md) — политика, реестр контролей, lifecycle;
- [KNOWN-ISSUES.md](KNOWN-ISSUES.md) — tracked-долги (`SRC-2`, `OUT-2`, `EXT-3`);
- [ARCHITECTURE.md](ARCHITECTURE.md) и [BOUNDARIES.md](BOUNDARIES.md) — границы и их enforcement;
- [dev/processing.md](dev/processing.md) — input→output path; [dev/sync.md](dev/sync.md) — transport boundary.
