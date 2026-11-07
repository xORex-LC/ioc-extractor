---
title: "DOC-02K — Развёртывание, совместимость и восстановление"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation slice; open operational/security findings"
document_type: "Execution evidence"
source_of_truth: false
language: "ru"
---

# DOC-02K — Развёртывание, совместимость и восстановление

Проверка 2026-11-07 (Asia/Irkutsk), HEAD
`37eb6a0714f5e08ecad067403280ce2eff2934f4`, ветка `docs/quality`.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).
Проверены корректность и полнота [Deployment EN](../../../guides/deployment.md)
и [RU](../../../guides/ru/deployment.md) для разработчика и оператора.

Применены code-documenter, maven-dependency-audit, multi-module-maven,
cli-developer, java-code-review и spring-boot-engineer. Формат/ownership уже
согласованы. Linked module README использован как источник, без полного
пересмотра его содержимого. Source fixes, dependency upgrades, установка на host,
deployment и commit в этот документный срез не входят и не выполнялись.

На входе 370 Markdown paths и готовые незакоммиченные H/I/J. Input hashes и
registry rows сохранены. Меняются только два guides, audit, общие README/status/
inventory/source-set и две новые записи KNOWN-ISSUES. Прежние файлы пакетов
сохраняются; весь KNOWN-ISSUES и root security docs заново не аудировались.

## Находки и решения

| ID | Приоритет / пробел | Результат |
|---|---|---|
| `DOC-DPL01` | M: prerequisites не объясняли build/target dependencies, host boundary и native SQLite | Добавлены GNU/Linux/systemd PID 1, JDK/архитектура, tools/CA, local storage, embedded DB/native temp и optional SMB; отделены зависимости build host |
| `DOC-DPL02` | M: первая установка запускала сервис до review; checksum мог восприниматься как доказательство доверенного origin | Пример использует `--no-start`, объясняет его host effects/boot enablement и доверенное происхождение JAR/checksum/matching scripts |
| `DOC-DPL03` | M: не раскрывались sandbox и различия daemon/CLI окружения | Описаны writable paths/private tmp/ProtectHome, heap+cgroup, precedence/list replacement, EnvironmentFile и отсутствие передачи JAVA_OPTS через CLI launcher |
| `DOC-DPL04` | M: health gates можно было считать одинаковыми и достаточными для SMB | Таблица различает три storage-компонента, aggregate gate helper и CLI exit codes; добавлены port override и representative acceptance |
| `DOC-DPL05` | H: пример upgrade автоматически стартовал до template reconciliation, backup покрывал лишь DB | До старта согласовать YAML/release compatibility; пример сохраняет весь prefix/unit во внешнем защищённом каталоге, использует `--no-start`, учитывает irreversible lifecycle/schema/state effects |
| `DOC-DPL06` | M: RU отставал по Java selection, matching unit и ACL/xattr restore; разные archive layouts не объяснялись | RU согласован с EN, unit backup локального deploy отделён от ручного backup installer; DB extraction сохраняет ACL/xattrs без blanket chown |
| `DOC-DPL07` | M: не хватало понятного порядка диагностики и известных incompatibilities | Добавлены read-only observations и таблица ошибок: flags/files, CONFIG/78, runtime/JAR mismatch, ports, permissions/native/OOM, schemas, SMB и OPS-10/11/12 |
| `DOC-DPL08` | H operational finding: failed local preflight оставляет новые host files | Воспроизведён exact source segment со stubbed privileges/systemd; `OPS-13` открыт, последствия для restart/CLI и восстановление отражены в guide и registry; script не исправлен |
| `DOC-DPL09` | H security finding: runtime package/version matches | Проверены 195 nested JAR / 171 внешняя координата; 11 разных advisories затрагивают версии 7 координат. `SRC-3` открыт для reachability/disposition/updates; vulnerability-free или release-security PASS не заявлен |
| `DOC-DPL10` | Проверка: историческая migration и текущая policy должны оставаться различимы | Сохранён переход 0.1.0→0.2.0, отмечены current marked-layout compatibility и bounded v0.2 aliases. Исторические release notes не обновлялись под новые схемы |

## Источники и покрытие сценария

Прочитаны [install](../../../../packaging/install.sh),
[layout](../../../../packaging/install-layout.sh),
[local build](../../../../packaging/deploy-local.sh),
[activation](../../../../packaging/deploy-local-root.sh),
[uninstall](../../../../packaging/uninstall.sh),
[CLI](../../../../packaging/templates/ioc),
[config helper](../../../../packaging/templates/ioc-config),
[unit](../../../../packaging/templates/ioc-extractor.service),
[environment](../../../../packaging/templates/ioc-extractor.env),
[production YAML](../../../../packaging/templates/application.yml), parent/app POM,
schema migration catalogs, CLI health/bootstrap checks и release notes.

| Этап | Проверено в документации |
|---|---|
| Выбор поставки | Trusted released bytes либо собственный build; только bootable app, matching packaging, checksum/identity |
| Подготовка | Root/account, host tools, Java/CPU, offline archive, local filesystem/native temp, optional network/SMB |
| Установка | Prefix/marker/source-tree boundary, immutable release, no-start и фиксированный service name |
| Конфигурация | Candidate/check/apply, EnvironmentFile/overrides, sandbox, service/CLI differences, validation limits |
| Первый запуск | Storage против aggregate health, порты, local против remote acceptance, controlled document |
| Upgrade | Installed/release compatibility, all-state backup, `.new` conflict, schema/lifecycle/identity/alias boundary |
| Rollback/restore | Matching binary/unit/config/DB/state/runtime; archive layouts, ACL/xattrs и необратимые внешние эффекты |
| Неполадки/удаление | Actionable diagnosis, known defects, safe uninstall и destructive purge boundary |

Это coverage выбранного operator guide, а не Javadoc coverage или новая
host/server-family qualification.

## Исполненные проверки

- `make doctor-core`: JDK 21 / wrapper environment пригодны.
- `make lint-shell`: ShellCheck, packaging contracts, rendered unit validation
  и tools contracts PASS; host activation эти проверки не выполняют.
- **50 unit/contract cases, 5 suites:** IocSemanticConfigurationCheckTest,
  IocUnknownConfigurationPreflightTest, CanonicalIntakeStartupCoordinatorTest,
  ConfigurationDocumentationContractTest и DocumentationConventionTest.
  Selected `package` собрал actual boot JAR; это не full `verify`.
- **15 IT, 5 suites:** IocConfigurationContextIT (11), DaemonRuntimeModeIT,
  JdbcLedgerDaemonRuntimeModeIT, DataframeStorageDaemonRuntimeModeIT и
  SyncEnabledDaemonRuntimeModeIT (по 1). Все без failures/errors/skips;
  выбранные XML отбирались по фактически исполненным names, не stale glob.
- **14 Bash blocks:** literal syntax PASS и выполнение через recording facade
  в private cwd. Sudo/systemctl/installer/restore не вызывались на реальном host;
  facade проверяет форму команд, но не квалифицирует installation/restore.
- **13 JVM вызовов actual JAR:** help/version и пять subcommand help; valid
  production configuration (exit 0), unknown key (78), четыре synthetic health
  responses (UP 0, DOWN/UNKNOWN 1, malformed 2). Конфигурационная проверка не
  создала runtime DB. Три real packaging help также прошли.
- **Native SQLite probe:** исходный sqlite-jdbc из boot JAR выполнил запрос в
  отдельной локальной DB. Это не проверка noexec/musl/actual systemd sandbox.
- **OPS-13:** исполнен pre-activation source segment с перенаправленным unit
  path и stubbed ownership/systemd. При simulated exit 78 helper получен exit 1,
  `current` не изменился, stop/DB backup не выполнялись, старый unit сохранён
  sidecar, но активный unit и helpers остались новыми. Механизм — explicit `die`
  обходит ERR trap. Live systemd и credentials не использовались.
- EN/RU: semantic review, 16 headings одинаковых уровней, 15 literal blocks,
  normalized link counts и набор CLI flags совпадают; реальные local anchors,
  inventory hashes, source-set boundaries проверены отдельно. Два local Markdown
  fragments разрешаются; 365 входных Markdown paths сохранены побайтово,
  остальные registry rows/source sets неизменны. В KNOWN-ISSUES сохранён весь
  входной текст, добавлены только две строки. `make docs`: 2971 total /
  716 unique / 2715 OK / 0 errors / 256 excluded; `git diff --check` PASS.

## Аудит packaged dependencies и открытый SRC-3

Локальный boot JAR: `bootstrap/ioc-app/target/ioc-app-0.3.0-SNAPSHOT.jar`, SHA-256
`cc6907fcb669f33b76f56d84016e360abbcb8fd6ca0b8f6c66853b5f1508fe21`.
Он построен на указанном HEAD с незакоммиченными docs; это не опубликованный
release bundle. Manifest запускает Boot loader и приложение; 195 nested JAR
учтены, внутренних production modules 24, внешних координат 171.

Coordinates подтверждены embedded pom.properties либо совпадением SHA-256 с
реальным Maven-cache file/path. Jarmode-tools отдельно совпал с Central bytes.
Duplicate nested names не обнаружены; это не complete dependency-convergence
или binary API compatibility proof. Из наличия Jackson 2 и 3 с разными groupId
не делался вывод о конфликте: приложение действительно использует обе семьи.

Датированный запрос [OSV API](https://google.github.io/osv.dev/api/) для всех
171 внешних package/version pairs дал 11 разных неотозванных advisories
(withdrawn отсутствует); fixed ranges и primary references сохранены локально.

| Packaged component | Версия | Совпадения |
|---|---|---|
| com.fasterxml.jackson.core:jackson-core | 2.21.5 | GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89 |
| tools.jackson.core:jackson-core | 3.1.5 | Те же два core advisories |
| com.fasterxml.jackson.core:jackson-databind | 2.21.5 | GHSA-cxp5-3px4-pw24, GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf, GHSA-wv8q-qhhj-9h54 |
| tools.jackson.core:jackson-databind | 3.1.5 | Те же пять databind advisories |
| com.github.junrar:junrar | 7.6.0 | GHSA-89m4-43j5-vhhx, GHSA-h9h9-2rmf-rvhp |
| com.google.guava:guava | 33.3.1-jre | GHSA-xxph-c9ww-hj94 |
| org.jsoup:jsoup | 1.23.1 | GHSA-65r4-943x-97jj |

Primary details: [Jackson core](https://github.com/advisories/GHSA-7hhh-6rmp-j9qf),
[databind](https://github.com/advisories/GHSA-cxp5-3px4-pw24),
[Junrar](https://github.com/advisories/GHSA-h9h9-2rmf-rvhp),
[Guava](https://github.com/advisories/GHSA-xxph-c9ww-hj94),
[jsoup](https://github.com/advisories/GHSA-65r4-943x-97jj).
Potential outcomes зависят от API/входа: parser/deserialization DoS и отдельные
побочные filesystem effects. Нет proof, что все эти пути достижимы из IOC;
отсутствие прямого вызова в нескольких модулях не доказывает недостижимость
в transitives. Полный reachability/disposition остаётся владельцу security.

Central metadata для семи координат получены read-only. Возможность новой
версии не заменяет совместимый update, тесты и reviewed disposition. POM,
versions, suppressions и NVD cache не менялись; global Dependency-Check gate
не запускался. Нельзя считать bundle безопасным по успешному health/docs run.

## Учёт и свежесть

Census — **371 Markdown path / 64 source sets / 53 missing RU pairs**
(3 root, 49 ADR, 1 guide). Добавлен один audit; оба deployment guides имели RU.
Base census 329 и hashes на `2c76d8b095a2162c7038c64de896b244517cf4c7` сохранены.
Меняется owning source set `deployment`; root KNOWN-ISSUES получает только
OPS-13/SRC-3 entries, а не статус полного review.

Evidence — ignored `.dev/r030-doc-02k/`: input snapshots, unit-package/IT XML/logs,
packaging/doctor, examples/probe fixtures/results, packaged-runtime identity,
OSV request/response/advisory JSON, metadata и parity/inventory/link checks.
Предыдущие H/I/J сохранены. Source/POM/scripts/ADRs/release notes/generated
catalogs не менялись; root security docs остаются отложенными.

Full verify/PMD не запускались: последние PASS относятся к
`191e74146dddeeab36bf59d9d09020a92bef0620`, оба freshness=false.
OPS-13/SRC-3 и прежние OPS-10/11/12, TOOL-10/OUT-4 не закрыты. Новая live
установка, privileged restart/restore, SMB qualification, публикация и commit
не выполнялись. Документный комплект завершён с открытыми findings;
R030-DOC/SEC/REL остаются открытыми.
