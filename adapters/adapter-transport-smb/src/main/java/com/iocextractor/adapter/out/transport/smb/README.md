# adapter/out/transport/smb

SMB-адаптер для sync `FileTransport` и managed dataframe-import lifecycle.
Модуль инкапсулирует `smbj` и не отдаёт его типы через application ports.

## Состав

| Файл | Роль |
|---|---|
| `SmbSessionPool` | Общий lazy endpoint-keyed pool для sync/import operations с сериализацией, reconnect и idle close. |
| `SmbFileTransport` | Реализация transport-neutral `FileTransport`: `list`, `stat`, `get`, `delete`, `publishAtomically`. |
| `SmbManagedImportSourceLifecycle` | Complete listing, server-side claim rename, orphan adoption, durable local snapshot и remote disposition. |
| `SmbImportSourceDefinition` | Привязка managed source к endpoint и remote inbox. |
| `SmbImportChangeSignalSource` | Преобразует `CHANGE_NOTIFY` в source-scoped import listing hint. |
| `SmbChangeNotifyWatcher` | Optional `RemoteChangeSignalSource` поверх SMB2 `CHANGE_NOTIFY`; отдаёт только doorbell-сигналы. |
| `SmbjChangeNotifySessionFactory` | Выделенный SMBJ client/session/share/directory handle для long-poll watch. |
| `SmbEndpointSettings` | Immutable-настройки endpoint с маскированием credentials. |
| `ConnectTimeoutSocketFactory` | Ограничивает TCP connect через `Socket.connect(timeout)`. |
| `SmbjShareClientFactory` | Создаёт SMBJ client/session/share для endpoint. |
| `SmbjShareClient` | Тонкая обёртка над SMBJ `DiskShare`, работающая в терминах путей и файлов. |
| `SmbExceptionMapper` | Переводит SMBJ/IO ошибки в `RemoteErrorKind`. |

## Инварианты

- `smbj` остаётся только в этом adapter-модуле.
- Пароли не попадают в `toString()` и не должны логироваться.
- `publishAtomically` пишет данные в adapter-owned temp path, commit-marker — последним,
  затем делает `temp → final` rename.
- Уже опубликованный slice с совпадающим marker считается idempotent success.
- Remote partial без marker считается adapter-owned состоянием и может быть перезаписан.
- `delete` используется только явными retention/cleanup сценариями; fetch-source в v1 read-only.
- Sync/import соединение создаётся lazy и переиспользуется per endpoint. Операции одного endpoint
  сериализованы, поэтому reconnect/idle-close не закрывают share во время активного IO;
  разные endpoints могут обслуживаться параллельно.
- Managed import определяет стабильность полным listing и фиксирует leaf, size,
  last-write time и server file ID. После ledger reservation первым ownership
  действием является `inbox/file → inbox/.ioc-managed-import/processing/<delivery-token>.csv`.
- Producer+processing collision не перезаписывается. Processing orphan
  переиспользуется только той же delivery, что позволяет продолжить после
  disconnect сразу за rename.
- Materialization открывает claimed object без write sharing, проверяет remote
  evidence до/после stream и публикует fsync-ed local snapshot через atomic move.
  Изменение объекта или разрыв оставляет только retryable remote ownership и не
  открывает canonical write path.
- Remote disposition выполняется после local terminal source/report unit:
  `REJECTED` идёт в `quarantine`, остальные terminal outcomes — в `terminal`.
- `CHANGE_NOTIFY` использует выделенный клиент и не делит cached `SmbShareClient` с
  `list/get/publish`; long-poll watch не должен блокировать обычные transport операции.
- Watcher — это accelerator, не correctness path: callback только сообщает
  "что-то изменилось", а application запускает обычный `RemoteSourceMonitor.detect`.
- Pending watch имеет bounded shutdown: `close()` отменяет future, закрывает SMB handles
  и ждёт daemon-worker ограниченное время. Плановый lease re-open вызывает новый
  `WATCH_ESTABLISHED` detect через bootstrap coordinator.
- Async `CHANGE_NOTIFY` response сначала классифицируется по SMB2 header status:
  `STATUS_NOTIFY_ENUM_DIR` означает overflow/re-list signal, а error-status
  (`ACCESS_DENIED`, `DELETE_PENDING`, invalid handle и т.п.) переводится в watch failure
  с reconnect/backoff, а не маскируется под пустой signal. `STATUS_SUCCESS` без notify
  entries считается spurious wake/no-op: watcher re-arm'ит ожидание без doorbell callback.
- Transient/unreachable failure инвалидирует cached client; следующий macro/micro retry
  открывает новое соединение. Bootstrap вызывает `closeIdle`, shutdown закрывает все clients.
- `connectTimeout` ограничивает TCP dial, `requestTimeout` — отдельный SMB
  read/write/transact request, `idleTimeout` — жизнь неиспользуемого cached client.
  Reader socket работает с `SO_TIMEOUT=0`, поэтому простой не уничтожает живое соединение.

## Тестирование

Unit-тесты используют fake `SmbShareClient`/watch-session без SMB-сервера и проверяют
атомарность publish-протокола, idempotency, reconnect-on-transient, timeout wiring,
taxonomy mapping, watch re-arm, lease re-open, bounded close, polling без
notifications, claim-before-download, share conflict, collision, object
substitution/mutation, orphan adoption, disposition и shared-session reuse.

Пять opt-in контрактных сценариев против живого SMB-сервера проверяют managed
claim/disposition/restart и `CHANGE_NOTIFY`:

```bash
./mvnw -pl adapters/adapter-transport-smb -am test \
  -Dioc.smb.contract=true \
  -Dioc.smb.host=127.0.0.1 \
  -Dioc.smb.share=test-share \
  -Dioc.smb.username="$SMB_USER" \
  -Dioc.smb.password="$SMB_PASSWORD" \
  -Dioc.smb.remotePath=import
```

Каждый сценарий создаёт только собственный UUID-подкаталог под `remotePath` и
удаляет именно его в `finally`; корень operator inbox не очищается.
