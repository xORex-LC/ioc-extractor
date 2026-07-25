# Инжест файлов в daemon-режиме

Документ описывает устойчивый путь от файла в локальном inbox до canonical
SQLite и CSV-проекции. Точная конфигурация находится в `application.yml`, а
состав портов и классов — в co-located `README.md` соответствующих пакетов и
модулей.

## Поток выполнения

```text
inbox
  -> periodic directory scan (supported)
     / optional WatchService event path
  -> include/exclude glob filter
  -> quiet-period stability check
  -> content hash
  -> atomic claim into processing
  -> IngestionService
       -> extraction pipeline
       -> failure-policy checkpoint
       -> canonical SQLite commit
       -> CSV projection
       -> run completion
  -> archive into done

post-claim terminal failure -> failed + error sidecar
pre-claim terminal failure  -> may remain in inbox (ING-13)
```

Spring Integration является только driving adapter в `adapter-ingest`.
Application-слой принимает уже обнаруженную единицу через
`IngestSourceUseCase`; он не зависит от Spring Integration или файлового
poller-а.

Daemon использует синхронный Spring Integration channel. Обработка следующего
сообщения не начинается параллельно в скрытом executor-е. Свойство
`ioc.ingestion.concurrency` сейчас связывается конфигурацией, но является
зарезервированным seam и не задаёт фактический параллелизм.

## Границы ответственности

- `adapter-ingest` обнаруживает и стабилизирует файлы, вычисляет fingerprint,
  управляет каталогами `inbox/processing/done/failed` и вызывает use case.
- `ioc-application` владеет orchestration, ingestion ledger и write→project
  run-saga.
- `adapter-store-jdbc` реализует durable ledgers и canonical repository.
- `adapter-sink-csv` готовит строки и строит CSV-проекцию из canonical truth.
- bootstrap выбирает daemon wiring и запускает recovery/retention/schedulers.

Инжест не владеет правилами извлечения, схемой хранения или export slices: он
координирует эти capability через порты.

## Инварианты корректности

1. **SQLite — источник истины.** CSV после commit является восстанавливаемой
   проекцией, а не отдельной системой записи.
2. **Polling — production correctness-path и default.** При
   `use-watch-service=false` каждый detection cycle полностью сканирует inbox.
   WatchService остаётся opt-in latency optimization для локальной filesystem:
   matching-файл, отклонённый только из-за quiet period, возвращается в
   retry-set через `DiscardAwareFileListFilter` и проверяется следующим poll.
   Это закрывает `ING-14`, но не превращает delivery событий ОС в полный
   directory rescan; на network/unreliable filesystem используйте polling.
3. **Файл должен стабилизироваться до claim.** Quiet period защищает от чтения
   во время записи; producer-side `*.part` + atomic rename остаётся лучшим
   входным контрактом.
4. **Идентичность whole-file — SHA-256 содержимого.** Путь/размер/mtime
   используются только как terminal identity, если файл невозможно прочитать.
5. **Claim предшествует обработке.** Атомарное перемещение в `processing`
   исключает штатную двойную обработку одним процессом.
6. **Повтор безопасен на уровне данных.** Canonical write использует keep-first
   `row_key`, а повторная проекция строится из БД целиком.
7. **Failure policy проверяется до durable write.** Ошибочные результаты не
   должны частично попасть в canonical storage из-за решения политики.
8. **Событие изменения canonical данных публикуется только после завершения
   durable run.** Оно ускоряет export, но periodic scheduler остаётся backstop.

## Durable состояния и recovery

Ingestion ledger хранит terminal lifecycle источника:

```text
CLAIMED -> SOURCE_ARCHIVED
      \-> FAILED
```

Run ledger отдельно фиксирует write→project saga:

```text
STARTED -> DB_COMMITTED -> PROJECTION_COMPLETED -> COMPLETED
       \------------------------------------------> FAILED
```

На старте recovery действует по durable состоянию, а не по одному наличию
файла:

- незавершённый `CLAIMED` источник проходит полный идемпотентный replay;
- `DB_COMMITTED` доводится вперёд повторной CSV-проекцией;
- orphan в `processing`, для которого нет ledger-записи, изолируется как
  failure, а не молча считается обработанным;
- завершённые `SOURCE_ARCHIVED` и `FAILED` не запускаются заново.

Это at-least-once orchestration с идемпотентными durable шагами, а не обещание
распределённого exactly-once.

## Ошибки, retry и lifecycle

Retry чтения, hashing и обработки реализован явно в file message handler с
bounded backoff. Spring Retry не является частью текущего контракта. После
успешного claim исчерпание попыток перемещает источник в `failed`; сбой до claim
может оставить его в `inbox` из-за ING-13. В обоих случаях ledger получает
terminal состояние, а причина сохраняется без утечки исходного IOC в INFO/WARN
логи. Поддерживаемого requeue/clear use case пока нет.

Retention ограничивает рост рабочих каталогов по времени/количеству. Она не
должна удалять источник, который всё ещё нужен recovery. Health отражает
готовность poller-а, состояние recovery и durable backlog; точные компоненты и
поля следует проверять по bootstrap health wiring.

Сейчас известны три lifecycle seam-а, которые нельзя скрывать документацией:

- **ING-10:** startup recovery и poller ещё не разделены строгим lifecycle
  barrier;
- **ING-11:** retry после частичного run не имеет полноценного resume protocol;
- **ING-13:** fate файла при сбое до durable claim закрыта временным
  durable-once механизмом, но не окончательным протоколом.

Актуальный scope и критерии закрытия этих долгов находятся в
[KNOWN-ISSUES.md](../KNOWN-ISSUES.md).

## Как расширять

- Новый способ обнаружения файлов добавляется в driving adapter и всё равно
  вызывает `IngestSourceUseCase`.
- Новый lifecycle/storage backend реализует существующие application-порты; не
  переносит Spring или JDBC в core.
- Tail/streaming source требует отдельной checkpoint identity
  (`file identity + offset + rotation marker`) и не должен притворяться
  whole-file content hash flow.
- Параллелизм вводится только вместе с явной моделью ordering, admission,
  ledger claims и SQLite contention. Одного включения executor-а недостаточно.
- Новый post-commit consumer должен опираться на durable state и иметь
  reconcile/backstop, если fast-path может потеряться.

## Источники истины

- Runtime flow и filters:
  `adapters/adapter-ingest/src/main/java/com/iocextractor/adapter/ingest/`.
- Orchestration/recovery contract:
  `core/ioc-application/src/main/java/com/iocextractor/application/ingest/README.md`.
- Composition/lifecycle:
  `bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/`.
- Defaults and validation:
  `bootstrap/ioc-app/src/main/resources/application.yml` и `IocProperties`.
- Open lifecycle seams: [KNOWN-ISSUES.md](../KNOWN-ISSUES.md).

## Когда обновлять документ

Обновите его при изменении file lifecycle, ledger/run states, retry/recovery,
stability/claim semantics, daemon ordering или границы driving adapter-а. Новый
класс или переименование метода сами по себе обновления не требуют.

## Связанные документы

- [processing.md](processing.md) — extraction и policy checkpoint.
- [storage.md](storage.md) — canonical write и projection semantics.
- [artifact-export.md](artifact-export.md) — export после canonical change.
- [event-coordination.md](event-coordination.md) — ingest→export fast-path.
- [ADR-0001](../ADR/0001-streaming-ingestion.md) — исходное решение daemon ingest.
