---
title: "DOC-01B.1 — Архитектурный комплект"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified for DOC-01B.1"
document_type: "Audit and verification evidence"
source_of_truth: false
language: "ru"
---

# DOC-01B.1 — Аудит, полнота и перевод архитектурного комплекта

Дата: 2026-11-02, Asia/Irkutsk. Исходный HEAD:
`5edd3af3a955c7cdd087e8b5917176c58285ae9b`, `docs/quality`, чистое дерево.
Контракт — [R030-DOC](../goals/R030-DOC-documentation.md).
Пользователь согласовал этот пакет и дополнение существенных пробелов с
сохранением назначения каждого документа. Новые архитектурные решения не
принимаются; карта актуализирует существующие contracts и ADR.

## Границы документов

| Документ | Вопрос | Достаточная полнота |
|---|---|---|
| `PRINCIPLES.md` | Как выбирать ответственность и оценивать решение? | Ports/substitutability, декларативная политика, reuse ownership, durable truth/identity, bounded work, failure visibility и evidence |
| `ARCHITECTURE.md` | Как устроена текущая система и где её гарантии? | Слои, два хранилища, run modes, document/import flows, transaction scope, identity/lifecycle, export/delivery, startup и ограничения |
| `MODULARIZATION.md` | Где живёт ответственность и как устроен Maven-граф? | Все runtime/test/report projects, integration families, направления зависимостей, composition и publication boundaries, сопровождение графа |

API/class inventories принадлежат module README/Javadoc; точные columns, config,
defaults и recovery protocols — capability docs и operator guides. Текущая
карта не переносит их полное содержимое в root. ADR остаются историей решений.

## Находки и полнота

| ID | Приоритет | Что требовало исправления/дополнения | Результат |
|---|---|---|---|
| `DOC-A01` | M | Архитектурная схема и таблица слоёв пропускали `ioc-processing`, хотя он находится между application и domain | Граница и её ответственность добавлены; схема объясняет направление зависимости, а не полный Maven-граф |
| `DOC-A02` | M | Перечень портов смешивал чистое row mapping с более широким preparation flow, использующим private I/O | Разделены pure mapping, owned workspace/seal и business commit после failure-policy checkpoint |
| `DOC-A03` | M | Canonical IDs описывались как общая public ID scheme; null-time пример мог восприниматься как общий контракт импорта | Canonical row/lifecycle identity отделена от reusable export slots; public time fields явно не владеют lifecycle |
| `DOC-A04` | M | Export описывал global single-flight и insert-only revisions | Описаны per-profile formation/recovery и revision-significant public-field updates; equal-byte slice не равен тому же observation |
| `DOC-A05` | M | Не было компактной общей карты двух БД и различия atomicity документов/import | Добавлены state ownership и локальные transaction boundaries: document по артефакту, managed import cross-artifact; receipt-based recovery без distributed transaction |
| `DOC-A06` | M | Runtime modes, ordered daemon promotion и recovery-before-intake были распределены по частным docs без общей картины | Добавлен ограниченный overview, включая strict configuration, явную Spring composition, startup barrier и ссылки на mechanics |
| `DOC-A07` | M | Общая карта не обозначала SQLite writer/WAL и workspace-budget limitations; текст утверждал внутренний `String`, несмотря на streamed text | Добавлены bounded preparation, Native backend, single-daemon boundary, writer non-preemption, snapshot cost и измеряемые capacity limits |
| `DOC-A08` | L | Принципы могли читаться как обязательное вынесение любого похожего кода или интерфейс для каждого collaborator | Уточнены cohesive ownership, substitutability, reuse existing API и развитие admitted library по ADR 0048 |
| `DOC-A09` | L | Module map дублировал tree/table/API/history и представлял все platform-модули как одинаково generic | Одна карта всех модулей, service-specific platform metadata отделены от шести published libraries; TCK и четыре report POM имеют явную роль |
| `DOC-A10` | L | Не хватало общей связки untrusted input, secrets, observability и correctness authorities | Добавлены принципы disclosure и краткий trust-boundary overview со ссылками на security/threat owners; новые security guarantees не заявлены |

## Привязка к живым источникам

Пути здесь относительны repository root. Полные списки находятся в
[source-sets.tsv](source-sets.tsv); команды в нём являются планами, а не pass evidence.

| Проверяемая часть | Основные источники | Исполняемые свидетельства / ограничение |
|---|---|---|
| Module graph / dependency families | Root и 30 child POM; parent dependencyManagement; boot plugin в `bootstrap/ioc-app/pom.xml` | Maven compile-scope dependency tree; ArchitectureTest/DomainBoundaryTest; дерево не доказывает source/binary API compatibility |
| Preparation/checkpoint/commit | `IocExtractionService`, `WriteArtifactsStage`, `JdbcCanonicalImportWriter`, processing/workspace ports | Existing processing/storage contracts; code review подтвердил per-artifact и cross-artifact scope; полный функциональный regression не является новым scope этого документационного пакета |
| Identity / public changes | Lifecycle writer/mutation sessions, import writer, `ExportRunLedger`, ADR 0020–0024/0030/0035 | Exact source review и capability contracts; lifecycle metadata не заменяется public time columns |
| Export concurrency | `JdbcExportRunLedger`, export ports, ADR 0037 | `JdbcExportRunLedgerContractIT`; профильная уникальность и state transitions проверяются независимо от текста карты |
| Spring/runtime startup | `IocExtractorApplication`, `CanonicalIntakeStartupCoordinator`, config preflight, production YAML, EventCoordinationConfig | `CanonicalIntakeStartupCoordinatorTest`; general Spring skills не переносят `@Service`/`@Transactional` внутрь framework-free core |
| Events / reusable contracts | Events core/Spring adapter POM, publish contracts, logging/diagnostics/configuration modules, ADR 0044–0048 | Архитектурные правила и current source; внешняя публикация повторно не выполняется |
| Constraints / support claims | ADR 0036–0039/0042, Native capacity, SECURITY-ENGINEERING, THREAT-MODEL и KNOWN-ISSUES | Именованные resource/live evidence сохраняют прежние условия; parser corpus coverage не считается одинаковой ресурсной приёмкой всех форматов |

## Изменения и переводы

- Три canonical root документа переписаны на английском с сохранением ролей.
- Добавлены `docs/ru/{PRINCIPLES,ARCHITECTURE,MODULARIZATION}.md`.
- EN/RU сверяются по утверждениям, секциям, таблицам, identifiers, ссылкам и
  схеме зависимостей. Ссылки RU могут вести на существующую RU-версию guide;
  при сравнении targets нормализуется языковой каталог.
- Краткая таблица четырёх вариантов mask classification сохранена и сверена
  с `ioc.classify.rules`, включая приоритет query/path/subdomain/default.
  Полные CSV columns и class inventories делегированы владельцам.
- Два старых fragment IDs архитектуры сохранены как anchors, включая цель
  ссылки `SRC-1` и локальную ссылку из agent guidance. Прочие входящие ссылки
  проверены; ADR не перемещены и не изменены.
- README навигация, inventory и status matrix обновлены в том же пакете.
  ROOT README не считается автоматически прошедшим полный новый audit.

После пакета: 336 Markdown paths, из них 329 исходных census и 7 добавленных
в ходе DOC; 63 source sets. Обязательных отсутствующих RU-пар остаётся 74:
6 root docs, 16 capability docs, 49 ADR и 3 guides. Наличие пары и hashes
отделено от semantic review: завершённость этого пакета не закрывает прочие docs.

## Проверки

| Команда/проверка | Результат |
|---|---|
| `make context` | Чистый исходный HEAD; verify/PMD на `191e7414`, freshness=false |
| `./mvnw -B -ntp -DskipTests dependency:tree -Dverbose -Dscope=compile` | Пройдено, 31 проект; explicit conflict markers в compile tree не обнаружены |
| `make test-one MODULE=bootstrap/ioc-app TEST=ArchitectureTest` | Пройдено: 34 tests, 0 failures/errors/skips |
| `make test-one MODULE=core/ioc-domain TEST=DomainBoundaryTest` | Пройдено: 7 tests, 0 failures/errors/skips |
| `make test-one MODULE=bootstrap/ioc-app TEST=DocumentationConventionTest` | Пройдено: 1 test, 0 failures/errors/skips |
| `make test-one MODULE=bootstrap/ioc-app TEST=CanonicalIntakeStartupCoordinatorTest` | Пройдено: 5 tests, 0 failures/errors/skips |
| `make test-one MODULE=adapters/adapter-store-jdbc TEST=JdbcExportRunLedgerContractIT` | Пройдено: 13 tests, 0 failures/errors/skips |
| `make test-one MODULE=adapters/adapter-store-jdbc TEST=JdbcExportRunLedgerIT#differentProfilesCanFormConcurrentlyWithoutSharingProgress` | Пройдено: 1 test, 0 failures/errors/skips; отдельное доказательство per-profile concurrency без пересечения progress |
| EN/RU semantic/structure/target parity и module graph coverage | Пройдено: 6 страниц, одинаковые identifiers, нормализованные links, headings/table structure; 30 child modules и 6 published markers совпадают с POM; Boot plugin только в app |
| Mermaid/text diagrams | Синтаксис используемого flowchart subset и 8 dependency edges совпадают EN/RU; text flow одинаков. Отдельный Mermaid renderer недоступен, визуальный rendering не заявлен |
| Таблица классификации | Пройдено: 4 варианта, codes и порядок совпадают с production `ioc.classify.rules` |
| Inventory paths/hashes/source sets | Пройдено: 336 уникальных paths, 63 source sets; все SHA-256 и источники сверены; ADR/catalogs/baseline неизменны |
| `make docs` | Пройдено: 2046 link occurrences, 575 unique, 1826 OK, 0 errors, 220 excluded offline policy |
| `git diff --check` и whitespace новых Markdown | Пройдено |

Локальные logs сохранены в `.dev/r030-doc-01b1/`. Production Java, POM,
analyzer configuration и accepted ADR не меняются. Новые runtime examples не
добавлены; text/Mermaid diagrams проверяются как документационные схемы.
Полные verify/PMD не перезапускаются и не объявляются fresh. Dependency updates,
новый CVE scan, stand checks и повторная квалификация released libraries в пакет
не входят. Данный аудит не закрывает R030-SEC/REL, ING-11/13, OUT-4 или TOOL-8/9.

## Результат

`DOC-01B.1` verified для данного documentation worktree поверх `5edd3af3`.
Завершены audit по точности/полноте, 3 EN canonical pages, 3 RU translations,
навигация и inventory. Всего выполнен 61 focused test без failures/errors/skips
или retries. Это не закрытие остальных root maps, модульных hardening cells или
финальной release qualification. Следующий root-пакет — CONVENTIONS.
