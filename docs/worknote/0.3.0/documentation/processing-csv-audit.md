---
title: "DOC-02B — Обработка IOC, маршрутизация и CSV"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified for DOC-02B"
document_type: "Audit and verification evidence"
source_of_truth: false
language: "ru"
---

# DOC-02B — Обработка IOC, маршрутизация и формирование CSV

Дата: 2026-11-04, Asia/Irkutsk. HEAD
`4641e5ea9ff5e75edbf61cdff6581108c60f04e4`, ветка `docs/quality`.
На входе готовый незакоммиченный DOC-02A. Его шесть документов и audit record
сохранены побайтово; общие inventory/navigation/status дополнены новым срезом.
Контракт — [R030-DOC](../goals/R030-DOC-documentation.md).

Scope: processing, ioc-processing-routes, csv-output-format, три EN/RU-пары,
навигация, карта источников и evidence. Применены code-documenter,
java-code-review, cli-developer и spring-boot-engineer. Изучение исходников и
тестов подтверждает утверждения выбранных документов; весь Java API,
co-located README, импорт/хранение/экспорт целиком и live stand не объявляются
проверенными по одному факту ссылок.

## Находки и решения

| ID | Наблюдение и влияние | Результат |
|---|---|---|
| `DOC-P01` | Внутренний документ перечислял поток, но не объяснял важные границы refang, overlap priority, атрибуции и классификации | Добавлены буквальный порядок замен, приоритет типов и захват диапазонов, позиции UTF-16 после refang, ближайший предшествующий маркер, first-match rules и нейтральная классификация хешей |
| `DOC-P02` | Исходная дедупликация, итоговые победители и долговременная идентичность могли читаться как один механизм | Разделены три области, exact type/value dedupKey и равенство record; уточнены retained-observations/final-key и LAST_NONEMPTY: последняя целая строка с непробельным полем, иначе первая, без слияния ячеек |
| `DOC-P03` | Порядок artifact filters, column views, gates/providers/transforms не был описан полностью | Указаны default-view для accepts/filter, отдельное представление каждой колонки, условия и порядок преобразований, константы и запрет перепривязки ID/source; lower-host отделён от удаления схемы и получения хоста |
| `DOC-P04` | YAML пример выглядел самостоятельным overlay, но plans заменяется целиком; production contract даже при disabled intake требует original-aggregate-import | Явно описано добавление в полный сохранённый список, новый пример задаёт final-key. Положительный candidate сохраняет исходные планы; отрицательный пример с их потерей получает CONFIG.REGISTRY |
| `DOC-P05` | Dev-документ ссылался на подробности режимов и недоступности в guide, где их не хватало | Добавлены first/all/exclusive, порядок short-circuit, ordinary no-match/default branch, BLOCKED против false/NOT, required field views и явный recover с агрегированием серьёзности |
| `DOC-P06` | Import fragment ссылался на отсутствующий в примере import-hosts; можно было ошибочно ожидать импорт всех вычисленных кодов | Добавлен отдельный полный элемент плана и проверена пара с processed-route. В примере разрешён только mask; вычисленные коды не заполняют неразрешённые output columns автоматически |
| `DOC-P07` | Отличия document text search, processed whole-cell parsing и as-is были недостаточно явными для расширения | Описаны refang/strip выбранного VALUE, точное одно совпадение, отказы partial/multiple/invalid address, ABSENT/NULL/VALUE, независимые вклады и явные transforms/validators в as-is |
| `DOC-P08` | Failure checkpoint и частичный отказ после записи могли восприниматься как общий rollback; dry-run/preview — как полная квалификация | Описаны policy ERROR/FATAL, CLI exit 3, поартефактные document commits против одной import transaction, поздние ошибки, drain полного fingerprint, согласованный stopped backup и пределы check/apply/dry-run/preview |
| `DOC-P09` | Ограничение замен символов было потеряно в processing summary; trace можно было ошибочно считать всегда value-free | Согласованы ALL/ALL replacement против strict MINIMAL/NONE/export; технический Router trace отделён от opt-in per-item TRACE со значениями IOC и источников |
| `DOC-P10` | CSV failure table слишком обобщала сохранность прежнего файла и отсутствие marker при materialization failure | Уточнена ошибка до замены, обязательный ATOMIC_MOVE без fallback, per-artifact замена, private staging с уже возможным marker, separate final profile publication и новая раскладка при следующем допустимом экспорте |
| `DOC-P11` | Не хватало малого наблюдаемого контракта literal NULL против missing и пустого текста; отсутствовали три обязательные RU-пары | Добавлен проверенный пример точных CRLF-байтов, уточнены header semantics, созданы три RU-пары и согласована навигация |

## Привязка к источникам

Точные пути добавлены в source sets `processing` и `csv-format` в
[source-sets.tsv](source-sets.tsv).

| Контракт | Владелец / свидетельство | Ограничение |
|---|---|---|
| Порядок чтения/refang/extract/attribute/prepare/write | IocExtractionService, stream stages, ReplacementRefanger, RegexIndicatorExtractor, MarkerSourceAttributor | Не обновляет автоматически старые root maps или альтернативные finite-batch helpers |
| Классификация и отображение | RuleBasedMatchPolicy, IndicatorClassifier, ConfigurableRowMapper, CsvArtifactPreparer, LowerHostTransform | Проверены описанные инварианты; общий аудит всех публичных Javadoc не выполнен |
| Победители, workspace и граница promotion | PrepareRoutedArtifactsStage, JdbcDocumentPreparationWorkspace, WriteArtifactsStage | Private workspace не является канонической authority или общей гарантией RSS |
| Режимы, required views, recovery | ProcessingPlanCatalog, CompiledSelector, InvocationViews, CamelRouteRuntime, IocProcessingRouteAdapter | Локальное исполнение, не брокер и не durable delivery |
| Processed import | RouterProcessedImportRowPreparer, ExactIndicatorParser, compiled catalog и owning row mapper | Ручной пример проверяет admit/prepare fragments; отдельно существующий E2E доказывает durable import/recovery |
| CSV bytes, encoding, projection, manifest | ExportFormat/Plan/ChangeDetector, CsvFormatConfiguration, CsvRecordFormat, CsvArtifactProjection, CsvSliceMaterialization, JacksonSliceManifestCodec | Форматирование не меняет каноническую идентичность и не гарантирует атомарность набора mutable files |
| CLI flags и outcomes | ExtractCommand, ImportValidateCommand, EarlyCliLauncher, CompletionStatus | Classpath help/version не является квалификацией опубликованного JAR или установленного wrapper |

## Проверки

| Проверка | Результат |
|---|---|
| make context / doctor-core | Текущие branch/HEAD/dirty и JDK 21 проверены; полные gates stale |
| Основной выбранный unit cohort | 15 suites, 162 теста, 0 failures/errors/skips |
| Selector/parser/recovery cohort | 9 suites, 72 теста, 0 failures/errors/skips; всего 234 unit cases |
| Выбранный Failsafe verify | 9 suites, 103 теста, 0 failures/errors/skips; команды ниже. Это не полный make verify |
| Documented plan | Точный YAML из guide добавлен к сохранённому production plan list. Изолированный Spring composition и extract проверяют host collapse, bare IP, original blacklist/aggregate, uppercase hash, configured classification и CRLF CSV |
| Import fragments | План и processed-route из guide проходят catalog admission; подготовка VALUE проверяет refang/strip/host, source authority, неизменный ABSENT у неразрешённых кодов и отклонение partial cell |
| CSV example и configuration | Точный escaped CRLF-блок сравнивается с реальными байтами форматтера; YAML layout проходит semantic check. Существующие CSV suites проверяют 9 сочетаний quoting, NULL, неоднозначность, encoding, atomic files и manifests v1/v2 |
| Отрицательный candidate | Полная замена plans одним документным примером при сохранённом import contract даёт exit 78 / CONFIG.REGISTRY |
| Inline scheme example | Фактический NetworkSchemeDeriver сохраняет Path/query/fragment и source для буквального примера из guide |
| CLI | Реальные extract --help, import validate --help и --version возвращают 0; --source/--dry-run/--file и snapshot version подтверждены |
| EN/RU | Три пары: совпадают heading levels, inline identifiers, normalized target multisets и literal blocks; выполнен смысловой review |
| Inventory / preservation | 351 Markdown paths, 64 source sets; base/current hashes и source paths сверены. 7 документов DOC-02A неизменны относительно входа |
| make docs / whitespace | 2411 occurrences, 626 unique, 2191 OK, 0 errors, 220 excluded; git diff --check PASS |

Unit commands используют `./mvnw -B -ntp -T 1C -pl bootstrap/ioc-app -am test`
с точным списком `-Dtest` и `-Dsurefire.failIfNoSpecifiedTests=false`.
Списки классов/логи и executable documentation fixtures сохранены локально в
`.dev/r030-doc-02b`; make test-one не принимает список selectors.
Failsafe запускается тем же reactor selection с `-Dskip.unit.tests=true verify`,
`-Dfailsafe.failIfNoSpecifiedTests=false` и девятью suite names:
GoldenPipelineIT, CustomerRoutingPipelineIT, RouterSelectedImportDeliveryIT,
NativeDeliveryLifecycleIT, CsvArtifactProjectionIT, CsvArtifactSliceWriterIT,
JacksonSliceManifestCodecIT, JdbcDocumentPreparationWorkspaceIT,
JdbcDocumentProcessingPolicyGateIT. Source/consumer expectations независимы от
фактически полученных строк; тестовые данные синтетические. Это не load/RSS
приёмка, не новый live SMB результат и не квалификация installed service.

## Замечания к локальным исходным Javadoc

Вне трёх выбранных документов обнаружен локальный дрейф комментариев:

- `Indicator` описывает equality только через type/value, хотя record equality
  также включает source. Рабочая дедупликация явно использует dedupKey; новый
  capability doc не распространяет ключ на равенство record.
- `ConfigurableRowMapper` в class-level Javadoc ещё говорит об отсутствии
  условий кроме when-type, хотя реализация поддерживает when-types и when.

Это низкоприоритетное продолжение для среза API/module reference; Java-файлы и
их поведение сейчас не изменялись. Нельзя использовать эти устаревшие строки
как доказательство контракта или объявлять весь API-документированный модуль
проверенным вместе с DOC-02B.

## Состояние

После пакета отсутствуют 64 обязательных RU-пары: 3 root docs, 10 capability
docs, 49 ADR и 2 guides. DOC-02B завершает выбранные три документа, но не общие
DOC-02/04/05, R030-DOC, R030-SEC и R030-REL. Отложенные пользователем
SECURITY-ENGINEERING/THREAT-MODEL не редактировались; принятые ADR, generated
catalogs, historical release records и analyzer/coverage baselines сохранены.

Production/test Java, POM и scripts/workflows не менялись. Полные make verify
и make pmd-analysis не перезапускались; последнее full evidence —
`191e74146dddeeab36bf59d9d09020a92bef0620`, freshness=false. Выбранный Failsafe
verify не создаёт новое доказательство полного release gate. Коммиты, push,
публикации, deployment и privileged systemd actions не выполнялись.
