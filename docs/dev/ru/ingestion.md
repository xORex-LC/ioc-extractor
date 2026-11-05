# Приём документов в daemon

[English version](../ingestion.md)

Возможность принимает стабилизированные файлы из локального inbox, записывает
данные в каноническую SQLite, восстанавливаемую CSV-проекцию и завершает обработку
исходного файла. Spring Integration — входной адаптер. Извлечение, маршрутизация,
правила lifecycle и полномочия storage остаются за портами application. См.
[processing.md](processing.md), [storage.md](storage.md) и
[ADR-0038](../../ADR/0038-durable-bounded-document-execution.md).

## Порядок выполнения

```text
periodic full listing -> include/exclude -> quiet-period stability
  -> durable observation reservation -> dataframe admission order
  -> atomic private token claim -> detector returns

bounded preparation workers:
  durable token -> private sealed source -> SHA-256 -> journal link
    -> read-only receipt lookup or extraction/routing -> sealed workspace

one promotion worker, oldest unresolved document first:
  prepared reference -> content-key guard -> receipt revalidation
    -> failure-policy checkpoint -> canonical commit -> projection
    -> source archive -> terminal CAS -> post-commit change hint
```

Детектор остаётся однопоточным (`ioc.ingestion.concurrency=1`). Он проверяет
метаданные, кратко фиксирует допуск и атомарно забирает файл. Хеширование,
копирование и извлечение не выполняются на потоке опроса. Диспетчер ставит в
очередь ссылки на задания/workspace, а не документы или коллекции IOC. Через
допуск проходит каждый документ daemon, в том числе артефакты только с KEEP_FIRST.

Подготовка использует ограниченный воспроизводимый workspace из
[processing.md](processing.md). Независимо готовить данные могут до двух потоков.
Workspace владеет декодированным/refanged текстом, индексированными совпадениями,
упорядоченными курсорами наблюдений и reducer. HTML освобождает обработанные
поддеревья, DOCX использует SAX; ограничения регионов парсера и полей отклоняют
подготовку до promotion. Окно диспетчеризации ограничено меньшим из настроенного
окна и общей вместимости фабрики workspace: поздние готовые задания не должны
занять все разрешения раньше самого старого. Для этих ограничений всё равно
нужно отдельно измерять память процесса целиком. Только самый старый незавершённый
допуск может перейти к promotion; быстро подготовленный новый документ не меняет
результат whole-row KEEP_FIRST. Приоритет изменяемых полей определяется сохранённым
рангом dataframe. Блокировка по ключу источника приобретается при promotion,
поэтому новый дубликат не удерживает её в ожидании старого документа. Подготовка
и promotion остаются отдельными операциями application.

## Допуск и давление на ресурсы

`ioc.ingestion.execution` задаёт число потоков подготовки, окно, число ожидающих
документов, суммарный размер исходных файлов и размер одного файла. Значения по
умолчанию: 2 потока, окно 4, 64 документа, 4 GiB суммарно и 512 MiB на файл.
Потоков не может быть больше двух или размера окна; окно не больше числа ожидающих
документов, а это число не больше 256. Размер одного источника не превышает лимит
закреплённого исходного файла workspace — четверть дисковой квоты workspace.

Это разные бюджеты: допуск ограничивает входные файлы во владении системы, окно —
число дескрипторов подготовки, а допуск workspace — общий кеш, резерв парсера,
память и диск. Потоковый протокол источника определён в
[ADR 0039](../../ADR/0039-streamed-document-source-processing.md).
Слишком большой файл или файл сверх текущего лимита остаётся в inbox. Полный опрос
обнаруживает его заново без AcceptOnce-фильтра. Уже захваченный файл не теряется
из-за отказа executor принять уведомление: восстанавливаемой очередью остаётся
журнал. Объединяемое уведомление ускоряет dispatch, а сканирование журнала каждую
секунду обеспечивает повторное обнаружение работы.

Полный опрос (`ioc.ingestion.detect.use-watch-service=false`) — основной режим по
умолчанию. Опциональный WatchService подходит только для надёжных локальных ФС:
файлы, отклонённые из-за quiet-period, повторяются, но периодического полного
обхода против потери событий ОС нет. Сканирование журнала диспетчером восстанавливает
уже допущенные задания; пропущенный сканером файл оно обнаружить не может.

Фильтр по умолчанию принимает `*.htm`, `*.html`, `*.docx`, исключая `*.tmp`,
`*.part` и скрытые имена. Фильтр quiet-period принимает файл, когда время его
изменения достаточно старое; равенства размера/mtime на нескольких обходах он
не требует. Пауза производителя может пройти проверку раньше завершения копии.
Передавайте файл под исключённым именем `*.part`, закрывайте запись и атомарно
переименовывайте в пределах ФС inbox. Изолированная копия защищает полученные
байты, но не определяет задуманный производителем конец документа.

## Идентичность и владение

Каждое поступление получает новый UUID `ObservationId`. Повторы и восстановление
сохраняют эту идентичность и ранг допуска. Идентичность содержимого — SHA-256
изолированного источника; путь, размер и mtime остаются метаданными.

Журнал допуска проходит `RESERVED -> ORDERED -> CLAIMED -> LINKED -> TERMINAL`.
Dataframe storage владеет выделением ранга; service DB или файловый журнал с fsync —
ссылкой для восстановления источника/задания. Атомарный захват token не заменяет
существующую цель и не откатывается к копированию/неатомарному переносу. Затем
`sealClaim` публикует приватный inode через fsync и atomic move: открытый дескриптор
производителя не меняет источник для хеширования и обработки. Хеш проверяется
до и после чтения. Рост файла сверх допуска отклоняется до обработки.

Ingest ledger проходит `ABSENT -> CLAIMED -> SOURCE_ARCHIVED|FAILED`. Терминальный
CAS монотонен: тот же исход идемпотентен, противоположный конфликтует. Run saga
проходит `STARTED -> DB_COMMITTED -> PROJECTION_COMPLETED -> COMPLETED`, а сбой до
commit помечается FAILED. Это разные автоматы: файловый ingest/admission journal
не заменяет JDBC run ledger, подключённый в daemon. Истина — SQLite; проекция
восстанавливается вперёд. Документ фиксируется по отдельным артефактам, а не одной
транзакцией на все артефакты. Поздний сбой может следовать за уже сохранёнными
изменениями и не означает откат всего документа. У managed import отдельный
контракт общей транзакции. Транзакции service journal и dataframe независимы;
завершение регистрации повторяется по сохранённому состоянию.

Обычное завершение и reconcile могут одновременно завершать один handshake.
Проиграв терминальный CAS, сервис перечитывает durable state и принимает только
то же поступление, данные источника/кандидата, закреплённую регистрацию, execution
state и терминальный исход с той же или новой версией. Уже завершённый handshake
регистрации может удовлетворять конкурентному terminal transition, незавершённый —
нет. Разные timestamps перехода сами по себе не создают конфликт этих фактов.
Ссылка на регистрацию dataframe проверяется и при повторе уже завершённого
поступления. Прочие CAS допуска сохраняют строгое сравнение; несовместимые исходы
или смена полномочий останавливают выполнение.

## Receipts и повторы

При активном fixed lifecycle поиск полного receipt по
`(source_key, processing-policy fingerprint)` только для чтения может избежать
подготовки. Сам поиск не продлевает записи. Promotion перепроверяет receipt по
эффективному времени и воспроизводит его типизированные строки через canonical
writer. Если receipt истёк или удалён, то же упорядоченное задание возвращается
к извлечению. Срок хранения по умолчанию — 30 дней. Изменение семантики
парсинга/маппинга вне конфигурации требует увеличения code-policy epoch fingerprint.

Попытки и retry-after сохраняются в выбранном журнале допуска: service schema v14
для JDBC, записи с fsync для файлового хранения. Временный сбой сохраняет приватный
источник и исходный observation/rank. Повторы используют ограниченную настроенную
задержку, не усыпляя poller. Когда достоверный ключ содержимого уже есть, исчерпание
попыток использует существующий механизм rejection/dead-letter. Каждая неудачная
попытка даёт типизированную диагностику с причиной; терминальное логирование только
наблюдает и не повторяет успешную каноническую операцию.

Timestamps прогресса допуска не предшествуют его сохранённому `updatedAt`.
Шаг часов назад не блокирует захват, повтор или завершение по receipt. Retry-after
остаётся абсолютным UTC-дедлайном. Это правило координации не разрешает canonical
publication: независимые безопасные часы отвергают небезопасный UTC.

При исчерпанном сбое до хеширования достоверного ключа нет. Допуск остаётся
заблокированным с собственным token (`retry_after_ms=Long.MAX_VALUE`), блокирует
последующие promotion и переводит capacity health в DOWN. Автоматического
requeue/clear API нет; оператор разбирает token/права и использует поддерживаемое
восстановление или согласованный restore. Startup не может молча пропустить
повреждённый token или придумать identity. Сбой до reservation может оставить
файл в inbox — ING-13 остаётся открыт. Ограничение частичного run resume ING-11
тоже открыто. См. [KNOWN-ISSUES.md](../../KNOWN-ISSUES.md).

## Запуск, восстановление и остановка

`CanonicalIntakeStartupCoordinator` — ApplicationRunner наивысшего приоритета.
Он держит intake закрытым до восстановления run saga, допусков документов,
обычных источников, lifecycle admission и managed imports. Затем запускаются
выполнение документов и обычный intake. Ошибка барьера останавливает оба пути,
проваливает readiness и один раз сообщает исходную диагностику. Сохраняется
прежний ранг; незавершённая legacy-работа без ранга при ordered field policy
требует drain/restore, а не придуманного нового ранга.

Диспетчер закрывает допуск, ограниченно ждёт coordinator/preparation/promotion
потоки и затем закрывает неподтверждённые дескрипторы. Отменённая подготовка
закрывает run/workspace, сохраняя захваченное поступление. Незавершившийся поток
даёт явный сбой shutdown: его живой дескриптор не уничтожается из-под него.
Периодическое чтение журнала возобновляет работу после рестарта. Это оркестрация
at-least-once с идемпотентными durable steps.

Фатальный `Error` рабочего потока останавливает intake/promotion scheduling,
освобождает готовые дескрипторы и прерывает остальные потоки. Уже активный promotion
владеет своим дескриптором до cleanup. Каждое независимое освобождение выполняется,
даже если другое выбросило `Error`; primary/suppressed ошибки остаются видимыми.
Допуски сохраняются для startup recovery. Сломанный объект dispatcher сам себя
не перезапускает; `dataProcessingCapacity` остаётся DOWN при остановленном dispatch,
даже если нет blocked admission. Это контракт остановки при фатальной ошибке,
а не обещание восстановления истощённой JVM без рестарта процесса.

Поддерживается один daemon на пространство inbox/БД. Несколько процессов требуют
отдельного lease/fencing-контракта. Managed CSV import имеет собственные intake,
delivery ledger, workspace и атомарный promotion; порядок документов не заменяет
порядок import.

## Операционные свидетельства

`ingestionLifecycle` имеет статус UP только после recovery и запуска intake.
`dataProcessingCapacity` показывает фазы документов по метаданным, ожидающие байты,
возраст старейшего, завершённые документы/строки/байты, saturation и blocked count.
Также доступны суммы/максимумы ожидания и удержания writer по классам операций,
активные/ожидающие snapshot readers, размер WAL и давление кеша workspace.
Health не двигает допуски и не перечисляет IOC-строки. Краткая ошибка содержит
тип исключения; подробная причина остаётся в диагностике.

Export/publish health показывает durable lag. Writer admission не вытесняет
работающую транзакцию: priority/aging действуют между транзакциями и не ограничивают
уже активный атомарный promotion. Долгие snapshot readers могут удерживать WAL
checkpoint, даже пока canonical writes продолжаются.

Терминальные `source_ingest` логи сохраняют run id, completion и счётчики severity;
COMPLETED_WITH_ERRORS имеет failure outcome. Receipt replay содержит
`ioc.ingest.disposition=duplicate`, не выдумывая extraction completion.
Startup recovery логирует начало и один терминальный исход с длительностью и
счётчиками. События ускоряют реакцию; ledgers и periodic reconcile обеспечивают
корректность.

## Границы проверки

`NativeDeliveryLifecycleIT` соединяет production Spring configuration и preflight
маршрутов с физической обработкой, canonical storage, typed receipts, CSV export,
fetch и publication ledgers. Подменены только SMB wire и часы. Он проверяет очистку
host/IP, итоговые ключи, происхождение выбранных строк, повтор после потерянного
ответа publication, receipt replay без исходного файла и новый lifecycle/export
slice при равенстве TTL, даже если байты CSV повторяются. Это не доказательство
работы живого SMB-сервера или допустимой нагрузки.

JDBC lifecycle tests отдельно проверяют откат expiry при сбое history write,
закреплённое чтение receipt во время удаления, некорректные ordered positions и
возврат соединения при сбое закрытия курсора. Admission/reference tests отвергают
изменённые file evidence, namespace и rank при повторе, сохраняя проверки версий
при finalization/cleanup. Managed-import delivery test соединяет YAML, processing,
canonical commit и receipt-only recovery; атомарность import не распространяется
на отдельные коммиты артефактов документа.

Живой Native smoke 2026-10-13 использовал обычный Maven application JAR
(`08f6c799c6025130c2082e0480e9067bea0132a4f35ce9ca3acf7e7688a5be65`),
диагностические helpers, временные service/dataframe DB и собственное пространство
SMB. Десять DOCX содержали 1 000 IOC; прежние и recovery fixtures были дополнительным
входом. Независимые проверки пяти артефактов, ключей, provenance, полных receipts,
mutable generations, export slots и удалённых manifest/CSV hashes прошли.
Итоговый canonical set содержал 2 490 artifact rows и 12 полных receipts.
Durable SMB completion занял 14,91 секунды от локальной передачи; завершение
с независимым удалённым чтением — 18,02 секунды. Это время smoke, не приёмка
ёмкости. Terminalization CAS conflicts и cleanup errors не было; пространство
удалено, частный процесс остановлен. Raw reports остаются локальными и не
являются артефактами репозитория.

Этот исторический smoke не квалифицирует текущий lifecycle harness;
[TOOL-10](../../KNOWN-ISSUES.md) фиксирует несовпадение имени архива. Для живого SMB
и ресурсов процесса целиком нужны отдельные свидетельства точного bundle; см.
[native capacity](../native-capacity.md).

## Расширение и сопровождение

Новым транспортам обнаружения нужны стабильные кандидаты, durable occurrence
identity и приватное владение. Streaming/tailing требует собственного checkpoint
с offset/rotation. Новые storage implementations реализуют внутренние порты;
Spring/JDBC остаются за пределами core. Параллельный promotion или разбиение
транзакции требует явного решения о видимости/порядке/восстановлении, а не только
настройки executor.

## Живые источники

- [Конфигурация ingest flow](../../../adapters/adapter-ingest/src/main/java/com/iocextractor/adapter/in/ingest/IngestFlowConfiguration.java)
  владеет detection wiring; [dispatcher](../../../adapters/adapter-ingest/src/main/java/com/iocextractor/adapter/in/ingest/DurableDocumentDispatcher.java) —
  ограниченной подготовкой, последовательным promotion и shutdown.
- [Admission service](../../../core/ioc-application/src/main/java/com/iocextractor/application/ingest/admission/DocumentAdmissionService.java)
  владеет durable occurrence transitions; [ingestion service](../../../core/ioc-application/src/main/java/com/iocextractor/application/ingest/IngestionService.java) —
  судьбой источника, receipt replay и run saga.
- [Конфигурация приложения](../../../bootstrap/ioc-app/src/main/resources/application.yml)
  владеет defaults; [композиция приложения](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java)
  подключает journals, storage и lifecycle support.
- [Тесты dispatcher](../../../adapters/adapter-ingest/src/test/java/com/iocextractor/adapter/in/ingest/FileSourceMessageHandlerIT.java)
  проверяют порядок, давление, durable retry, blocked pre-hash work и fail-stop cleanup.
  [Native delivery test](../../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/NativeDeliveryLifecycleIT.java)
  соединяет processing с canonical receipts и publication.

## Когда обновлять

Обновляйте документ при изменении claim/identity, порядка фаз, лимитов допуска,
повторов, shutdown, startup recovery или terminal semantics. Одновременно меняйте
перевод и затронутый [runbook daemon](../../guides/ru/daemon-operations.md).
Локальные ссылки — в [adapter-ingest](../../../adapters/adapter-ingest/README.md);
связанные возможности — [artifact export](artifact-export.md),
[event coordination](../event-coordination.md) и [managed import](dataframe-import.md).
