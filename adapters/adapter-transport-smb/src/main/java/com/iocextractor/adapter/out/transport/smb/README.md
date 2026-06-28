# adapter/out/transport/smb

SMB-адаптер для `FileTransport`. Модуль инкапсулирует `smbj` и не отдаёт наружу
SMB-сессии, handles или типы transport-библиотеки.

## Состав

| Файл | Роль |
|---|---|
| `SmbFileTransport` | Реализация transport-neutral `FileTransport`: `list`, `stat`, `get`, `delete`, `publishAtomically`. |
| `SmbEndpointSettings` | Immutable-настройки endpoint с маскированием credentials. |
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
- Transient/unreachable failure инвалидирует cached client; следующий macro/micro retry
  открывает новое соединение. Bootstrap вызывает `closeIdle`, shutdown закрывает все clients.

## Тестирование

Unit-тесты используют fake `SmbShareClient` без SMB-сервера и проверяют атомарность
publish-протокола, idempotency, reconnect-on-transient и taxonomy mapping.
