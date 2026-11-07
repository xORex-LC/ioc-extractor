---
title: "DOC-02L — Единый IOC aggregate feed"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation slice; open OPS-14"
document_type: "Execution evidence"
source_of_truth: false
language: "ru"
---

# DOC-02L — Единый IOC aggregate feed

Проверка 2026-11-07 (Asia/Irkutsk), HEAD
`37eb6a0714f5e08ecad067403280ce2eff2934f4`, ветка `docs/quality`.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).
Выбранный комплект: [IOC aggregate EN](../../../guides/ioc-aggregate.md)
и [RU](../../../guides/ru/ioc-aggregate.md), корректность, полнота, настройки
и реальные границы текущего сценария.

Применены code-documenter, maven-dependency-audit, multi-module-maven,
cli-developer, java-code-review и spring-boot-engineer. Согласованный формат
EN/RU и ownership сохранён. На входе ready H/I/J/K без commit: сохранены hashes
371 Markdown paths и registry rows. Эти пакеты не переписаны. В KNOWN-ISSUES
добавлена только OPS-14; полный аудит реестра не заявлен.

## Вывод о поддержке сценария

Текущая реализация объединяет шесть типов IOC и observations разных источников
в одном артефакте/фиде. Она не объединяет IP/domain/URL в составную сущность и
не строит aggregate как union готовых таблиц/CSV. Это обычная configured ветка
extraction/import, а не отдельный runtime mode, модуль или дополнительный batch.

Проверены четыре carrier-колонки, no-public-id, uppercase hashes, подробный
URL и clean host, full-tuple identity, выбранное имя, generation/export/import,
empty-start, lifecycle и recovery. Новые guide sections делают этот scope
доступным оператору, а не оставляют его в release worknotes.

## Находки и решения

| ID | Приоритет / наблюдение | Результат |
|---|---|---|
| `DOC-AGG01` | M: название aggregate можно понимать как корреляцию нескольких carriers/union существующих artifacts | Объяснены один фид разных типов/источников и одна IOC observation на строку; без DNS, join, inferred entity и автоматического backfill |
| `DOC-AGG02` | M: field names можно спутать с mask classification codes; не хватало consumer identity semantics | `url_match` — адрес, не u:hEX; case-preserving URL detail, hash lengths, no id/slot, full normalized tuple и нестабильность name как ID явно описаны |
| `DOC-AGG03` | M: не было практической связки artifact/plan/identity/export/import catalogs | Добавлены config table, точный YAML artifact entry, warning whole-list replacement и сохранение original view при host routes других artifacts |
| `DOC-AGG04` | M: отсутствовал воспроизводимый путь document → accumulated projection → immutable feed | Добавлены actual HTML/CLI examples, пути файлов, различие mutable/immutable output, profile authorization для SMB и отсутствие per-source filtering по name |
| `DOC-AGG05` | M: policy/name/revision/lifecycle различались недостаточно ясно | Объяснены registration против completion/time, whole-occurrence last-nonblank, name-only mutation, unchanged revision и expiry-only export cadence |
| `DOC-AGG06` | M: preview предлагался до enabling import без объяснения его prerequisites | Enabled catalog/fixed lifecycle проверяются в disposable oneshot copy, затем live daemon intake; classpath пустые catalogs отделены от production sample |
| `DOC-AGG07` | M: импорт не раскрывал source ID, plan/authority, null/cardinality и отказ compound rows | Добавлены exact shipped bindings и CSV example, target-only routing, reject-delivery, preview limits/exit codes и отсутствие imported ID |
| `DOC-AGG08` | M: health подразумевал только crashed oneshot; empty-start мог читаться как reset на restart | Pending может быть live; нельзя удалять ranks ради health. Empty-start ограничен первым добавлением, ordinary restart сохраняет state |
| `DOC-AGG09` | M: current schemas были зафиксированы как v11/v11 | Ссылки на live migration catalogs вместо дрейфующей версии; rollback остаётся coordinated. Исторические P0–P7/release records не менялись |
| `DOC-AGG10` | M source defect: whitespace imported name обходит ordered guard | Actual private daemon воспроизвёл OPS-14; guide/KNOWN-ISSUES описывают нарушение и workaround empty/NULL. Source fix не делался |

## Живые источники и contract coverage

Сверены [classpath defaults](../../../../bootstrap/ioc-app/src/main/resources/application.yml),
[production template](../../../../packaging/templates/application.yml),
[AppConfig](../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java),
[mapper](../../../../core/ioc-processing/src/main/java/com/iocextractor/processing/mapping/ConfigurableRowMapper.java),
[document selection](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcDocumentPreparationWorkspace.java),
[ordered field store](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcOrderedFieldStore.java),
[import writer](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriter.java),
[registration store](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcObservationRegistrationStore.java),
CLI commands и observation health/retention composition.
Исторические contract/network amendment использованы как контекст, не вместо
проверки текущих routes. Public docs не ссылаются на execution worknotes.

| Покрытие guides | Evidence |
|---|---|
| Все шесть типов, columns, normalization, exact one carrier | Golden/customer IT, live extracted examples, structural validators |
| Один feed, независимые artifacts и original-view mapping | Classpath/production plan и CustomerRoutingPipelineIT |
| Full identity, name precedence, retries/recovery, public revision | Selection/admission unit, JDBC mutation/registration/import IT и actual repeat/rename extraction |
| Export profile и consumer bytes | OnDemandExportIntegrationIT и actual CLI immutable slices |
| Processed import, rejected malformed/compound rows, source authority | Catalog/compiler/port IT, actual valid/invalid preview и local daemon intake |
| Startup/health/retention/rollback | Source review, PreparedDocumentPromotionIT, ObservationRegistrationOperationsTest и owning capability docs |

Это coverage operator guide, не Javadoc coverage всех модулей или новый load/SLO.

## Исполненные проверки

- **78 unit/contract cases, 9 suites:** ConfigurableRowMapperTest,
  LowerHostTransformTest, DocumentObservationSelectionTest,
  ObservationAdmissionServiceTest, ObservationRegistrationOperationsTest,
  ExportPlanCatalogTest, ArtifactIdentityConfigurationResolverTest,
  DataframeImportCatalogCompilerValidationTest, DocumentationConventionTest.
- **61 IT, 7 suites:** JdbcCanonicalImportWriterContractIT 33,
  JdbcCanonicalMatchAndMutationIT 15, JdbcObservationRegistrationStoreIT 8,
  GoldenPipelineIT 2 и CustomerRoutingPipelineIT, OnDemandExportIntegrationIT,
  PreparedDocumentPromotionIT по 1. Все без failures/errors/skips. XML сохранены
  по фактическим suite names log, stale reports не включены.
- **21 actual packaged CLI JVM:** help/version; document extraction всех шести
  типов; повтор и skipped export; новое name при той же identity и новый slice;
  unnamed observation сохраняет имя; unnamed new row имеет NULL; внутри документа
  latest nonblank occurrence побеждает. YAML fragment равен shipped artifact entry.
- Actual preview: sample CSV имеет 4 accepted/0 rejected; compound, zero-carrier,
  invalid hash и dirty host дают exit 3. В preview storage canonical aggregate
  остаётся пустым. Full CLI initializes storage/workspaces; нулевой I/O не заявлен.
- **Actual private managed daemon:** shipped processed contract, один local source,
  isolated cwd и собственный ephemeral loopback port. Первая CSV delivery создала
  4 canonical rows. Вторая с whitespace-only name воспроизвела OPS-14. Owned
  process завершён SIGTERM без forced kill; installed service/systemd/SMB не
  использовались. Запуск daemon сам выбирает servlet mode, поэтому fixture задаёт
  port 0, а не пытается использовать host port 8081.
- EN/RU semantic review, heading levels, шесть literal blocks, normalized link
  targets, CLI flags, inventory/current/base hashes и local links проверяются
  отдельно. Сохранены 366 входных Markdown paths, остальные source-set rows,
  все current/base hashes; в KNOWN-ISSUES добавлена только OPS-14. `make docs`:
  3024 total / 721 unique / 2768 OK / 0 errors / 256 excluded;
  `git diff --check` PASS. Наличие ссылки не превращает прочие README в reviewed.

## OPS-14: подтверждённое ограничение

В [JdbcCanonicalImportWriter](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriter.java)
`orderedCandidate` отвергает `isBlank()` VALUE. Но после этого generic merge
ветка может выполнить SET для `replace-non-null`. В проверке `name=Feed-A`
после второй delivery стал тремя пробелами, число строк осталось 4.
Все name field origins сохранили admission order 1, хотя новая registration
получила order 2: изменение прошло вне ordered-field update path.

Это реальный current implementation defect, не обещанный configurable способ
очистки имени. Current workaround — empty cell/NULL для отсутствующего имени,
без whitespace padding. Remediation требует согласовать blank policy перед
generic mutation и regression для прямого/обратного порядка, NULL/blank/nonblank,
нового/active lifecycle и настоящего aggregate import. До source fix guide
не обещает blanket nonblank protection для whitespace CSV input.

## Dependency и binary evidence

Для probes сохранены **те же bytes**, что квалифицированы в DOC-02K:
`ioc-app.jar` SHA-256
`cc6907fcb669f33b76f56d84016e360abbcb8fd6ca0b8f6c66853b5f1508fe21`.
Это локальный snapshot bundle на указанном HEAD с dirty docs, не новый release.
Source/POM не менялись; artifact использует существующие processing/application,
CSV/JDBC и bootstrap contracts. Новый Maven module, dependency, shared-library
release или версия для aggregate не требуются.

195 nested JAR / 171 external coordinates равны K bundle. Его датированный OSV
screen/metadata сохранён как reuse evidence: 11 advisories / 7 coordinates,
SRC-3 disposition открыт. Нового vulnerability scan, NVD refresh, dependency
upgrade или полного API/convergence/security gate в L не выполнялось.
Успех aggregate tests не закрывает SRC-3 или release security acceptance.

## Учёт и свежесть

Census — **372 Markdown paths / 64 source sets / 53 missing RU pairs**
(3 root, 49 ADR, 1 guide). Добавлен один audit, обе aggregate translations уже
существовали. Исходный census 329 и base hashes на
`2c76d8b095a2162c7038c64de896b244517cf4c7` сохранены. Меняется только owning
source set aggregate; KNOWN-ISSUES получает одну новую строку OPS-14.

Ignored evidence `.dev/r030-doc-02l/`: input hashes/rows, unit/IT XML/logs,
retained JAR identity, examples.json/fixtures/logs, actual managed-intake JSON/logs,
dependency-review-reuse и parity/inventory/link checks. Первый пробный preview
использовал Spring options как picocli flags и получил exit 2; исправлен harness
на JVM properties. Первый daemon fixture занял бы default port и корректно
отказал; исправлен на isolated port 0. Неуспешные attempts сохранены и не
зачислены как pass, существующий процесс на host не останавливался.

Предыдущие H/I/J/K и их findings сохранены. Java/tests/POM/tooling, ADR, release
records, generated catalogs и root security docs не менялись. Новых commit,
publication, deployment/restore установленного сервиса или SMB qualification нет.
Full verify/PMD не запускались: прежние PASS принадлежат
`191e74146dddeeab36bf59d9d09020a92bef0620`, freshness обоих false.
OPS-14, OPS-10/11/12/13, SRC-3 и остальные release debts не закрыты.
Документный комплект завершён с открытым дефектом; R030-DOC/SEC/REL открыты.
