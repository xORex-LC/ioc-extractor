# Управляемый импорт dataframe

[English version](../dataframe-import.md)

Управляемый импорт принимает CSV-доставки оператора из выделенных локальных
каталогов или SMB inboxes. Каждый стабилизированный файл — отдельная occurrence:
сервис получает владение, копирует его в неизменяемый локальный snapshot,
распознаёт ровно один версионируемый контракт, подготавливает данные на диске и
переносит их в каноническую SQLite одной транзакцией по всем артефактам.

Возможность по умолчанию отключена. Для включения нужны JDBC service/dataframe
storage, фиксированный lifecycle и полный каталог sources, authority и contracts.
Intake работает в daemon; установленные CLI-команды используют отдельный
oneshot context. Managed import не возвращает удалённый путь lookup/seed из
заполняемых вручную CSV.

## Поток выполнения

```text
periodic complete listing <------------------------------ correctness backstop
        ^                         local watch / SMB CHANGE_NOTIFY (doorbells)
        |
positive source capability gate
  -> source-scoped READY / TRANSIENTLY_UNAVAILABLE / INCOMPATIBLE
        |
stabilized candidate
  -> reserve durable delivery ID and global sequence
  -> reserve and allocate shared dataframe admission order
  -> claim source ownership
       local: atomic move into the private processing directory
       SMB: server-side rename into inbox/.ioc-managed-import/processing
  -> create and pin a private immutable local snapshot
  -> exact-one recognition and strict streaming mapping
  -> sealed per-delivery SQLite stage
  -> active-only match, merge and preferred-slot planning
  -> one dataframe transaction plus import_commit receipt
  -> atomic protected source/report terminal unit
  -> local or remote terminal/quarantine disposition
```

Продвигается только минимальная сохранённая nonterminal sequence. Повторяемая
голова сохраняет своё место; порядок executor, файловых событий или SMB
уведомлений не определяет бизнес-порядок. Резервирование claim в ledger и каждый
следующий checkpoint идемпотентны: startup recovery продолжает незавершённую
доставку по сохранённым данным, а не создаёт новую occurrence.
Import sequence управляет FIFO; dataframe admission order — общий порядок
документов и импорта для настроенных изменяемых полей. Service schema v11
сохраняет admission-reservation marker в той же транзакции, что создаёт доставку.
Recovery может выделить отсутствующий rank только при доказанном новом
резервировании. Legacy delivery без rank отвергается, а не получает новый порядок.

Переходы service ledger и их audit timestamps используют максимум переданного
wall UTC и сохранённого `updated_at_ms` доставки. Terminal retention отсчитывается
от того же неубывающего времени; скачок часов не сокращает настроенный срок.
Retry deadlines остаются буквальными абсолютными UTC. Finalization по одному
receipt может продвигать координацию после commit, даже когда независимая
safe-clock policy уже запрещает новую каноническую публикацию.

## Распознавание и отображение

Распознавание проверяет все разрешённые источнику charset/dialect/header
signatures и требует ровно одного совпадения. Required headers обязательны;
optional и ignored допускаются только при объявлении. Aliases разрешаются в
канонические имена, поэтому имя вместе с его alias — повтор, а не два поля.
Неожиданные, отсутствующие или повторные канонические headers отклоняют
распознавание. Имена точные и чувствительны к регистру; порядок колонок не
выбирает контракт. Каждая data record обязана иметь столько физических полей,
сколько header. Отсутствующий optional header и пустая ячейка — разные случаи,
а не синтаксис короткой строки. Parser строго декодирует байты, проверяет
разделители записей и ресурсные пределы; замена символов не исправляет неверный файл.

Отображение `as-is` выполняет только transforms закреплённого контракта и
сохраняет состояния ячеек `ABSENT`, явный `NULL` и `VALUE`. Режим `processed`
передаёт mapped logical row обычным refang/extraction/classification/artifact
mapping policies. Поля identity/routing/match под управлением pipeline заменяют
импортированные копии; metadata оператора сохраняет трёхсоставную merge semantics.
Каждый заполненный IOC carrier должен содержать один IOC на всю ячейку;
составные URL/IP или multi-hash fields остаются одной атомарной строкой артефакта.
Column `validation` задаётся явно в обоих режимах; AS_IS сам по себе не
проверяет IOC syntax. IOC validators используют whole-cell extraction, разбор
поддерживаемого network syntax и классификацию, не меняя принятое значение.
`network-address` принимает поддерживаемые IPv4, domain и URL; `md5`, `sha1` и
`sha256` дополнительно требуют алгоритм целевой колонки. `canonical-integer`
принимает только каноническое десятичное представление signed 64-bit integer.
Это не позволяет строковым identity `10` и `010` стать равными публичными
значениями из-за INTEGER affinity. Nullable cells обходят value validation и
сохраняют presence state. Import behavior pin включает semantic revision
валидатора независимо от document policy. Обновление validation semantics может
заблокировать unsealed restaging под старым contract pin; sealed-stage и
committed-receipt recovery сохраняют свои границы. До такого обновления
завершите nonterminal imports.
Для объявленного `source-label-target` finalization проверяет, что source
по-прежнему взят из принятой ячейки. Отсутствующий source и явный NULL остаются
различными инструкциями. Маршрут не может заменить этот source target.
Каждый `processed` contract требует `processed-route` с именованным IOC plan,
явными source-cell inputs и разрешёнными final-field outputs. Отсутствие
привязки останавливает startup даже при отключённом intake; preparer не выводится
из providers, альтернативного пути обработки нет. Маршрут не расширяет authority
источника и не создаёт вторую logical row того же артефакта. `as-is` независим
и никогда не вызывает IOC routing. Compiled contract pin включает выбранный
план, порядок bindings и processing semantic epoch.
См. [ADR 0034](../../ADR/0034-required-router-processing-plans.md).
Когда несколько выбранных inputs вносят вклад в одну строку артефакта, null
неприменимого mapping provider не является вкладом и не конфликтует с другим
значением. После сбора вкладов routed artifact с non-null output заменяет
route-owned targets группой: прежний `VALUE` в output target без итогового
вклада очищается. Поэтому URL, преобразованный в чистый IP, не оставляет прежний
URL carrier или path в итоговом ключе. При отсутствии non-null route output
принятые ячейки не меняются. `ABSENT` и явный CSV `NULL` различаются;
различные non-null вклады в одну цель по-прежнему отклоняют logical row.
Предупреждения каждого принятого участника совместимой COALESCE group, включая
участников, чьи ячейки вошли в representative, сохраняются в canonical receipt.
Rejected rows не добавляют accepted warnings. Recovery по receipt воспроизводит
тот же список без повторного открытия sealed stage.

Processed staging открывает application-owned preparation session после
проверки contract pin и закрывает до sealing, включая read/map/append failures.
Выбранные routes переиспользуют успешную ограниченную семантику между строками
одной попытки; все строки всё равно проходят обычные validation, authority checks
и staging. Stateless preparers передают в session и mapping, и source authority.
Restaging начинает новую session; sealed-stage/receipt recovery не открывают её.
Direct mapping и advisory validation остаются построчными.
См. [ADR 0033](../../ADR/0033-bounded-semantic-reuse-during-import-staging.md).

### Presence, merge и идентичность

Отсутствующий mapped optional header даёт `ABSENT`. Пустая ячейка или точный
настроенный null literal даёт `NULL`; CSV quoting не экранирует эту import
инструкцию. Остальной текст — `VALUE`, включая пробелы, если явные transforms
их не меняют. Объявленные transforms идут по порядку, затем mapper снова
проверяет null/empty, formula policy и value validation. Formula rejection
проверяет первый непробельный символ на `=`, `+`, `-` или `@`; это не
spreadsheet sanitizer. `machine-only-preserve` требует явной source authority.

Effective merge policy определяется column override, затем artifact default,
затем contract default и ограничивается authority источника. Для существующей
активной записи:

| Политика | Входящий VALUE | Входящий NULL |
|---|---|---|
| `keep-existing` | сохранить прежнее значение | сохранить прежнее значение |
| `fill-missing` | заполнить только прежний NULL | сохранить прежнее значение |
| `replace-non-null` | установить входящее значение | сохранить прежнее значение |
| `authoritative` | установить входящее значение | очистить прежнее значение |
| `reject-conflict` | заполнить NULL или принять равенство; иначе отклонить logical row | принять прежний NULL; иначе отклонить logical row |

`ABSENT` никогда не меняет поле. Новые записи инициализируются переданными
значениями; `keep-existing` не отбрасывает их из-за отсутствия прежней записи.
Настроенные ordered mutable fields также подчиняются общему observation rank:
более раннее позднее завершение не перезаписывает новое зарегистрированное значение.

Within-delivery reduction использует итоговый record key основного артефакта.
`keep-first` сохраняет первую принятую целую строку; `last-nonempty` выбирает
последнюю целую строку с непробельным `duplicate-selection-column`, а при
отсутствии такой — первую; `coalesce` объединяет
совместимые presence/value вклады и отвергает конфликтующие группы. Эти политики
не заменяют cross-delivery matching. Primary и объявленные альтернативные match
keys разрешают только активные строки; несколько совпадений отклоняют logical
row. Merge, меняющий стабильную primary identity найденной записи, отклоняется
с `IMPORT.STABLE_IDENTITY_CONFLICT`, а не превращается молча во вставку.

Принятая публичная мутация подтверждает срок даже при `renew-unchanged: false`.
Этот флаг управляет иначе неизменным подтверждением, а не правом изменённых
данных получить deadline. Принятый непробельный ordered-field candidate также
запрашивает подтверждение, даже если публичный текст уже совпадает. Флаг не
замораживает TTL целиком. Подтверждение только TTL не увеличивает public revision и не
требует нового immutable export. Retry по тому же commit receipt не подтверждает
доставку повторно.

## Владение источниками

Local и SMB sources реализуют один application lifecycle port, сохраняя
транспортные механизмы владения.

Выбор кандидатов намеренно не зависит от имени и суффикса. Временный файл
producer поэтому должен оставаться вне source directory до атомарной передачи;
стабильный `.part` внутри source тоже является кандидатом доставки.

Для SMB adapter сначала сравнивает leaf, size, last-write time и server file ID
кандидата из listing, затем переименовывает его на сервере в свой namespace.
Имя назначения — SHA-256 token сохранённого delivery ID, без producer filename.
Одновременное наличие producer и destination objects — collision с отказом;
ни один не перезаписывается. После restart та же доставка может принять уже
переименованный объект.

До listing каждый source проходит транспортно-нейтральный положительный
capability gate. Local sources проверяют свой корень. SMB требует заранее
созданные оператором `processing`, `terminal`, `quarantine`, `probe`, затем
доказывает создание пустого private object, его переименование по принятому
private flow и точное удаление. Отказ закрывает source и повторяется при
следующем ограниченном reconcile; другие sources остаются открытыми.

Materialization открывает claimed file для чтения с запретом concurrent write
sharing и потоково передаёт его общему local snapshot store. Source adapter
проверяет source evidence до и после stream; общий store владеет byte limit,
digest, fsync, no-replace atomic publication, reference compatibility и purge.
Каноническая обработка начинается только с pinned snapshot. Terminal outcome
перемещает remote processing object в `terminal` или `quarantine` лишь после
создания защищённого локального source/report unit.

Sync и managed import используют один lazy endpoint-keyed SMB session pool.
Операции одного endpoint сериализованы: idle close или reconnect не инвалидируют
активный handle; разные endpoints независимы. `CHANGE_NOTIFY` использует
выделенные long-poll sessions и испускает лишь source-scoped listing hints.
Polling продолжается при отключённых, потерянных, повторных notifications и
после reconnect.

## Границы

- `ioc-application` владеет contracts, exact recognition, tri-state mapping,
  staging/promotion/finalization, durable-order policies и независимыми от
  storage/transport ports. CSV, JDBC, SMBJ и Spring imports здесь нет.
- `adapter-csv` владеет strict charset decoding, построением Commons CSV dialect,
  потоковыми строками и import transforms.
- `adapter-store-jdbc` владеет service v9 delivery foundation и v11 observation
  reservation, dataframe v12 accepted-row warnings, private sealed workspaces,
  active-only canonical matching/mutation, sparse requested slots и dataframe
  `import_commit` receipt.
- Принятая processed row может отдельно от rejection `issues` нести ограниченный,
  value-free список `warnings`. Sealed workspace хранит его отдельно; canonical
  transaction копирует в dataframe receipt, чтобы post-commit report recovery
  воспроизводило его без новой обработки. Публикуются warnings representative
  и совместимых COALESCED members; rejected rows их не добавляют. Workspace
  хранит максимум `maximum-row-errors` warning details (default 100,000);
  последующие details текущий writer опускает. Это предел деталей, а не точное
  число всех предупреждений подготовки.
- `adapter-ingest` владеет local claim/terminal filesystem mechanics, общей для
  всех source transports реализацией immutable snapshot store на локальном
  filesystem и local WatchService hints.
- `adapter-transport-smb` владеет SMB sessions, server rename, object evidence,
  claimed-byte access, positive private-namespace probe, remote disposition,
  exact terminal-source purge и `CHANGE_NOTIFY`. Зависимости от local adapter нет.
- `ioc-app` — единственный composition root. Он объединяет ordinary-ingest/import
  recovery barrier и post-commit canonical-change path.
- `ioc-application-tck` владеет переиспользуемыми ledger/canonical-promotion contracts.

## Инварианты

1. **Delivery — occurrence, а не digest.** Повтор равных bytes резервирует новый
   ID и global sequence.
2. **Распознавание требует ровно одного совпадения.** Имена, порядок каталога и
   оценочный fallback не выбирают контракт.
3. **Владение предшествует bytes.** Staging/canonical path не принимает producer
   file напрямую; нужен immutable snapshot после local move или SMB server rename.
4. **Строки потоково уходят из heap.** Parsing использует synchronous callback
   и ограниченные workspace batches. Жёсткие field/record/row/column/snapshot
   limits действуют до неограниченного роста.
5. **Одна входная строка — логическая атомарная единица.** Отказ ветви отклоняет
   весь её детерминированный fan-out. Принятые ветви продвигаются вместе.
6. **Canonical promotion — одна транзакция.** Публичные мутации, ordered field
   origins, lifecycle effects, aliases, preferred export slots, revisions и
   receipt имеют before-or-after по всем затронутым артефактам. Новое
   зарегистрированное nonempty value побеждает даже при более раннем commit;
   позднее завершение старого наблюдения не перезаписывает его.
   Независимо зафиксированные canonical/lifecycle ID reservations остаются в
   dataframe DB под общим writer admission. Неудачные диапазоны сгорают; sealed
   CSV staging не становится владельцем канонических ID или receipt.
7. **Совпадают только активные строки.** Отсутствующие строки импорта не удаляют,
   не меняют и не продлевают локальные записи. Исторические lifecycle не участвуют.
8. **Sealed stage — неизменяемое свидетельство.** Snapshot/contract pins, plan
   hash, counts, SQLite integrity и stage digest должны совпасть до promotion.
   При restart без sealed stage активный контракт должен совпасть с durable
   pin до remapping. Несовпадение блокирует ordered lane до восстановления
   прежней конфигурации; committed deliveries завершаются только по receipt.
9. **События сокращают задержку.** Полный listing, service ledger, dataframe
   receipt и periodic reconciliation восстанавливают потерянные локальные hints.
10. **Terminal evidence — единица.** Защищённый source и безопасный JSON report
    публикуются атомарно; raw IOC cells, producer paths и source-content digests не попадают в reports,
    health или diagnostics.
11. **Retention удаляет authority последней.** Forward delivery сначала удаляет
    точный transport-managed terminal object, затем local terminal/workspace/
    snapshot/receipt; ledger row удаляется последней. Replay явно source-detached
    и не выполняет source disposition/retention.
12. **Aggregate import — target-only.** Поставляемый отключённый
    `ioc-aggregate-v1` требует ровно одно из `ip_address`, `url_match`,
    `host_match`, `hash`, применяет строгие structural validators, сводит дубли
    по последнему непустому `name` и не маршрутизирует в четыре legacy artifacts.

Promotion planning проверяет всю доставку до canonical mutation. Разные primary
groups могут делить новый related key после workspace coalescing. Все такие
группы отклоняются с `IMPORT.DUPLICATE_NEW_TARGET`; их ветви/provenance не
применяются частично. `ACCEPT_VALID` сохраняет независимые valid rows, а
`REJECT_DELIVERY` записывает отклонённую доставку без бизнес-мутаций.

Requested slot найденного survivor обязан ссылаться на его стабильное
назначение. При первом seed исходные назначения берутся из canonical public IDs.
Если реестр уже есть, active lifecycle без назначения отвергается с
`IMPORT.EXISTING_SLOT_UNASSIGNED` при любой existing-slot policy. Следующий
допустимый export может выдать слот; нельзя подставлять canonical ID вместо
уже инициализированного export namespace.

Slot requests и receipt resolutions хранятся в SQL той же promotion transaction.
Ограниченные batches подготавливают весь набор до validation/allocation,
сохраняя входной порядок и глобальные sparse-slot preferences. Planning/slot
scratch — TEMP tables операции, удаляемые до возврата connection в пул.
Immutable SQLite attachment доверяет сервисному владению sealed bytes;
`immutable=1` сам по себе не запрещает другому владельцу filesystem заменить их.

## Отказы и восстановление

Сохранённый путь — `DETECTED -> CLAIMING -> CLAIMED -> SNAPSHOT_PINNED ->
CONTRACT_PINNED -> STAGING -> STAGED -> PROMOTING -> CANONICAL_COMMITTED ->
FINALIZING -> TERMINAL`. У `TERMINAL` неизменяемый outcome: `SUCCEEDED`,
`COMPLETED_WITH_ERRORS` или `REJECTED`. Rejection — итог, а не команда переоткрыть
или переставить lane. Повторяемая глобальная голова может задержать promotion
других sources, хотя их readiness/listing остаются независимыми.

| Ситуация | Поведение | Основание recovery |
|---|---|---|
| Source меняется до claim, открытый producer запрещает delete sharing или destination существует | Защитный отказ и неблокирующий retry | candidate evidence и delivery ledger |
| Disconnect после SMB rename | Принять processing object по delivery token и продолжить materialization | remote private namespace и delivery ID ledger |
| Disconnect/мутация при download | Удалить `.part`; не публиковать snapshot | remote object evidence и следующая попытка |
| Неверные bytes, dialect/header mismatch, ноль/несколько recognition matches или hard parser limit | Отклонить всю доставку до canonical promotion | pinned snapshot и catalog |
| Invalid logical row | Хранить только безопасные row/code evidence; применить row-failure policy | sealed stage |
| Crash до dataframe commit | Продолжить/перестроить по последнему service checkpoint | ledger, snapshot, sealed stage |
| Crash после dataframe commit | Прочитать `import_commit`, завершить без повторной мутации | dataframe receipt |
| Crash после delivery reservation до registration link | Продолжить то же occurrence и rank; не выделять из недоказанной legacy row | service reservation marker и dataframe registration |
| Потеря события/notification | Следующий ограниченный full listing или durable-head reconcile | periodic backstops |
| Нет private SMB namespace или детерминированно неверные permissions | Закрыть этот source до listing/claim, сообщить incompatible readiness | positive capability probe и bounded reprobe |
| Временный отказ SMB capability или terminal retention | Сохранить intake/cleanup authority закрытой для source и повторить | source readiness либо terminal ledger row |
| Replay finalization/retention | Пропустить transport disposition и purge source remnant | явный source occurrence kind и retained local terminal unit |

Противоречивые durable evidence закрывают startup recovery barrier. Ordinary
ingest и managed import остаются закрытыми до их recovery и lifecycle admission.

## Операторские поверхности

- `ioc import validate` использует реальные recognizer/parser/mapping rules без
  source claim, сведения duplicate groups или canonical promotion. Он не делает
  live canonical matching, merge/slot planning или commit; принятие строки
  advisory. Полный CLI startup всё же может инициализировать/мигрировать storage
  и создать workspace directories. Это не filesystem-level dry run.
- `ioc import status` показывает aggregate backlog и безопасные head retry facts;
  он не пропускает, не переставляет и не завершает доставку принудительно. Он
  читает local service DB. Recovery-complete flag принадлежит query process;
  для recovery state работающего daemon используйте его actuator health.
  Текущий дефект nullable retry-deadline reader [OPS-10](../../KNOWN-ISSUES.md)
  может обрушить query и import health reader на nonterminal head; это не commit evidence.
- `ioc import replay` создаёт новую occurrence с причинной ссылкой на retained
  terminal evidence. Он использует активные source/catalog, а не старое поведение
  контракта; успешный CLI return подтверждает admission, не итоговый canonical
  commit. Защищённые локальные terminal source bytes должны ещё сохраняться.
- Actuator health различает failed recovery, безопасную деградацию retry/backlog,
  временную недоступность source, несовместимую capability и обычное продвижение.
  Source details агрегированы и value-free; metrics имеют низкую cardinality.
- Общий `observationRegistration` показывает counts неразрешённых ranks.
  Terminal import references защищены, пока delivery ledger не terminal и не
  истекли все canonical provenance/receipt references.

## Расширение

- Добавлять форму CSV в compiled catalog и зарегистрированные key/transform
  families; не выводить структуру из имени файла.
- Новый wire format размещать в parser adapter с сохранением strict decode,
  streaming и immutable snapshot boundary.
- Новый транспорт реализует source lifecycle и необязательные doorbell ports.
  Он обязан доказать ownership, stable materialization, idempotent disposition
  и recovery по полному listing.
- Расширять processed через `ProcessedImportRowPreparer`; application port
  остаётся framework-free, новые carrier/provider semantics проверяются против
  обычного pipeline до включения контракта.

## Источники истины

- Решение/инварианты: [ADR-0024](../../ADR/0024-managed-dataframe-import.md).
- Application contracts: `application.dataframeimport` и
  `application.port.*.dataframeimport` в `ioc-application`.
- CSV boundary: module/package READMEs `adapter-csv`.
- Durable state/promotion: README `adapter-store-jdbc`, ledger TCK и canonical
  import integration tests.
- Local ownership: import lifecycle classes `adapter-ingest`.
- SMB ownership: import lifecycle/live contract tests `adapter-transport-smb`.
- Runtime configuration: `IocProperties.DataframeImport`,
  `DataframeImportConfiguration`, `DataframeImportRuntimeConfiguration`.

- Exact recognition/presence: [ImportHeaderPlan](../../../core/ioc-application/src/main/java/com/iocextractor/application/dataframeimport/mapping/ImportHeaderPlan.java), [DataframeImportRowMapper](../../../core/ioc-application/src/main/java/com/iocextractor/application/dataframeimport/mapping/DataframeImportRowMapper.java).
- Preview boundary: [DataframeImportValidationService](../../../core/ioc-application/src/main/java/com/iocextractor/application/dataframeimport/DataframeImportValidationService.java), [runtime composition](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/DataframeImportRuntimeConfiguration.java).
- Transaction/replay evidence: [JdbcCanonicalImportWriterContractIT](../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriterContractIT.java), [RouterSelectedImportDeliveryIT](../../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/RouterSelectedImportDeliveryIT.java).
- Защита local source/report: [LocalManagedImportSourceLifecycleIT](../../../adapters/adapter-ingest/src/test/java/com/iocextractor/adapter/in/ingest/LocalManagedImportSourceLifecycleIT.java), [LocalImportTerminalStoreIT](../../../adapters/adapter-ingest/src/test/java/com/iocextractor/adapter/in/ingest/LocalImportTerminalStoreIT.java).

## Когда обновлять документ

Обновлять при изменениях recognition, presence/authority, routing, identity/merge,
порядка, canonical commit, retained evidence, source ownership или CLI/recovery
boundaries. Внешнее поведение SMB квалифицируется на целевом server family;
offline adapter tests и неисполненный external suite не доказывают live support.

## Связанные документы

- [storage.md](storage.md) — роли canonical/service DB и миграции.
- [processing.md](processing.md) — обычная обработка и reusable preparation.
- [ingestion.md](../ingestion.md) — владение/recovery обычного whole-file ingest.
- [event-coordination.md](../event-coordination.md) — события как hints.
- [Операторский guide](../../guides/ru/dataframe-import.md) — настройка, intake,
  status, replay и инциденты.
- [IOC aggregate guide](../../guides/ru/ioc-aggregate.md) — поставляемый контракт,
  empty-start activation и согласованный rollback.
- [ADR-0015](../../ADR/0015-retire-legacy-csv-lookup-storage.md) — сохранение отказа
  от legacy CSV lookup/seed.
