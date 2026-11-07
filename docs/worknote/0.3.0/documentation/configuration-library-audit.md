---
title: "DOC-02I — Библиотека конфигурации и ограниченный контекст"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation slice"
document_type: "Execution evidence"
source_of_truth: false
language: "ru"
---

# DOC-02I — Библиотека конфигурации и ограниченный контекст

Проверка 2026-11-07 (Asia/Irkutsk), HEAD
`37eb6a0714f5e08ecad067403280ce2eff2934f4`, ветка `docs/quality`.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).

Комплект — три EN/RU-пары:
[configuration-inspection](../../../dev/configuration-inspection.md),
[config-spring-boot](../../../guides/libraries/config-spring-boot.md),
[config-spring-boot-check](../../../guides/libraries/config-spring-boot-check.md).
Создан отсутствовавший capability-перевод; существующие библиотечные guides
уточнены без изменения Java/XML/YAML/expected-output blocks.
Применены code-documenter, java-code-review, cli-developer, spring-boot-engineer.
CONVENTIONS и согласованный формат/границы аудита сохранены.

На входе готовый незакоммиченный DOC-02H. До правок сохранены hashes 367
Markdown paths, включая его новые файлы, и исходные inventory/source-set rows.
Содержательные файлы H и A–G сохранены; общие navigation/registry/status
дополнены без сброса чужих review states. Java/tests/POM/scripts, quality scope,
generated catalogs, принятые ADR/release records и root security docs не менялись.
Новых commit, publication, protected CI recovery или deployment не выполнялось.

## Находки и решения

| ID | Приоритет / наблюдение | Результат |
|---|---|---|
| `DOC-LC01` | M: complete coverage могло выглядеть доказательством загруженного кандидата или обязательных настроек | Уточнены empty/other-namespace/excluded-only results; наличие значений и правильный выбор источников проверяет сервис после loading/binding |
| `DOC-LC02` | M: исключение aggregate и environment qualification не раскрывали точную границу доверия | Исключение зависит от имени configurationProperties до проверки типа; источник с таким именем резервирует вызывающий код. Source classification не удостоверяет происхождение данных |
| `DOC-LC03` | M: source ordinals и immutable snapshot можно было принять за постоянную identity/заморозку входов | Ordinals ограничены одним обходом; изменение источника после inspection видно Binder. Источники и их ресурсы принадлежат вызывающему коду |
| `DOC-LC04` | M: name-only API не объяснял стоимость и произвольную работу callbacks | Добавлены synchronous execution, rebuild schema, copied/sorted names, несэмплированные результаты, отсутствие inspector quota/timeout/cancellation и ответственность за cardinality |
| `DOC-LC05` | M: общий name-only contract и value-reading closed recipe нуждались в явном разделении | Loading, placeholder check, Binder и validation читают значения по отдельным контрактам. Ограниченный набор beans не задаёт input/resource sandbox |
| `DOC-LC06` | M: coarse Code enum не объяснял этапы и пределы адаптации ошибок | Добавлена таблица четырёх методных outcomes; они не задают CLI exit codes, programming callbacks и Error не объявлены безусловно нормализованными. Owned CheckFailure отделён от framework logs |
| `DOC-LC07` | M: пустой snapshot не мог подтвердить namespace migration catalog | Выбор каталога привязан к схеме сервиса. Сохранены UNKNOWN/AMBIGUOUS/UNSUPPORTED, placeholder mapping и отсутствие автоматического Binder alias/конфликта значений |
| `DOC-LC08` | L: capability не имел RU, ссылка README#migration-observations указывала на отсутствующий раздел; RU guide не повторял maintenance rule | Создан перевод, ссылка заменена на owning migration API и действующий guide section; добавлены точные checks, When to update, navigation и согласованное сопровождение обеих версий |
| `DOC-LC09` | M: локальные source/tests и свежий repository consumer не должны подменить published identity или protected CI recovery | Проверены original manifest/четыре hashes, совпадение всех 10 Java sources и cold Central consumer с default/BOM graphs. TOOL-8 остаётся открытым; публикация не повторялась |

## Живые источники и ownership

Source set `library:config-spring-boot` уточнён в [source-sets.tsv](source-sets.tsv).

| Граница | Источники | Проверенная область |
|---|---|---|
| Schema/name inspection | PropertySchema, PropertyInspector, SchemaModel, NameMatcher, PropertyInspection | Root/nested shape support, source traversal, unknown/ambiguous/unsupported, sorting, exact aggregate exclusion, immutable facts и completeness |
| Migrations | PropertyMigration, MigrationPath, MigrationInspector, MigrationInspection | Canonical templates/indexes, catalog rejection, full matching, original coverage, empty namespace и отсутствие mutation/binding alias |
| IOC integration | IocConfigurationPropertyShape, IocEnvironmentPropertyMatcher, IocUnknownConfigurationPreflight, override reporter, semantic checker | IOC сохраняет stricter policy/fallback, controlled output и effective Boot sources; общая библиотека не берёт эти решения на себя |
| Closed recipe | Actual Markdown Java/YAML, ConfigurationCheckRecipeIT, ConfigurationRecipeMain | Explicit source policy, ambient isolation, safe owned error categories, whole-list replacement и context cleanup без IOC runtime |
| Publication | Component POM, publication tool, configuration-consumer, immutable release record | Required Boot/Core, default/application BOM closure, packaged examples, origin/hash и dependency/routing negatives |

Прочитаны public/package Javadoc, внутренние schema/name/migration helpers и
module README как опорный reference. Полный rewrite README или аудит любого
linked module не объявляется. Две старые compatibility-entry страницы продолжают
вести к maintained EN/RU guides; их содержимое не менялось.

[Boot external configuration](https://docs.spring.io/spring-boot/4.0/reference/features/external-config.html)
сверен как первичный framework reference. Онлайн ветка 4.0 показывает 4.0.9;
проверяемые POM/consumer остаются на 4.0.8 / Spring 7.0.9. Эта сверка не
квалифицирует upgrade или все Boot versions.

## Выполненные проверки

- **90 unit/contract cases, 10 suites**, без failures/errors/skips. Все четыре
  suites библиотеки — 36 cases: PropertySchema 6, PropertyInspection 13,
  MigrationInspection 13, GenericConsumer 4. IOC/документные suites — 54:
  UnknownConfigurationPreflight 5, ConfigurationPropertyShape 3,
  ConfigurationOverrideReporter 6, SemanticConfigurationCheck 32,
  ConfigurationDocumentationContract 7, DocumentationConvention 1.
- **85 IT cases, 3 suites**, без failures/errors/skips: ConfigurationCheckRecipeIT 1,
  IocPropertiesBindingIT 73, IocConfigurationContextIT 11. Actual guide recipe
  компилируется и запускается отдельно без IOC classes, под синтетическими
  environment/system/JSON/classpath inputs и с ограниченным ожиданием/cleanup.
  Expected startup failures в отрицательных fixtures не являются suite failures.
- **228 offline publication contracts** прошли. Синтетические Central/deployment
  ответы внутри этих tests не являются внешней публикацией или recovery run.
- **14 targeted boundary cases** прошли в отдельном JVM на original published
  main JAR с extracted Java/YAML recipe и сторонними зависимостями, без IOC runtime.
  Проверены empty/unrelated/excluded-only coverage, смена имени источника,
  snapshot-local ordinals, later mutation/Binder, enumeration exception identity,
  empty migration namespace, четыре fixed CheckFailure categories без cause и
  два programming-callback/Error escapes. БД и runtime components не создавались.
- **EN/RU parity:** headings, количество inline-code tokens, literal blocks и
  normalized targets совпали. Все executable blocks четырёх библиотечных guides
  совпали с HEAD; API/recipe examples остались исходными, дополнялась prose.
- **Ссылки и inventory:** make docs прошёл — 2886 total / 701 unique / 2642 OK /
  0 errors / 244 excluded; отдельно проверены 6 local Markdown anchors во всех
  шести выбранных документах. Inventory подтвердил все current/base hashes,
  359 сохранённых входных Markdown paths и неизменность H/чужих registry states.
  `git diff --check` прошёл.

## Published identity и новый Central consumer

Исходная запись: [ioc-config-spring-boot 0.3.0-rc.1](../../../../.github/release-notes/ioc-config-spring-boot-0.3.0-rc.1.md).
Source `68679dd18ed251b71c4c0f4480b440a270dc1e71`, immutable component tag
`ioc-config-spring-boot-v0.3.0-rc.1`; проверка не изменяла их.
Original signed identity SHA-256:
`e57f1f52f53cc95ff5290943d9c5cd790928ea063fc726ca7388c7fe021f74f2`.
Все четыре primary hashes из release record совпали; 10 Java source files из
original sources JAR побайтово равны текущему src/main/java.

Для нового consume скопированы только identity.json и repository из
`.dev/configuration-release-0.3.0-rc.1/published/signed-library` в собственный
`.dev/r030-doc-02i/configuration-published-bundle`. Original identity после
проверок сохранил тот же hash. Новая подпись/cryptographic trust qualification
не выполнялась: проверялись manifest identity и hashes исходных файлов.

`make library-consumer LIBRARY_COMPONENT=configuration` с этой копией и
`LIBRARY_REPOSITORY=central` завершился успешно:

- Независимый project вне checkout, изначально пустой Maven cache и пустые
  settings; four main/POM/sources/Javadoc artifacts пришли с library-target
  origin и совпали с original bundle.
- Реальные guide API/recipe Java и YAML выполнились при default и application-BOM
  mediation. Оба графа проверены явно; BOM использует уже созданный private cache,
  это не два отдельных cold-cache проекта.
- Incompatible Spring / unavailable Boot были отвергнуты по ожидаемой причине.
  Missing selected library не маскировалась populated alternate repository;
  отдельный unfiltered control разрешил четыре ожидаемых файла.
- Новый GitHub consumer или protected CI run не запускался. TOOL-8 не закрыт,
  источник/toolchain/recovery identity исторического релиза не переписывались.
  Consumer witness не закрывает полный API-comparison TOOL-6.

## Учёт, сохранение и свежесть

Текущий census — 369 Markdown paths / 64 source sets. Отсутствуют 53 RU-пары:
3 root, 49 ADR и 1 guide; capability-документы теперь все имеют RU-пары.
Исходный census 329 на `2c76d8b095a2162c7038c64de896b244517cf4c7` и base hashes
сохранены. Изменяется только owning source set библиотеки; capacity/H и прочие
registry states остаются прежними.

Evidence в ignored `.dev/r030-doc-02i/`: input hashes/rows, unit/integration logs,
retained unit XML, publication-contracts.log, published-identity-check.json,
central-consumer.log, boundaries.json/логи, parity/inventory/link checks.
Входные XML сохранены до следующего Maven invocation, очищающего stale reports.

Full verify/PMD не запускались: прежние PASS принадлежат
`191e74146dddeeab36bf59d9d09020a92bef0620`, freshness остаётся false.
Deployment, installed wrapper, live SMB, другая версия Boot, второй deployed
service, live reload или global security qualification не выполнялись.
DOC-02H, TOOL-10, OUT-4 и OPS-10/11/12 сохранены. DOC-02I закрывает документный
комплект, а не R030-DOC/SEC/REL, независимые версии TOOL-9 или protected TOOL-8.
