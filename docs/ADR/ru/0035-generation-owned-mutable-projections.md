> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0035-generation-owned-mutable-projections.md).

<a id="adr-0035-generation-owned-mutable-artifact-projections"></a>
# ADR 0035: Изменяемые проекции артефактов с владением по generation

Статус: принято, 2026-10-04. Уточняет ownership и acknowledgement изменяемой
проекции из [ADR 0020](../0020-canonical-record-expiration-lifecycle.md).

<a id="context"></a>
## Контекст

Прямой ingest и восстановление run устанавливали CSV независимо от lifecycle
convergence. Атомарная замена защищала целостность байтов, но позволяла старому
snapshot перезаписать более новый подтверждённый файл. CAS required generation
после замены не предотвращал такой порядок установки. Stream reader также
не возвращал evidence generation; convergence подтверждала наблюдавшееся до
сборки состояние работы, а не действительно установленный snapshot.

<a id="decision"></a>
## Решение

Один application service владеет каждым настроенным mutable artifact внутри
процесса. Все запросы oneshot, ingest, recovery, вызванной import convergence,
activation и expiry используют его. Bootstrap предоставляет как projection port
только владельца; непосредственный CSV installer приватен его созданию.

Справедливая прерываемая блокировка по artifact охватывает чтение snapshot,
построение temporary file, atomic installation и acknowledgement. Canonical
writer admission удерживается только существующими короткими DB-операциями,
включая acknowledgement; она никогда не охватывает построение CSV. Порядок
блокировок: artifact owner, затем DB admission. Canonical writer не вызывает
projection, удерживая admission.

Stream reader возвращает row count и covered generation из одной SQLite
read transaction. Acknowledgement монотонно продвигает projected coverage
до установленной generation, если она не превышает required work. Более новая
canonical mutation во время сборки остаётся pending. Это заменяет ограничение
exact required-generation CAS для mutable acknowledgement; canonical
transactions, receipts и coverage immutable slices не меняются.

Владелец сохраняет один успешный outcome на artifact. Ожидающие callers могут
использовать его, когда он покрывает последнюю durable generation; advisory
diagnostics сохраняют counts и получают correlation ID каждого caller.
Generation zero не отслеживается (lifecycle disabled) и всегда пересобирается
в той же границе сериализации.

Ошибка до замены сохраняет прежний target. Ошибка после замены, но до
acknowledgement оставляет durable work pending. Startup/periodic convergence
повторяет попытку из canonical truth, не требуя новой mutation. Владелец не
создаёт workers; callers владеют исполнением, прерываемым ожиданием и отменой.
CSV streaming проверяет interruption перед установкой и на каждой row.

<a id="consequences"></a>
## Последствия

- Разные artifacts могут продвигаться независимо. Внутри одного artifact
  одновременно выполняется только одна build/install/ack attempt; параллельные
  speculative builds отложены, пока измеренная выгода не оправдает более
  сложный installation fence.
- Mutable output paths и dataframe storage требуют одного service process.
  Это не distributed filesystem lock и не transactional filesystem/DB commit.
  Сбои процесса между rename и ack исправляются pending work.
- Внешнее удаление/повреждение подтверждённого output остаётся операционной
  задачей reprojection; одного durable coverage недостаточно для проверки
  целостности файла.
- Immutable slices сохраняют отдельных владельцев slots, manifest и recovery.
- Stale-install race, partial coverage, cancellation, fault windows и restart
  recovery требуют детерминированных regression tests.
