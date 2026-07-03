# adapter/out/transport/smb

SMB-адаптер для `FileTransport`. Модуль инкапсулирует `smbj` и не отдаёт наружу
SMB-сессии, handles или типы transport-библиотеки.

## Состав

| Файл | Роль |
|---|---|
| `SmbFileTransport` | Реализация transport-neutral `FileTransport`: `list`, `stat`, `get`, `delete`, `publishAtomically`. |
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
- Соединение создаётся lazy и переиспользуется per endpoint. Операции одного endpoint
  сериализованы, поэтому reconnect/idle-close не закрывают share во время активного IO;
  разные endpoints могут обслуживаться параллельно.
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
taxonomy mapping, watch re-arm, lease re-open и bounded close.

Opt-in contract test против живого SMB-сервера:

```bash
./mvnw -pl adapters/adapter-transport-smb test \
  -Dioc.smb.contract=true \
  -Dioc.smb.host=127.0.0.1 \
  -Dioc.smb.share=test-share \
  -Dioc.smb.username="$SMB_USER" \
  -Dioc.smb.password="$SMB_PASSWORD" \
  -Dioc.smb.remotePath=send
```
