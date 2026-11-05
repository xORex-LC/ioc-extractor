# Неизменяемый экспорт артефактов

[English version](../artifact-export.md)

Экспорт формирует один проверяемый неизменяемый срез профиля из канонических
данных SQLite. Локальная доступность среза и удалённая доставка — отдельные факты:
publish обнаруживает только локально зафиксированные запуски `COMPLETED`.
Изменяемые generated CSV не служат входом экспорта.

## Поток выполнения

```text
IO-free profile validation at the CLI boundary
  -> canonical-data admission
  -> cross-process profile lease + incomplete-profile recovery
  -> revision/plan pre-gate
  -> export_run STARTED
  -> reconcile stable export slots at canonical generation G
  -> one consistent dataframe snapshot
  -> stream CSV + manifest into .staging
  -> durable _SUCCESS
  -> STAGED
  -> atomic staging-to-final rename
  -> AVAILABLE
  -> export_progress + COMPLETED
  -> SliceCompleted latency hint
```

`core/ioc-application` владеет планом, политикой изменений и сагой. JDBC даёт
snapshot и журналы; CSV создаёт срез в файловой системе; Jackson кодирует
manifest. Bootstrap собирает CLI, расписание daemon, recovery, health и retention.
CLI проверяет профиль до ленивого подключения хранилища. Допустимый экспорт с
доступом к состоянию проходит общий допуск к каноническим данным до recovery,
предварительной проверки или snapshot. Расписание daemon открывается после
допуска и стартового восстановления.

## Инварианты

1. **Профиль — упорядоченная неделимая единица.** Поддерживается режим
   `COMPLETE`; порядок артефактов и колонок значим. По умолчанию `ioc_aggregate`
   вынесен в отдельный профиль `ioc-aggregate`, сохраняя состав
   `reputation-lists` и `address-blacklist`. Атомарность относится к одному
   профилю, а не ко всем настроенным профилям или изменяемым CSV вместе.
2. **План определяет политику формирования байтов.** Схема и идентичность
   артефактов, выбранное отображение и раскладка CSV участвуют в хешах и
   отпечатках. Временные метки запуска и его ID не меняют идентичность плана.
   Исходная раскладка CRLF/ALL/ALL сохраняет manifest v1; другая раскладка
   использует v2 и меняет хеш плана. См. [формат CSV](csv-output-format.md).
3. **Один snapshot владеет охватом и строками.** Одна WAL read transaction
   фиксирует revisions и строки всех артефактов. При активном lifecycle общий
   `asOf` применяется ко всем предикатам активности; `valid_until == asOf`
   исключается. Изменения, зафиксированные после открытия этого snapshot,
   попадут в следующий экспорт. Граница фиксирована на операцию, а не
   пересчитывается для каждой строки.
4. **Локальная публикация атомарна.** CSV, manifest и `_SUCCESS` принудительно
   сохраняются до переименования staging в final. Проверяются точный состав,
   хеши и marker. Marker содержит SHA-256 manifest с завершающим LF; одного
   marker недостаточно для доказательства полноты среза. Отсутствие поддержки
   атомарного перемещения каталога приводит к отказу без копирующего fallback.
5. **Кодирование строгое.** Непредставимый символ прерывает формирование;
   замена символов нарушила бы контракт байтов и контрольных сумм. Частичный
   staging может остаться для проверки и recovery, но не является доступным
   финальным срезом.
6. **Обнаружение изменений двухступенчатое.** Упорядоченные revisions и
   `planHash` сначала определяют необходимость формирования. Полный candidate
   избыточен, только если план, байты каждого артефакта и охваченные revisions
   совпадают с сохранённым progress. Более новая revision требует нового среза
   даже при совпадении CSV. Revision может увеличиться при вставке, повторном
   появлении или поддерживаемом изменении публичных полей; это не обязательно
   новая lifecycle.
7. **Событие не является commit.** `SliceCompleted` следует за сохранённым
   `COMPLETED`. Потерянные подсказки восстанавливает publish reconcile. Ни
   событие, ни каталог `AVAILABLE` сами по себе не подтверждают удалённую доставку.
8. **Истечение и подтверждение только TTL не создают срезов.** Они не увеличивают
   revision публичных данных. Последующее изменение публичных данных или плана
   может допустить экспорт текущего активного состава. Ручной экспорт с прежними
   планом и revisions не принуждает создать snapshot только ради истечения.
9. **Внешний ID — слот экспорта с заданной областью.** При активном lifecycle
   артефакты с `id` используют стабильные переиспользуемые слоты в
   `(profile, artifact)`. Идентичности канонической строки и lifecycle отделены
   и непереиспользуемы. Артефакты без ID не участвуют в выделении; в режиме
   совместимости с отключённым lifecycle экспорт сохраняет канонические ID.

## Владение профилями и параллельность

[ADR-0037](../../ADR/0037-profile-scoped-export-execution.md) ограничивает
формирование и recovery одним профилем. Сервисная БД допускает один активный
запуск на профиль, а NIO operation lease исключает его одновременное исполнение
процессами с общим настроенным export root. Lease охватывает recovery и дешёвую
предварительную проверку. Это локальная файловая блокировка, а не разрешение
запускать несколько daemon с общими изменяемыми путями.

Стартовое recovery обходит незавершённые профили до открытия расписания.
Recovery перед формированием затрагивает только профиль под lease. CSV writer
хранит состояние отдельно для каждого вызова, не удерживая общий монитор на всё
время streaming. Daemon использует два worker и не более одной ожидающей или
исполняемой попытки на настроенный профиль; quiet/max-cap остаётся политикой
каждого профиля.

`ioc.storage.dataframe.pool.read-max` ограничивает snapshot transactions.
Транзакции часов и слотов не удерживают reader permit; для одновременного
продвижения двух профилей нужны хотя бы два reader slots. Длительные чтения
по-прежнему задерживают WAL checkpoint. Увеличение пула не создаёт несколько
SQLite writers. Перед обновлением остановите прежний процесс и сохраните
согласованные backups; старый binary может отвергать новую сервисную схему
даже при отключённом TTL.

## Стабильные переиспользуемые слоты экспорта

`SnapshotSliceReader` — application port целостного snapshot. При lifecycle
`ACTIVE` JDBC-реализация сначала выполняет короткую транзакцию в dataframe DB:

1. Захватывает write ownership, сериализуясь с подтверждением и истечением.
2. При первой инициализации реестра заполняет
   `(profile, artifact, lifecycle_id) -> slot` из активных канонических ID,
   сохраняя соответствие при обновлении. Обработка preferred import-slot тоже
   может инициализировать реестр до первого экспорта.
3. Освобождает исчезнувшие назначения, объединяет свободные диапазоны, назначает
   новым lifecycle минимальные положительные пробелы, затем выдаёт из `next_slot`.
4. Записывает `artifact_projection_state.required_generation` как
   `source_generation` реестра.

Отдельная read transaction затем проверяет generation и полноту назначений до
первого callback. Гонка между reconciliation и открытием snapshot повторяет
попытку не более трёх раз; постоянные изменения приводят к отказу, а не выдаче
смешанного отображения. Строки содержат `export_slot AS id` в порядке слотов.
Streaming поддерживают покрывающий индекс назначений и уникальный индекс
lifecycle. Manifest coverage `upperId` для такого артефакта означает максимальный
активный слот, а не канонический ID или верхнюю границу аллокатора.

Слоты находятся рядом с каноническими строками в `ioc-dataframe.db`, поэтому
запросы preferred import-slot могут участвовать в общей канонической транзакции.
Истечение и history не перенумеровывают выжившие слоты. Исторические срезы
сохраняют исходное соответствие; один номер в другом срезе может обозначать
другую lifecycle. Для диагностики нужны срез, профиль и артефакт вместе.

## Сохранённое состояние и восстановление

```text
STARTED -> STAGED -> AVAILABLE -> COMPLETED
   |          |          |
   +-------> FAILED <-----+
STARTED -> SKIPPED
```

Пропуск на предварительной проверке не создаёт строку run или срез и не
возвращает run ID. Избыточный сформированный candidate удаляется и получает
сохранённый `SKIPPED`, оставляя прежний опубликованный срез в progress. Оба
пути не испускают `SliceCompleted`.

Ошибка формирования может оставить последний активный checkpoint, обычно
`STARTED`; она не обязательно немедленно записывает `FAILED`. Следующее recovery
под владением проверяет ledger, staging/final files и manifest coverage, не
перечитывая текущие канонические строки:

| Свидетельство/checkpoint | Восстановление |
|---|---|
| `STARTED` с отсутствующим/частичным staging | Удалить неполный staging и записать `FAILED` |
| `STARTED` с корректным candidate | Повторить проверку избыточности; удалить как `SKIPPED` либо завершить marker и перейти в `STAGED` |
| `STAGED` с корректным staging или совпадающим final directory | Идемпотентно завершить атомарную доступность |
| `AVAILABLE` с корректным final directory | Восстановить progress из его manifest и завершить `COMPLETED` |
| Повреждённые, конфликтующие или несовместимые сохранённые данные | Записать `FAILED` с `EXPORT.RECOVERY_FAILED`; не перезаписывать свежим snapshot |
| Ошибка I/O/runtime во время recovery | Сообщить об отказе; checkpoint может остаться незавершённым для следующей попытки под владением |

Progress и `COMPLETED` фиксируются вместе в сервисной БД. Файловая система и БД
не образуют общую транзакцию; окна crash закрывают порядок checkpoints и
проверка файлов. Ошибка publisher в обычном пути может дойти до вызывающего кода
после завершения; recovery подавляет такой отказ доставки. Оба пути сохраняют
завершённый срез и опираются на publish reconcile. Ошибка команды поэтому не
доказывает отсутствие сохранённого среза.

## Расписание и retention

CLI выбирает настроенный профиль командой `ioc export --profile reputation-lists`.
Она не разбирает документ и не обходит проверку изменений. Daemon interval или
quiet-period/max-cap используют подсказки зафиксированных публичных изменений;
периодические проверки остаются backstop. Импорт испускает одну подсказку уровня
артефактов после всей транзакции, а не по одной на строку. Подтверждение только
TTL и import no-op не создают revision. Изменение aggregate `name` является
публичной мутацией; изменение только origin при прежнем `name` — нет.

Остановка scheduler отменяет новые проверки и завершает timer и profile workers.
Отказ остановки одного executor не пропускает другой; последующие ошибки
сохраняются как suppressed. Ссылки на executors остаются до подтверждённого
завершения, предотвращая небезопасный restart или повторное открытие
остановленного scheduler поздним admission.

Retention считает каждый final slice directory одной единицей и применяет
age/count отдельно по профилям. При включённом remote publish
`PublishLedgerSliceRetentionGuard` блокирует удаление, пока каждая настроенная
пара `slice × target` не получит ledger record со статусом `SUCCEEDED` или явно
`ABANDONED`. Отсутствующие записи и повторяемая работа удерживают срез. Guard
выполняется непосредственно перед удалением, поэтому `max-count` — best effort,
а не разрешение потерять недоставленные данные.

Не изменяйте завершённые срезы вручную и не подменяйте их изменяемыми CSV.
Потерянный исторический срез требует восстановления исходных проверенных байтов;
текущие данные могут создать новый срез, но не воспроизвести старую идентичность.

## Расширение и источники истины

Новый профиль использует включённые артефакты конфигурации и их identity contracts.
Новый режим вывода требует явной application/writer семантики; неизвестные режимы
не должны подменяться `COMPLETE`. Новый manifest codec размещается за
`SliceManifestCodec`. Удалённый транспорт принадлежит sync/publish.

- Модель/сага: [пакет export](../../../core/ioc-application/src/main/java/com/iocextractor/application/export/README.md), [ExportService](../../../core/ioc-application/src/main/java/com/iocextractor/application/export/ExportService.java).
- Проверки/recovery: [ExportChangeDetectorTest](../../../core/ioc-application/src/test/java/com/iocextractor/application/export/ExportChangeDetectorTest.java), [ExportRunRecoveryServiceTest](../../../core/ioc-application/src/test/java/com/iocextractor/application/export/ExportRunRecoveryServiceTest.java).
- Snapshot/слоты: [JdbcSnapshotSliceReader](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcSnapshotSliceReader.java), [ReusableExportSlotIntegrationIT](../../../bootstrap/ioc-app/src/test/java/com/iocextractor/ReusableExportSlotIntegrationIT.java).
- Протокол файловой системы: [CsvArtifactSliceWriterIT](../../../adapters/adapter-csv/src/test/java/com/iocextractor/adapter/out/sink/csv/CsvArtifactSliceWriterIT.java).
- Сборка/расписание: [AppConfig](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java), [DaemonExportSchedulerTest](../../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/DaemonExportSchedulerTest.java).
- Общая композиция: [OnDemandExportIntegrationIT](../../../bootstrap/ioc-app/src/test/java/com/iocextractor/OnDemandExportIntegrationIT.java).

## Когда обновлять документ

Обновлять при изменении переходов состояний, snapshot/атомарной доступности,
политики plan/hash, видимости слотов, recovery evidence, расписания или retention.

## Связанные документы

- [Хранилище](storage.md) — revisions и владелец транзакций.
- [Канонический lifecycle](canonical-record-lifecycle.md) — активный состав.
- [Sync](../sync.md) — доставка завершённых срезов.
- [Координация событий](../event-coordination.md) — подсказки и reconcile.
- [Импорт dataframe](../dataframe-import.md) — публичные мутации и запросы слотов.
- [ADR-0012](../../ADR/0012-streaming-dataframe-emission.md) — artifact emission.
- [ADR-0021](../../ADR/0021-stable-reusable-export-slots.md) — переиспользуемые слоты.
- [ADR-0022](../../ADR/0022-revision-significant-identical-export.md) — значимые revisions при одинаковых байтах.
