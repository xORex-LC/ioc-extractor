---
title: "R030-SEC — Repository and CI security"
version: "0.3.0"
goal_id: "R030-SEC"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-SEC — Безопасность repository, CI и публикации

## Результат и состояние

`in-progress`: dependency remediation имеет историческую квалификацию, но общий
repository/workflow/publication gap analysis и release dispositions не завершены.
[Release evidence](../../../../.github/release-evidence/0.3.0.md#dependency-and-publication-evidence)
сохраняет исходные проверки; текущие obligations определяются
[SECURITY-ENGINEERING](../../../SECURITY-ENGINEERING.md),
[THREAT-MODEL](../../../THREAT-MODEL.md) и
[KNOWN-ISSUES](../../../KNOWN-ISSUES.md#src-3).

## Подтверждённый repository-control срез — 2026-11-20

Принята модель одного maintainer: PR и обязательный CI без второго reviewer;
публикацию maintainer подтверждает отдельно. Через API применены и перечитаны
[branch ruleset 27737840](https://github.com/xORex-LC/ioc-extractor/rules/27737840)
для `main`/всех `release-*`, tag ruleset `19423734` для application и шести
component prefixes, а также repository-wide full-SHA admission Actions.
Новые release-ветки разрешено создавать до первых checks; дальнейшие изменения
требуют PR. Старые classic protections и дополнительные checks 0.3.0 сохранены.
`SECURITY CHECKS` допускает `main`/`release-*` без ручного approval;
`LIBRARY PUBLISHING` по-прежнему требует подтверждения владельца.

Редактируемые requests находятся в
[repository controls](../../../../.github/repository-controls/README.md).
API read-back проверил effective rules для существующих и будущей release-ветки,
исключение рабочей ветки и сохранность настроек publication/секретов. Тестовых
push/delete и реальной публикации не выполнялось. `SEC-CI-5` уточнён по текущим
settings: secret scanning и push protection включены, maintainer обрабатывает
alerts; это не полный исторический secrets audit.

В рабочем дереве подготовлен scan через день для `main` и активной
`release-0.3.0`, с раздельными source identities, reports и cache keys.
Workflow ещё должен попасть в default branch и получить первый remote run;
эта cadence/матрица пока не operational pass. Приведён к общей application/library
форме version admission: ASCII SemVer core без ведущих нулей, `rc.N` от 1,
SNAPSHOT только для разработки. Исходные RC, versions и bytes не менялись.
231 offline publication tests, shell contracts, calendar/ref selection и local
docs checks прошли. Fresh verify/PMD, vulnerability scan, stand и protected
publication qualification этим не заявляются; SRC-3, SEC-LOG-1, TOOL-8 и прочие
оставшиеся obligations сохраняются.

## Оставшаяся работа

- После интеграции workflow подтвердить новый automatic multi-target run и
  перечитать фактические branch/status/tag/environment protections для final
  candidate. Наличие versioned requests не заменяет remote read-back;
  required checks должны соответствовать принятым blocking gates. Отдельный
  CODEOWNERS/reviewer gate в принятой single-maintainer модели не требуется.
- Проверить pinned Actions, permissions/triggers, untrusted-PR isolation,
  credentials, retention, immutable inputs и contracts внешних CI/reporting
  services: доступ, отправляемые данные, outage и enforcement.
- Завершить dependency/source-security review: review/update automation,
  воспроизводимый offline scan, secret scanning, justified suppressions с
  owner/expiry и residual-risk dispositions.
- Для `SRC-3` учесть [разбор остатка](../../../../.github/release-evidence/ru/0.3.0.md#src-3-residual-review).
  Повтор текущего SNAPSHOT с исходными правилами прошёл. Правило JBIG2
  срабатывает, а правило для Camel-адаптера привязано к SNAPSHOT и блокирует
  RC при смене версии. Решения по находкам Camel, Processing Foundation и
  нативному SQLite записаны в отчёте. До закрытия нужны сверка правила на
  выбранной версии и проходящий security run релизного коммита.
- Пересмотреть statement `SEC-LOG-1`: сверить обещанную redaction с фактическими
  owned reports, log catalog и границами raw IOC/URL/credential handling;
  обновить registry/threat claims и tests при расхождении.
- Проверить publication trust/credential boundary, protected workflow,
  no-overwrite и совпадение verified/published bytes; сохранить disposition
  checksums, provenance/attestation и внешней repository qualification.

## Критерии закрытия

Каждый применимый control имеет подтверждённое состояние и evidence; изменения
в тот же change отражены под прежним stable ID или новым ID в security registry.
Required protections и credential boundary проверены, critical findings закрыты,
остаточные риски/suppressions имеют owner, rationale и срок/условие пересмотра.
Недоступная внешняя проверка имеет явное release disposition и не отмечается pass.

## Граница и зависимости

Глубокое внедрение SAST/CodeQL отложено: `SEC-VER-1` остаётся Planned; generic
SpotBugs не закрывает security control. Не вводятся случайные scanners, broad
permissions или online scan как единственный gate. Локальная release publication
и credentials в repository/examples не допускаются.

По решению пользователя 2026-11-16 полный аудит, переработка и перевод
`SECURITY-ENGINEERING.md`/`THREAT-MODEL.md` исключены из baseline 0.3.0 (N/A):
SDLC и модель угроз будут прорабатываться отдельно в одной из следующих версий.
Полнота этих двух документов не является blocker DOC/release closure.
Применимые controls и отдельные `SRC-3/SEC-LOG-1` dispositions этим исключением
не закрываются; существующие документы остаются reference для уже принятых
правил, без требования завершить их новую модель в 0.3.0.
Требуются `R030-BASE/BUILD`; goal участвует в publication admission и блокирует
финальный go/no-go `R030-REL` до закрытия обязательств либо принятого scope change.
