# Синхронизация с удалённым хранилищем

[English version](../sync.md)

Sync соединяет удалённое файловое хранилище с локальными возможностями, сохраняя
границы ответственности:

```text
remote source -> fetch -> local inbox -> ingestion -> canonical SQLite
canonical SQLite -> export slice -> publish -> remote target
```

Fetch не извлекает IOC, publish не формирует артефакты. `FileTransport` скрывает
удалённый I/O; текущая реализация SMB2/3 находится в `adapter-transport-smb`.
[Операторское руководство](../../guides/ru/remote-storage-sync.md) владеет настройкой
и подготовкой сервера. У managed import отдельные права владения и изменения.

## Поток fetch

```text
periodic/startup/CHANGE_NOTIFY hint
  -> detect: list + include/exclude + ledger/in-flight filtering
  -> RemoteChangeBatchDetected
  -> endpoint-keyed admission
  -> download into hidden .sync-staging/*.part
  -> force file + atomic move into inbox
  -> remote_fetch_ledger = FETCHED
```

Обнаружение отделено от выполнения. `RemoteSourceMonitor` получает список,
фильтрует и формирует ограниченные batches; событийный
`RemoteFetchService.fetch(FetchRemoteObjectsCommand)` не повторяет listing.
Fetch по CLI selection сам запрашивает список. Process-local in-flight claims
уменьшают повторные постановки; durable ledger переживает restart. Обычный fetch
читает удалённые источники, не перемещая и не удаляя их.

Текущая identity — `(path, size, modifiedAt)`, не хеш содержимого. Повторная
загрузка считается новой только при смене tuple. Source и endpoint не входят в
ключ: одинаковый tuple на двух endpoint может подавить второй fetch (`OPS-11`).
См. [известные проблемы](../../KNOWN-ISSUES.md). Имя endpoint в конфигурации не
создаёт отдельное пространство идемпотентности.

Fetch записывает staging bytes, принудительно сохраняет файл и атомарно переносит
его в inbox до отметки fetched в ledger. Сохранение каталога — best effort на
платформах, запрещающих directory channels. Crash после landing до закрытия ledger
может привести к ещё одной копии: это at-least-once transfer, не distributed
exactly-once. Дедупликация canonical rows не делает любое повторное наблюдение
операцией без последствий.

При уже занятом конечном имени обычно выбирается стабильный suffix identity.
Это не гарантия no-replace: проверка существования предшествует atomic move,
а параллельный endpoint или другой writer может занять имя между ними. На Linux
два успешных move могут оставить один источник (`OPS-12`). До устранения дефекта
используйте непересекающиеся имена производителей. Атомарная видимость сама по
себе не разделяет writers и не доказывает завершение записи. Производитель обязан
закрыть исключённый временный файл и переименовать до обнаружения; debounce не
заменяет этот контракт. Fetch не перепроверяет listed size/mtime вокруг download.

## Поток publish

Publish принимает только completed local slice, прошедший проверку `_SUCCESS`,
manifest decode, membership и hash/size в completed-slice catalog.

```text
completed slice x configured target
  -> publish-ledger discovery/retryable work
  -> endpoint-keyed admission
  -> upload non-marker files into a private remote staging directory
  -> upload _SUCCESS last within staging -> verify uploaded sizes
  -> rename staging to final slice directory -> verify final marker
  -> publish_ledger = SUCCEEDED
```

Ledger владеет сагой для `(slice_id, target_id)`:

```text
PENDING -> IN_PROGRESS -> SUCCEEDED
                   \-> FAILED -> IN_PROGRESS
PENDING|IN_PROGRESS|FAILED -> ABANDONED
```

`SUCCEEDED` и `ABANDONED` терминальны. Застрявшая `IN_PROGRESS` работа становится
доступной для повтора после recovery threshold; свежая попытка не перехватывается
немедленно. После crash совпадение remote marker с закреплённым manifest hash
может завершить ledger без новой отправки. Несовпадение даёт verification failure,
а не молчаливую перезапись. Recovery по совпавшему marker не скачивает и не хеширует
каждый remote CSV: получатель сам проверяет membership, размеры и хеши.

SMB adapter загружает файлы в случайный соседний `.tmp-…`, проверяет размеры и
переименовывает каталог в конечный. Получатели игнорируют staging и принимают
только final slice с совпавшим marker и независимо проверенными manifest/files.
Незавершённый final directory без marker может быть удалён перед повтором;
пространство publish должно принадлежать этому потоку. Доставленные remote slices
не очищаются как самостоятельная remote retention услуга.

Retention ждёт терминальную пару для каждого текущего target профиля. Отсутствие
discovery, pending, in-progress и failed работа закрепляют local slice;
`ABANDONED` снимает закрепление, не доказывая доставку. Удаление target меняет
обязательство retention и требует операционного решения. Count/age limits
выполняются best effort при наличии pins. Delivery veto подключён только при
включённом daemon sync/publish и JDBC service storage. Выключение
`ioc.sync.enabled` или `ioc.sync.publish.enabled` убирает veto: сохраните
unresolved slices до смены настройки, не полагаясь на прежние pins.
Публичного abandon/requeue CLI нет;
не создавайте терминальное состояние редактированием таблиц ledger.

## Координация и повторы

События и SMB `CHANGE_NOTIFY` ускоряют работу; durable state и periodic reconcile
обеспечивают корректность для подходящих объектов, доступных на момент reconcile.
Файл, созданный и удалённый между listing, не восстанавливается по уведомлению.
Durable event queue нет.

- Периодическое обнаружение повторно находит неполученные remote tuples.
- Publish reconcile находит недостающие slice/target pairs и повторяет durable work.
- Fast paths и daemon backstops передают endpoint work в один
  `KeyedSerialExecutor`; разные endpoint могут выполняться параллельно.
- Ограниченный admission может отбросить hint, не удаляя durable work. Detection
  работает single-flight по source с объединением triggers координатором.
- Общий SMB session pool тоже сериализует обычные операции по endpoint.
  Это process-local controls, не межпроцессный lease.

`Retrier` включает первый вызов в `max-attempts` и внутри цикла повторяет только
`TRANSIENT` (`RETRY_NOW`). `UNREACHABLE` и `RESOURCE_EXHAUSTED` используют
`RETRY_LATER`; authentication, permission, not-found и security-policy errors —
`FAIL`. Поздний плановый цикл может повторить unresolved work; permanent
классификация не исправляет credentials или конфигурацию. Durable failed publish
pair может дать DOWN, даже если причиной был временный transport failure.
У watch отдельный reconnect loop без lifetime-предела `max-attempts`.

## Граница конфигурации и транспорта

Credentials передаются через environment placeholders. Operational logs/health
используют логические идентификаторы и очищенные краткие ошибки; секретам endpoint
и raw remote IOC там не место. Strict preflight отвергает неизвестные ссылки до I/O.

Для SMB:

- `encryption=required` предлагает только SMB3 и проверяет effective session
  encryption после authentication до share I/O; нарушение даёт
  `SECURITY_POLICY_UNMET` без немедленного сетевого повтора.
- `preferred` запрашивает encryption, разрешая unencrypted SMB2/3 fallback;
  `disabled` не запрашивает client-preferred encryption.
- `port` по умолчанию `445` для обычных и выделенных watch sessions.
- `connect-timeout` ограничивает TCP connect, не DNS или весь sync cycle.
- `request-timeout` ограничивает один обычный SMB request, не idle notification wait.
- `idle-timeout` закрывает неиспользуемый cached transport client; у долгого watch
  отдельная session lease/reconnect policy.
- Неподдерживаемый legacy `read-timeout` получает migration hint. Временный rollback
  alias `encrypt` отображает true в `required`, false в `disabled`, даёт value-free
  warning и конфликтует с явно заданным `encryption` (ADR-0027).

Transport failures преобразуются в `RemoteErrorKind` и стабильные `SYNC.*`.
При наличии work record сначала сохраняется failure state, затем диагностика.
Raw numeric SMB capacity/resource NTSTATUS даёт `RESOURCE_EXHAUSTED` и
`SYNC.REMOTE_RESOURCE_EXHAUSTED`, включая отсутствующие в enum SMBJ значения.
Ошибка не сообщает суммарное server usage или configured headroom.

## Lifecycle, health и CLI

Daemon запускает fetch до export, publish после export и retention после publish
discovery. Точные номера фаз принадлежат bootstrap. Recovery и remote availability
различны: обычный sync не выполняет полный authentication/write/ACL probe только
ради зелёного startup health.

`sync` health объединяет durable publication backlog, наблюдения source/target,
keyed executor и watch. Часть наблюдений сбрасывается с процессом, ledgers — нет.
Reconnect grace и bounded shed-to-reconcile — degradation signals; failed durable
delivery и permanent outcome могут дать DOWN.

`smbTransport` показывает application-owned established connections/sessions/trees,
роли `pooled_transport`/`change_notify`, planned steady demand и capacity failures.
Capacity failure остаётся DEGRADED до подтверждённого успеха внутреннего owner;
bounded endpoint/role labels не раскрывают его. Это не server-wide accounting
и не capacity acceptance test.

CLI `sync fetch`, `sync publish`, `sync all` используют те же application contracts.
`sync all` проверяет обе selection, затем получает файлы и публикует существующие
slices; он не ждёт ingestion и не выполняет между ними export. Отдельная CLI JVM
не разделяет endpoint lanes и session pool daemon. Согласуйте ручные изменения
с daemon и другими операторами.

`--dry-run` исключает fetch landing, publish mutations и ledger transitions в use
cases. Fetch может читать remote metadata; publish проверяет local completed slices
и ledger, не тестируя remote write permissions. Полный Spring CLI startup может
создать/мигрировать service schema; это не sandbox без I/O. Исполняемые команды
и exit semantics приведены в операторском руководстве.

## Инварианты и ограничения

1. Landing делает полученные байты атомарно видимыми; `OPS-12` ограничивает collision safety.
2. Publish начинается с verified completed local slice.
3. Final visibility следует за upload/size verification staging; marker связывает manifest.
4. Durable transition важнее события или log observation.
5. Endpoint exclusion принадлежит процессу; разные endpoint работают параллельно.
6. Потерянные hints восстанавливаются по доступным source/catalog/ledger, не payload.
7. Sync не владеет extraction, canonical schema или export formation.
8. `encryption=required` запрещает share I/O без effective SMB3 encryption.

## Расширение

Новый протокол реализует `FileTransport` и опционально `RemoteChangeSignalSource`.
Watch получает только `RemoteWatchTarget(sourceId, endpoint, remotePath)`;
include/exclude остаются в application detection. Для remote delete/move/retention
нужно явное решение о владении. Новые targets используют slice/target semantics.
Cross-process fencing и durable message delivery требуют отдельного adapter/protocol
решения, а не скрытого расширения `platform-events`.

## Живые источники

- [Fetch](../../../core/ioc-application/src/main/java/com/iocextractor/application/sync/RemoteFetchService.java),
  [monitor](../../../core/ioc-application/src/main/java/com/iocextractor/application/sync/RemoteSourceMonitor.java)
  и [publish](../../../core/ioc-application/src/main/java/com/iocextractor/application/sync/ArtifactPublishService.java)
  владеют application behavior.
- [SMB adapter](../../../adapters/adapter-transport-smb/README.md) владеет sessions/wire;
  [композиция](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/SyncConfig.java)
  подключает scheduling, listeners, storage и transport.
- [Defaults](../../../bootstrap/ioc-app/src/main/resources/application.yml) и
  [CLI](../../../adapters/adapter-cli-picocli/README.md) владеют конфигурацией/командами.

## Когда обновлять

Меняйте документ, перевод и затронутое руководство при изменениях remote identity,
marker/ledger state, retry/reconcile, endpoint exclusion или ownership.
Точные списки полей оставляйте справочнику конфигурации.

## Связанные документы

- [Artifact export](artifact-export.md) — контракт completed slice.
- [Event coordination](event-coordination.md) — fast paths и backstops.
- [Configuration](configuration.md) — strict reference validation.
- [Observability](../observability.md) — diagnostics и sensitive fields.
- [ADR-0011](../../ADR/0011-remote-sync.md) — решение remote sync.
