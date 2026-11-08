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

## Оставшаяся работа

- Сверить фактические branch/review/status/tag/environment protections и
  CODEOWNERS; repository text не доказывает внешние settings. Required checks
  должны соответствовать принятым project-owned blocking gates.
- Проверить pinned Actions, permissions/triggers, untrusted-PR isolation,
  credentials, retention, immutable inputs и contracts внешних CI/reporting
  services: доступ, отправляемые данные, outage и enforcement.
- Завершить dependency/source-security review: review/update automation,
  воспроизводимый offline scan, secret scanning, justified suppressions с
  owner/expiry и residual-risk dispositions.
- Разобрать `SRC-3`: актуальная advisory applicability, достижимые input paths,
  remediations/accepted risks и согласованное release решение. Исторический
  dependency scan не закрывает новые либо найденные позднее runtime advisories.
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

Перевод и полный отдельный аудит двух security-документов отложены в `R030-DOC`;
это не откладывает обязательный review применимых controls и `SRC-3/SEC-LOG-1`.
Требуются `R030-BASE/BUILD`; goal участвует в publication admission и блокирует
финальный go/no-go `R030-REL` до закрытия обязательств либо принятого scope change.
