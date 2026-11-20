# Каноническое хранилище SQLite

[English version](../storage.md)

Хранилище владеет бизнес-истиной, сохранёнными фактами координации, эволюцией
схем и границами локальных транзакций. Application ports определяют контракты;
`adapter-store-jdbc` реализует их с SQLite, Spring JDBC и HikariCP. Bootstrap
собирает datasources и управляет завершением. Хранилище не выбирает правила
обработки, CSV mapping, транспорт или частоту планирования.

## Поток выполнения

```text
owned document workspace / sealed import workspace
  -> validate prepared rows and final keys
  -> reserve non-reusable IDs
  -> Native canonical transaction
  -> durable receipt and projection work
  -> mutable CSV / immutable export snapshot
  -> service-ledger finalization and delivery
```

Обычное подтверждение документа фиксирует **по одному артефакту**. Общего rollback
для всех артефактов запуска extraction нет. Managed import фиксирует все
принятые ветви и receipt доставки в **одной dataframe transaction**. Оба пути
не выдают незафиксированных строк. Production backend — Native SQLite;
альтернативного publication backend или автоматического execution dispatcher
нет. Решение: [ADR 0042](../../ADR/0042-native-sqlite-production-storage.md).

## Границы владения

| Хранилище | Полномочия |
|---|---|
| Dataframe DB | Публичные строки, record/match identities, aliases, provenance, упорядоченные field origins, lifecycle/history, канонические receipts, ID allocators, generations проекций и переиспользуемые export slots |
| Service DB | Журналы ingest/run, допуска документов, import delivery, export progress, remote fetch и publish |
| Private document workspace | Неизменяемая копия source, декодированный/refanged текст, extraction scratch и запечатанные повторно читаемые подготовленные строки |
| Private import workspace | Зафиксированный CSV snapshot, логические строки, трёхсоставные ячейки, ветви, ключи, предупреждения и отказы |
| CSV и immutable slices | Сгенерированные проекции и snapshots доставки; не вход runtime lookup |

Service и dataframe DB не образуют общую транзакцию. Application recovery
согласует их локальные сохранённые checkpoints. Перенос полномочий канонического
receipt, lifecycle или slot в сервисную БД нарушил бы этот протокол. Private
preparation и managed-import staging остаются частью Native; они не являются
версионируемой канонической публикацией или ещё одним storage backend.

## Инварианты

1. **SQLite — источник истины.** Oneshot накапливает те же канонические данные,
   что и daemon. Удаление CSV не удаляет каноническую запись.
2. **Идентичность вычисляется после подготовки.** Версионируемые record keys
   используют итоговые публичные поля; именованные match keys дают
   альтернативные aliases только для активных записей. Поиск по digest также
   обязан сравнить полный канонический материал. Provenance и приоритет
   изменяемых полей отделены от идентичности.
3. **ID имеют разные контракты.** Совместимые записи сохраняют возрастающее
   выделение ID с учётом схемы. Lifecycle writes резервируют канонические ID и
   lifecycle IDs до мутации; неудачные диапазоны сгорают. Immutable export
   использует переиспользуемые разреженные слоты в `(profile, artifact)`, а не
   канонические ID. Пробелы ожидаемы: резерв покрывает худший случай, включая
   кандидатов, которые лишь продлят существующие строки. Верхняя граница ID
   не является количеством строк.
4. **Checkpoint предшествует эффектам.** Отклонённая подготовка не резервирует
   ID и не меняет бизнес-строки. Lifecycle writer отвергает повторные итоговые
   ключи до резервирования, включая mapping collisions сохранённых наблюдений.
5. **Одна транзакция использует одно effective UTC.** Native получает write
   ownership до чтения lifecycle time. Все активные запросы применяют
   `_valid_until_epoch_ms > asOf`; равенство означает истечение. Это принятый
   контракт Native, а не новое чтение часов в момент commit.
6. **Revision и generation проекции различаются.** Публичная вставка или
   объявленное изменение публичных полей увеличивает revision; изменение
   только origin/provenance и TTL renewal — нет. Истечение создаёт работу
   mutable-проекции без нового immutable slice, обусловленного вставкой.
7. **Полнота receipt структурна.** Document receipt становится COMPLETE лишь
   после совпадения всех ожидаемых artifact markers, включая пустые артефакты,
   и всех типизированных итогов строк. Для reuse нужны совпадающие source/policy
   pins и допустимая retention. Import receipts владеют идемпотентностью доставки.
8. **Изменение схемы закрывается при несовместимости.** Reconciliation публичной
   схемы аддитивна; разрушительные изменения и type drift требуют явной миграции.
   Identity backfill проверяет все ожидающие артефакты на collisions до
   изменения сохранённых строк.

## Native mutation и владение ресурсами

`JdbcCanonicalMatchPlanner` использует индексированный поиск одного полного
ключа либо ограниченную сессию matching с предварительными запросами. Совпадают
только активные lifecycle; сохранённая история не может оживить запись. TEMP
requests и prepared statements принадлежат транзакции, а не общему кэшу.
Mutation session обрабатывает insert, renewal, restart, update, clear и no-op
через одну политику. Обычное подтверждение держит одну сессию на артефакт; импорт
— по одной на затронутый артефакт. Каждая сессия владеет максимум 32 statements,
сбрасывает bindings и закрывается до commit. Writer владеет connection,
резервированиями, агрегацией revisions и публикацией receipt.

Неуспешная транзакция откатывается до восстановления auto-commit, в том числе
при `Error` в cursor или mutation. Ошибки cleanup добавляются как suppressed к
исходному отказу. Восстановление connection не может зафиксировать остаточную
работу. Отказ после явного commit не отменяет его: сохранённый receipt остаётся
основанием replay, даже если private source уже недоступен.

Import planning отвергает collisions новых related keys между разными primary
groups до мутации и применяет logical-row failure policy к полному fan-out.
Запросы preferred slots и результаты остаются в SQL scratch конкретного
connection, с ограниченными JDBC batches и cursor reads. Полный набор запросов
проверяется до выделения; независимая обработка chunks изменила бы выбор слотов.
TEMP tables операции удаляются на выходе с явным указанием схемы `temp`, чтобы
не удалить одноимённые таблицы `main`. Размещение scratch по-прежнему зависит
от SQLite tuning preset; SQL-backed requests убирают Java-списки размера всей
доставки, но не задают общий предел RSS. Восстановление connection не сохраняет
TEMP objects между операциями; смена `temp_store` также уничтожает их.

Writer admission различает control, promotion, expiry, export-slot и maintenance
с ограниченной справедливостью. Оно упорядочивает ожидание, но не прерывает уже
работающую SQLite transaction. Огромная atomic promotion поэтому может
задержать controls; увеличение пула не создаёт несколько SQLite writers.
Области замеров и наблюдавшиеся ограничения описывает
[Native capacity](../native-capacity.md).

Document factory допускает один lease с учётом совокупной памяти, дисковых
резервирований корня и квот workspace. Она фиксирует source bytes и policy
identity, использует file leases и отвергает symlinks и чужие workspace roots.
Неактивные pins удаляются retention; действующий lease удалить нельзя.
Декодированный/refanged текст использует дисковые страницы, source scratch
удаляется до sealing. Общий дисковый reducer сохраняет полную итоговую
идентичность, порядок первых ключей и выбор целой строки-победителя по всем
артефактам. Повторно читаемые cursors подают строки writers и receipts без
списка победителей всего документа.
Одна factory владеет workspace root внутри JVM. Memory charge lease — оценка
для допуска, а не гарантия heap/RSS. Корректный бюджет допускает хотя бы один
workspace; неверные аргументы открытия отвергаются до получения permit.
Четверть дискового бюджета workspace доступна для source snapshot. Незапечатанную
подготовку можно отбросить; sealed pins остаются для replay.

Декодирование строк проверяет версионируемый ограниченный payload до выделения.
ASCII fields обходятся без временных decoder/buffer copies на поле; non-ASCII
использует один строгий UTF-8 decoder на строку. Сохраняются ABSENT, NULL,
позиции полей и отклонение повреждённого входа. Receipt batches сбрасываются
после 128 строк или 1 MiB оценённых bindings; отдельный больший binding
сбрасывается самостоятельно. До резервирования ID проверка повторных ключей
использует дисковый TEMP index с кэшем 64 KiB и пределом 65,536 страниц
(256 MiB при странице 4 KiB).
Receipt replay при необходимости использует connection владельца, избегая
вложенного получения из пула даже при одном connection. Два упорядоченных cursor
соединяют receipt rows и field positions в одном snapshot; отсутствующие,
повторные или осиротевшие positions приводят к отказу без удержания всего
receipt в памяти.

## Lifecycle, проекции и экспорт

Активация lifecycle явная и однонаправленная. `disabled` сохраняет совместимое
поведение. `fixed + existing-records: expire` закрывает legacy records
ограниченными keyset batches, сохраняет history и назначает работу проекций;
intake/export остаются за readiness до окончания activation. Upgrade не
продлевает существующие строки молча.

Native confirmation атомарно фиксирует публичные строки, aliases, provenance,
field origins, observation markers, revisions, работу проекций и типизированный
receipt payload своего артефакта. Живое наблюдение продлевает ту же lifecycle;
истёкшее архивирует/удаляет старую строку и создаёт новую непереиспользуемую
идентичность. Safe UTC имеет сохранённую неубывающую верхнюю границу: малое
смещение ограничивается ею, существенный/длительный откат приводит к отказу.

Истечение использует одно неизменяемое время цикла и индексированные ограниченные
archive/delete pages. History, source summaries и complete receipts имеют
независимую retention. Пятисекундный nearest-deadline backstop только читает,
когда срок не наступил. У retention и projection отдельные backstops; успешные
пустые проверки молчат. На каждый IOC не выделяется job или timer.

Streaming mutable CSV читает строки и generation в одном snapshot, пишет
соседний временный файл и устанавливает его через `ATOMIC_MOVE`. Application
сериализует установку/acknowledgement отдельно по артефактам. Acknowledgement
продвигает только установленный охват; новая работа остаётся ожидающей.
Построение CSV и snapshot reader не удерживают canonical writer admission.
Чтение safe time заканчивается до открытия read lease.

Immutable export согласует reusable slots под writer admission, затем
фиксирует один read snapshot для coverage и всех строк артефактов. Выжившие
сохраняют слоты; слоты исчезнувших lifecycle становятся объединяемыми свободными
диапазонами; новые lifecycle берут минимальные положительные пробелы. Preferred
import slots используют тот же реестр и транзакцию импорта. Проверка generation
обнаруживает гонки между reconciliation и открытием snapshot. Артефакты без ID
не выделяют слотов. См. [экспорт артефактов](artifact-export.md) и
[канонический lifecycle](canonical-record-lifecycle.md).

## Схема и восстановление

Версионируемые миграции используют `user_version`, после них согласуется
настроенная схема артефактов. Поддерживаются текущие Native dataframe format 12
и service format 14. Обычный migrator отвергает новые/экспериментальные форматы;
он не переосмысляет их и не снижает версию.

Цепочка миграций сохраняет границы владения: dataframe v4 — lifecycle/history/
receipts, v5/v8 — slots и разреженные свободные диапазоны, v6 — checkpoint
ограниченной reconciliation, v7 — named match identities, v9 — import receipts,
v10 — упорядоченные registrations/field origins, v11 — ordered receipt
positions/origin history, v12 — принятые import warnings. Service v9 — managed
deliveries, v10 — document admission, v11 — import observation reservations,
v12 — document-policy pins, v13 — export-profile single flight, v14 — bounded
document execution. Document/import retry сохраняет исходный rank;
нерезервированный legacy import не может молча получить новый rank после restart.

| Граница отказа | Результат и восстановление |
|---|---|
| Schema/identity drift | Startup прекращается до бизнес-мутации; исправить конфигурацию или выполнить явную миграцию |
| Каноническая транзакция | Откатить транзакцию своего артефакта либо всю import transaction; независимо зарезервированные ID остаются сгоревшими |
| Commit до проекции/finalization | Каноническая истина сохраняется; журналы run/delivery восстанавливаются по каноническому receipt/checkpoint |
| Source/workspace удалён после commit | Достаточен совместимый complete receipt; не применять доставку повторно |
| Переименование проекции до acknowledgement | Требуемая generation остаётся ожидающей; startup/periodic recovery повторяет установку |
| Прерванное истечение | Зафиксированные страницы остаются закрытыми; reconcile продолжается из истины без оживления |
| Небезопасное UTC | Подтверждение отвергается; readiness отражает отказ часов без уменьшения high-water |
| Потерян/повреждён mutable CSV | Восстановить через владельца проекции; удаление уже подтверждённого файла само не создаёт ожидающую generation |
| Потерян/повреждён исторический immutable slice | Восстановить исходные проверенные байты; новый экспорт текущих строк не воссоздаёт тот же исторический snapshot или идентичность доставки |
| Незапечатанный import workspace | Не является доказательством promotion; перестраивать только по pinned snapshot и delivery checkpoint |

Rollback после эволюции схемы восстанавливает согласованные остановленные backups
обеих БД, owned files, configuration и binary. Копирование только dataframe DB
или уменьшение `user_version` не поддерживается.

## Расширение и источники истины

Расширяйте application ports, не проводя SQL types внутрь. Новому артефакту
обычно нужны columns конфигурации, record/match keys и необязательная field policy;
reconciliation создаёт его типизированные storage families. Новому семейству БД
нужны adapter и те же port contracts, а не новый lifecycle или merge engine.

- Композиция: [AppConfig](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java).
- Native ordinary writer: [JdbcCanonicalLifecycleWriter](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalLifecycleWriter.java).
- Native import writer и контракт: [JdbcCanonicalImportWriter](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriter.java), [contract suite](../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriterContractIT.java).
- Привязка lifecycle TCK: [JdbcCanonicalRecordLifecycleContractIT](../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalRecordLifecycleContractIT.java).
- Владелец схем и отказ downgrade: [DataframeFormatMigrations](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/DataframeFormatMigrations.java), [ServiceSchemaMigrations](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/ServiceSchemaMigrations.java), [SqliteUserVersionSchemaMigratorIT](../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/SqliteUserVersionSchemaMigratorIT.java).
- Production Spring flow: [NativeDeliveryLifecycleIT](../../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/NativeDeliveryLifecycleIT.java).
- Module reference: [adapter-store-jdbc](../../../adapters/adapter-store-jdbc/README.md).

## Когда обновлять документ

Обновлять при изменении области commit, владельца identity/UTC, receipt recovery,
ресурсного владения, схем или видимости projection/slots.

Связанные возможности: [обработка](processing.md), [ingestion](../ingestion.md),
[импорт dataframe](../dataframe-import.md), [observability](../observability.md).
