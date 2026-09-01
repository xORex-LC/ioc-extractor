---
title: "Инженерный релиз 0.3.0"
version: "0.3.0"
status: "Accepted"
document_type: "Release contract"
source_of_truth: false
language: "ru"
---

# Инженерный релиз 0.3.0

## 1. Назначение

Релиз 0.3.0 является engineering/hardening-релизом. Его цель — снизить
структурный, регрессионный, операционный и supply-chain риск существующего IOC
Extractor до начала внедрения новых продуктовых возможностей.

Будущий сервис `feeds-collector` будет независимо собираться, выпускаться и
эксплуатироваться в этом же репозитории. Он будет получать IOC feeds из
публичных источников и передавать их IOC Extractor. Релиз 0.3.0 не реализует
этот сервис, но подготавливает проверенный shared-platform слой и реальный
Maven publication path.

Новые business features IOC Extractor в релиз не входят, кроме явно принятого
scope change `R030-DATA / DATA-TTL-01` для canonical record lifecycle TTL.
Иное наблюдаемое изменение поведения допустимо только как:

- явно одобренное исправление дефекта;
- controlled compatibility retirement;
- отдельно принятое изменение release contract.

## 2. Статус и документы

Комплект имеет статус `Accepted`: цели, обязательные deliverables, execution
model и gates приняты как baseline для выполнения релиза. Их изменение после
этой точки оформляется как scope change по §11.

Worknotes являются локальными русскоязычными рабочими документами. Они не
заменяют опубликованные ADR, architecture maps, capability docs, security
policy и release process.

Навигация:

- [модель выполнения](execution-model.md);
- [матрица состояния](status-matrix.md);
- goal contracts перечислены в §4;
- [baseline evidence](evidence/baseline.md);
- [build-quality evidence](evidence/build-quality-ledger.md);
- [test-quality evidence](evidence/test-quality-ledger.md);
- [code-health review ledger](evidence/review-ledger.md);
- [retirement inventory](evidence/retirement-inventory.md);
- [shared-code inventory](evidence/shared-code-inventory.md).

Авторитетные опубликованные документы:

- [ARCHITECTURE](../../ARCHITECTURE.md);
- [MODULARIZATION](../../MODULARIZATION.md);
- [BOUNDARIES](../../BOUNDARIES.md);
- [CONVENTIONS](../../CONVENTIONS.md);
- [SECURITY-ENGINEERING](../../SECURITY-ENGINEERING.md);
- [THREAT-MODEL](../../THREAT-MODEL.md);
- [RELEASE-PROCESS](../../RELEASE-PROCESS.md).

При конфликте worknote с принятым ADR или опубликованным проектным контрактом
работа останавливается до явного resolution.

## 3. Нормативные термины

- **MUST** — обязательное условие и release blocker.
- **SHOULD** — ожидаемый результат; defer допускается отдельным scope decision.
- **MAY** — допустимое улучшение, не блокирующее релиз.
- **Evidence** — воспроизводимый отчёт, тест, CI run, опубликованный artifact,
  consumer build или другой проверяемый результат.
- **Gate** — автоматическая или зафиксированная ручная проверка продолжения
  phase, merge либо release.
- **Goal contract** — стабильные правила одной цели.
- **Evidence ledger** — изменяемое состояние анализа, findings и выполненных
  work items.
- **Matrix cell** — состояние применения цели к конкретному scope.
- **`R030-*`** — namespace целей release 0.3.0; он не пересекается со stable
  IDs в `KNOWN-ISSUES.md`, security registry и других project ledgers.

## 4. Цели

| ID | Приоритет | Outcome | Contract |
|---|---|---|---|
| `R030-BASE` | MUST | Текущее состояние воспроизводимо и измерено | [Baseline](goals/R030-BASE-baseline.md) |
| `R030-DATA` | MUST | Canonical records имеют проверяемый expiration lifecycle, а managed dataframe deliveries безопасно попадают в SQLite truth без частичной activation | [Canonical record lifecycle](data-ttl-01/release-contract.md), [managed dataframe import](dataframe-import/release-contract.md) |
| `R030-QUAL` | MUST | Code-health review конечен и проверяем | [Code health](goals/R030-QUAL-code-health.md) |
| `R030-RETIRE` | MUST | Dead/unwired code и ненужная compatibility контролируемо устранены | [Retirement](goals/R030-RETIRE-retirement.md) |
| `R030-ARCH` | MUST | Maven-границы подтверждены, внутри модулей понятная package organization | [Architecture](goals/R030-ARCH-architecture.md) |
| `R030-TEST` | MUST | Критичное поведение защищено эффективными тестами | [Test quality](goals/R030-TEST-test-quality.md) |
| `R030-BUILD` | MUST | Quality checks дают стабильный actionable signal | [Build quality](goals/R030-BUILD-build-quality.md) |
| `R030-LIB` | MUST | Shared code имеет owner, минимум одна библиотека реально опубликована | [Shared libraries](goals/R030-LIB-shared-libraries.md) |
| `R030-SEC` | MUST | Repository, CI и publication path hardened | [Security](goals/R030-SEC-security.md) |
| `R030-DOC` | MUST | Публикуемая документация English-first и единообразна | [Documentation](goals/R030-DOC-documentation.md) |
| `R030-REL` | MUST | Релиз воспроизводим и не содержит необъяснённых изменений | [Release readiness](goals/R030-REL-release-readiness.md) |

## 5. Контракт отсутствия регрессий

Если задача не является одобренным bug fix или retirement по `R030-RETIRE`,
поддерживаемое внешнее поведение MUST оставаться неизменным.

Контракт распространяется как минимум на:

- CLI commands, options, automation output и exit codes;
- `ioc.*` configuration schema, defaults, precedence и strict preflight;
- SQLite migrations, durable state, upgrade и rollback;
- canonical row identity, public IDs и deduplication semantics;
- CSV projections, export slices и manifests;
- diagnostic codes, completion status и ECS logging shape/types;
- daemon lifecycle, health и maintenance behavior;
- remote sync contracts;
- installer, deployment и release artifact interfaces.

Сохранение подтверждается regression, contract, golden, integration или smoke
tests. Необъяснённое изменение считается дефектом change set.

## 6. Что не входит в релиз

По умолчанию 0.3.0 не включает (исключение DATA-TTL-01 зарегистрировано в
§11):

- новые пользовательские возможности и новые IOC integrations;
- реализацию или deployment `feeds-collector`;
- RabbitMQ runtime внутри IOC Extractor;
- крупные изменения business workflows;
- переход на другой язык или build system;
- спекулятивные abstractions;
- полную перепись работающей подсистемы;
- удаление поддерживаемой compatibility без retirement evidence;
- JPMS только ради получения JAR;
- catch-all `commons`/`shared`/`utils`;
- разделение на микросервисы до стабилизации модульных границ;
- глубокий SAST/SecOps-контур, включая GitHub CodeQL;
- Spotless/Checkstyle, japicmp/Revapi, Error Prone/NullAway и постоянная
  SonarQube/Qodana platform.

Найденный дефект или долг не становится автоматически scope 0.3.0. Он получает
evidence и disposition. Исправление обязательно, если finding вызван текущим
change set, мешает безопасно его завершить, создаёт непосредственный
data/security/release risk либо принят отдельным scope decision.

## 7. Зависимости целей

```text
R030-BASE
   │
   ├────────────▶ R030-BUILD / R030-SEC
   │
   ├────────────▶ R030-DATA ────────────────┐
   │                                        │
   ▼
Module hardening waves
R030-QUAL + R030-RETIRE + R030-ARCH + R030-TEST + affected R030-DOC
   │
   ├────────────▶ hardened publication candidates ─▶ R030-LIB
   │
   ▼
Repository-wide documentation and compatibility closure
   │◀───────────────────────────────────────┘
   ▼
R030-REL
```

Цель закрывается только после выполнения всех применимых matrix cells и её
global gate. Порядок работ определён в
[execution-model.md](execution-model.md).

## 8. Общий release gate

Перед release candidate MUST быть выполнены:

- clean reactor verification;
- non-regression и support/retirement matrices;
- JaCoCo aggregate `75%` line / `80%` branch gate;
- JaCoCo `85%` line / `90%` branch gates для `core/ioc-domain` и
  `core/ioc-application`;
- per-module coverage ratchets и сигнальные Codecov project/patch reports;
- SpotBugs blocking no-new-findings check внутри Maven `verify`;
- PMD CPD repository-wide report и semantic dispositions существенных
  duplicate findings;
- PIT report-only pilot для `core/ioc-domain` с survived-mutant triage,
  runtime evidence и adoption disposition;
- Maven dependency-analysis evaluation с adoption disposition;
- ratchets принятых code-quality checks и существующие dependency/security
  gates;
- published-library standalone consumer test;
- documentation inventory и link checks;
- recorded representative performance/resource comparison с обязательной
  disposition;
- packaging, upgrade и rollback verification;
- curated release notes с compatibility disposition.

Пропущенная environment-dependent проверка получает явный disposition и не
считается пройденной.

## 9. Общий Definition of Done

Релиз завершён, когда:

- все одиннадцать MUST-goals имеют статус `verified`;
- все применимые cells в [status-matrix.md](status-matrix.md) закрыты;
- `./mvnw clean verify` проходит из clean checkout;
- поддерживаемые contracts не изменены без объяснения;
- intentional bug fixes и retirements документированы и проверены;
- минимум одна reusable library опубликована protected CI и разрешается
  standalone consumer;
- release artifacts имеют подтверждённую identity;
- документация, security evidence и release notes завершены.

## 10. Критерии успеха

После релиза:

- разработчики находят код по capability внутри нужного Maven-модуля;
- Maven-границы не пересобраны без доказанной необходимости;
- critical rules/invariants имеют authoritative source;
- dead/unwired code не остаётся без owner и rationale;
- obsolete compatibility удалена контролируемо, history сохранена;
- test gaps и quality regressions измеримы;
- shared code принадлежит именованным cohesive capabilities;
- `feeds-collector` сможет использовать published platform libraries без
  зависимости от IOC core;
- local control events, integration contracts и broker adapters имеют разные
  ownership boundaries;
- CI и release process дают воспроизводимое evidence.

## 11. Управление изменениями

Изменение MUST-goal, non-regression/retirement contract, architecture rules,
shared-library criteria, coverage policy, CI requirements или release DoD
проходит review как scope change.

Scope change фиксирует:

- что и почему изменено;
- затронутые goal IDs;
- влияние на compatibility, evidence и release date;
- новый disposition.

Defer MUST-пункта нельзя скрывать обычным backlog move.

### Принятые scope changes

| ID | Изменение и причина | Затронутые goals | Compatibility/evidence impact | Disposition |
|---|---|---|---|---|
| `DATA-TTL-01` | Срочный canonical record expiration lifecycle включён как новая business capability | новый MUST `R030-DATA`; gates `R030-TEST`, `R030-DOC`, `R030-REL` | SQLite/read/ID/projection semantics; explicit upgrade activation and rollback; crash/race/100k evidence; release critical path расширен | Принято 2026-08-15, implementation go-ahead получен; P1–P9, packaged qualification и final committed-HEAD gate завершены 2026-09-01. Подробности изолированы в [TTL bundle](data-ttl-01/README.md) |
| `DATA-IMPORT-H5-DEFER` | Недоступный live two-identity SMB hardening fixture вынесен из repository/package closure в явную target-deployment qualification | `R030-DATA`, `R030-REL`; debt `OPS-8` | H5 не считается pass; support claim не распространяется на непроверенные SMB families, production template сохраняет import disabled, а operator обязан доказать producer denial/service capability до включения source | Принято 2026-09-01 при закрытии R030-DATA; executable opt-in contract и exit condition сохранены в DATA-IMPORT-01 P9/H5 evidence и status matrix |
