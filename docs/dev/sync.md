# Синхронизация с внешними хранилищами

Sync соединяет внешнее файловое хранилище с локальными capability, не смешивая
их ответственность:

```text
remote source -> fetch -> local inbox -> ingestion -> canonical SQLite
canonical SQLite -> export slice -> publish -> remote target
```

Fetch не извлекает IOC, publish не строит артефакты. Транспорт скрыт за
`FileTransport`; текущая реализация SMB2/3 изолирована в
`adapter-transport-smb`.

## Поток fetch

```text
periodic/startup/CHANGE_NOTIFY hint
  -> detect: list + include/exclude + ledger/in-flight filtering
  -> RemoteChangeBatchDetected
  -> endpoint-keyed admission
  -> download into hidden .sync-staging/*.part
  -> fsync + atomic move into inbox
  -> remote_fetch_ledger = FETCHED
```

Remote object identity — `(path, size, modifiedAt)`. Source остаётся read-only:
успешный fetch не удаляет и не перемещает объект на remote стороне. Занятое
локальное имя не перезаписывается; новая identity получает стабильный suffix.

Detection и execution разделены: monitor решает, *что* изменилось, а fetch
service исполняет bounded command без повторного listing. Process-local
in-flight claim снижает повторную постановку, durable ledger обеспечивает
идемпотентность после restart.

## Поток publish

Publish принимает только immutable slice, который прошёл `_SUCCESS`, manifest
decode, hash/size и membership verification.

```text
completed slice x configured target
  -> publish-ledger anti-join/retryable work
  -> endpoint-keyed admission
  -> copy into remote staging directory
  -> verify
  -> write remote _SUCCESS last
  -> publish_ledger = SUCCEEDED
```

Для пары `(slice_id, target_id)` ledger хранит сагу:

```text
PENDING -> IN_PROGRESS -> SUCCEEDED
                   \-> FAILED -> IN_PROGRESS
PENDING|IN_PROGRESS|FAILED -> ABANDONED
```

`SUCCEEDED` и `ABANDONED` terminal. Если после crash remote marker уже совпадает
с manifest hash, recovery продвигает ledger вперёд без повторной публикации.
Mismatch является ошибкой проверки, а не поводом перезаписать неизвестное
состояние.

Retention не удаляет slice, пока для каждого настроенного target нет terminal
pair. Поэтому ограничение количества срезов является best-effort при backlog
доставки.

## Модель координации

События и SMB `CHANGE_NOTIFY` сокращают latency, но не являются источником
корректности:

- periodic detection повторно находит неполученные remote identities;
- periodic publish reconcile строит недостающие пары slice×target;
- durable fetch/publish ledgers переживают restart;
- все fast-path и reconcile работы одного endpoint проходят через один
  `KeyedSerialExecutor`; разные endpoints могут исполняться параллельно;
- admission overload может сбросить hint, потому что следующий reconcile
  восстановит работу.

`CHANGE_NOTIFY` — doorbell: callback не скачивает файл и не доверяет имени из
уведомления, а запускает обычный detection. Overflow, reconnect и lease re-open
также приводят к detection. Polling нельзя отключать после включения push.

## Транспорт и security boundary

Endpoint config ссылается на credentials через environment placeholders.
Пароли, username, host/share и query-like values не должны попадать в
operational logs. Неизвестные transport/endpoint/profile отклоняются config
preflight до первого I/O.

Для SMB:

- `encryption=required` является secure default: adapter предлагает только
  SMB3 dialects и после authentication проверяет effective session encryption
  до первого share request; нарушение даёт terminal
  `SECURITY_POLICY_UNMET`, а не сетевой retry;
- `preferred` запрашивает encryption, но явно допускает незашифрованный SMB2/3
  fallback; `disabled` не объявляет client preference;
- `connect-timeout` ограничивает TCP connect;
- `request-timeout` ограничивает один SMB request;
- `idle-timeout` задаёт время жизни неиспользуемого cached client;
- legacy `encrypt` и `read-timeout` не поддерживаются и получают migration
  hints.

Transport exception нормализуется в `RemoteErrorKind`, после чего application
выдаёт единый `SYNC.*` diagnostic. Если существует durable work record, сначала
фиксируется failure state, затем публикуется diagnostic.

## Lifecycle и health

Daemon запускает fetch раньше export, publish — после export, retention — после
publish discovery. Конкретные `SmartLifecycle` phase являются reference-level
деталью bootstrap и должны проверяться в коде/тестах, а не копироваться сюда.

Health сводит durable backlog, последний outcome по source/target, pinned
retention и состояние keyed executor/watch. Recoverable shed при работающем
reconcile видим как degradation signal, но сам по себе не означает потерю
корректности. Permanent transport/work failure или durable failed delivery
поднимает более строгий status.

CLI `sync fetch`, `sync publish` и `sync all` используют те же application
контракты. `--dry-run` не меняет inbox, remote storage или ledgers.

## Инварианты

1. Fetch заканчивается атомарным появлением complete файла в inbox.
2. Publish начинается только с verified completed slice.
3. Remote `_SUCCESS` записывается последним и связывается с manifest hash.
4. Ledger transition важнее события и лога.
5. Работа одного endpoint сериализована; межendpointный параллелизм разрешён.
6. Event/push может быть потерян без потери данных: reconcile обязан закрыть
   путь.
7. Sync не владеет extraction, canonical schema или export formation.
8. `encryption=required` не открывает share без effective SMB3 encryption.

## Как расширять

- Новый протокол получает отдельный adapter за `FileTransport` и, при наличии
  push, за `RemoteChangeSignalSource`. Watch boundary принимает только узкий
  `RemoteWatchTarget(sourceId, endpoint, remotePath)`; include/exclude fetch
  policy остаётся в application detection.
- Remote delete/move/retention требует отдельного решения о владении и не
  добавляется скрытым side effect существующего fetch/publish.
- Новый target использует существующую slice×target ledger семантику.
- Межпроцессная доставка требует adapter-level durable outbox/broker design;
  она не должна превращать `platform-events` в брокер.

## Источники истины

- Application ports/services: `core/ioc-application/.../sync/`.
- SMB semantics: `adapters/adapter-transport-smb/README.md` и реализация модуля.
- Scheduling, listeners, health: `bootstrap/ioc-app/.../sync/` и `AppConfig`.
- Defaults/validation: `application.yml` и `IocProperties.Sync`.
- CLI surface: `adapters/adapter-cli-picocli`.

## Когда обновлять документ

Обновите его при изменении remote identity, commit marker, ledger state
machine, reconcile guarantee, endpoint serialization, ownership remote data
или transport boundary. Добавление config field без изменения этих контрактов
фиксируется в config reference, а не раздувает этот guide.

## Связанные документы

- [artifact-export.md](artifact-export.md) — контракт completed slice.
- [event-coordination.md](event-coordination.md) — fast-path/backstop doctrine.
- [configuration.md](configuration.md) — strict endpoint/profile references.
- [observability.md](observability.md) — diagnostics и sensitive fields.
- [ADR-0011](../ADR/0011-remote-sync.md) — решение remote sync.
