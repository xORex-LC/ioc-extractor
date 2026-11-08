---
title: "Матрица состояния релиза 0.3.0"
version: "0.3.0"
status: "Active"
source_of_truth: false
language: "ru"
---

# Матрица состояния релиза 0.3.0

Состояние на консолидацию 2026-11-08; это roadmap, не отчёт fresh gate.
Правила переходов: [execution-model](execution-model.md).
Исторические outcomes и точные identities:
[qualification record](../../../.github/release-evidence/ru/0.3.0.md) и
[компонентные records](../../../.github/release-notes/README.md).

## Глобальные цели

| Goal | State | Подтверждённое / следующий gate |
|---|---|---|
| R030-BASE | verified | Исходные compatibility/runtime сравнения сохранены; менять comparison contract только явно |
| R030-DATA | verified | TTL/import/aggregate repository/package и отдельная live activation квалифицированы; H5 breadth явно deferred в OPS-8, TOOL-10 остаётся открытым |
| R030-BUILD | verified | Tool adoption и ratchets приняты; required statuses активированы на main/release-0.3.0 и проверены в run 34679847573; fresh candidate gate всё равно обязателен |
| R030-TEST global controls | verified | Lifecycle, coverage, regex, waits, external shells, pilots, Codecov и standalone consumers внедрены; module cells отдельны |
| R030-LIB | verified | Шесть 0.3.0-rc.1 библиотек опубликованы и имеют отдельные records/consumers; TOOL-8 recovery, TOOL-6 API comparison и TOOL-9 versions остаются открытыми; ETL внутренний |
| R030-QUAL | planned | Закрыть module review и актуальный intake в goal; зелёный analyzer не заменяет review |
| R030-RETIRE | planned | Сверить поддерживаемые/history surfaces и выполнить RETIRE-APP-11; DEP-9/10 уже устранены |
| R030-ARCH | planned | Подтвердить module/package maps и boundaries; QUAL-BOOT-1 остаётся review scope |
| R030-SEC | in-progress | Старый dependency repair квалифицирован отдельно; SRC-3 и repository/workflow/publication-security review ещё открыты |
| R030-DOC | in-progress | Основные root/capability/guide комплекты пересмотрены; оставшаяся очередь и scope ниже |
| R030-REL | planned | Все обязательные prerequisites, fresh security, compatibility/performance, packaging/upgrade/rollback и final release evidence |

## Module hardening matrix

Сохранены состояния исходной матрицы; `planned` означает необходимость сверки
owning DoD/evidence, а не доказанный дефект реализации. Старые `in-progress`
также нужно сверить с фактическим компонентным scope. Публикация, глобальный
TEST и обновление документа не закрывают прочие cells автоматически.

| Maven module | `R030-QUAL` | `R030-RETIRE` | `R030-ARCH` | `R030-TEST` | affected `R030-DOC` |
|---|---|---|---|---|---|
| `platform/platform-errors` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-diagnostics-api` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-diagnostics` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-etl` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-events` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-concurrency` | `in-progress` | `planned` | `in-progress` | `in-progress` | `in-progress` |
| `platform/platform-logging-slf4j` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-observability` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-diagnostics-logging` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-domain` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-processing` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-application` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-application-tck` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-config-spring-boot` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-events-spring` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-regex-re2j` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-psl` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-processing-camel` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-source-tika` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-csv` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-manifest-json-jackson` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-store-jdbc` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-transport-smb` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-ingest` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-cli-picocli` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `bootstrap/ioc-app` | `planned` | `planned` | `planned` | `planned` | `planned` |

## Оставшаяся очередь

| Scope / item | Owner / goal | Следующий проверяемый результат |
|---|---|---|
| QUAL CPD/PMD intake, QUAL-BOOT-1 | QUAL/ARCH/TEST | Live characterization и disposition по компактным таблицам [QUAL](goals/R030-QUAL-code-health.md); метрики разрешают review, не механическое извлечение |
| RETIRE-APP-11 StableArtifactId | RETIRE / application artifact | Подтвердить отсутствие Java/resource/reflection/API use, отдельное удаление declaration и obsolete reference с gates |
| Supported/history retirement surfaces | RETIRE / owning modules | Сохранить migrations/ledger replay, engine fallback, SMB, maintenance, publish/TCK до принятого retirement; [контракт](goals/R030-RETIRE-retirement.md) |
| Security: SRC-3 | SEC / dependency owners | Датированная affected-version матрица сохранена; reachability/owner disposition либо qualified remediation и fresh security evidence ещё нужны |
| SEC-LOG-1 | SEC / observability | При отложенном аудите SECURITY-ENGINEERING сверить broad redaction statement с formatter-only structured-context contract; free message/Throwable/cause/suppressed требуют producer ownership; это не доказательство новой утечки |
| DOC remaining paths | DOC / owning documents | [198 исходных задач](documentation/remaining-work.tsv): 49 ADR translations, 126 README reviews, 2 отложенных security docs, 15 release records, 6 compatibility redirects; локальный частичный review не закрывает полный scope |
| Publication follow-ups | LIB/SEC/REL | TOOL-6 API comparison, TOOL-7 provenance, TOOL-8 protected configuration CI recovery, TOOL-9 independent-version Maven/CI transition; не повторная публикация исходных RC |
| External target qualification | DATA/REL / operators | OPS-8 H5 две identities; неподтверждённые server/platform claims остаются skips; source default disabled |
| Operational/source findings | Owning capabilities / REL | OPS-10…14, OUT-4, ING-11/13/15/16, SRC-3 и TOOL-10 сохраняются в [KNOWN-ISSUES](../../KNOWN-ISSUES.md); retirement записок не закрывает их |
| Internal ETL reuse | QUAL/ARCH / ETL | Перед внешним reuse/publication сверить direct construction, compacted-policy input, post-effect observer failures и resource budget; текущие ограничения закреплены в README/Javadoc, ARCH-5 |
| Final release comparison | REL | Повторить representative workloads с сопоставимым boundary; latency/RSS regression и unavailable checks получают явный disposition |

QUAL-REGEX-1 закрыт TEST-REGEX-03, QUAL-CSV-1 — POM metadata correction.
RETIRE-DEP-9 (application→platform-errors) и RETIRE-DEP-10 (CSV→commons-io)
уже устранены. Они не остаются кандидатами на повторное исправление.

## Принятые defer

| Scope | Причина / ограничение | Exit condition |
|---|---|---|
| DATA-IMPORT-01 / ADR 0025 H5 / OPS-8 | Нет двухидентичного live fixture; это не pass и не расширение SMB support claim | Opt-in SmbManagedImportHardeningContractTest на конкретном family: denial, capability/namespace, claim, reconnect, disposition, exact purge |
| Multi-daemon ingestion lease/fencing | 0.3.0 поддерживает single-daemon; process-local ING-10 guard не cross-process ownership | Реальный multi-daemon deployment requirement и отдельное durable fencing решение |
| Security documents | Пользователь отложил SECURITY-ENGINEERING/THREAT-MODEL в отдельный комплект | Отдельный substantive audit/перевод; SEC и release closure до него не объявлять |

## Обновление

После item обновлять только затронутую cell/очередь, указывать постоянный outcome
и точную verification identity. Completed audit transcripts, полный census и
проектные журналы retired; они доступны в immutable snapshot из qualification
record, но не являются новым active backlog. Шесть опубликованных RC не означают
закрытие всех модулей или готовность приложения к выпуску.
