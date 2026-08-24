# adapters/adapter-transport-smb

## Назначение

Outbound remote-transport adapter over **SMB2/3** (smbj): реализует
`FileTransport` (list/stat/get/delete + atomic multi-file publish), managed
dataframe-import ownership/materialization/disposition и опциональный
`RemoteChangeSignalSource` (SMB2 `CHANGE_NOTIFY` doorbell для fetch/import).

**Правило слоя:** держит только SMB-специфику (сессии, handles, timeouts,
reconnect, server rename, file identity/share modes, marker-last publish,
watch-lifecycle); discovery/dedup/ledger-логика
остаётся в application. Наружу торчит контракт порта, не типы smbj.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `SmbSessionPool` | Общий lazy endpoint-keyed pool для sync и managed import; сериализует endpoint operation и владеет reconnect/idle close |
| `SmbFileTransport` | `FileTransport`: list/stat/get/delete + `publishAtomically` поверх общего pool |
| `SmbShareClient` / `SmbjShareClient` (+ `Factory`) | Обёртка над smbj share; открытие/аутентификация/операции |
| `SmbManagedImportSourceLifecycle` | Server-side claim rename, orphan adoption, write-exclusive durable local snapshot и remote terminal/quarantine disposition |
| `SmbImportChangeSignalSource` | Source-scoped import doorbells поверх transport-neutral watch port |
| `SmbEndpointSettings` | Настройки соединения (host/share/domain/creds/таймауты); пароль — defensive copy, `<redacted>` в `toString` |
| `ConnectTimeoutSocketFactory` | Настоящий TCP connect-timeout (smbj его не даёт) |
| `SmbExceptionMapper` | smbj/IO ошибки → `RemoteTransportException` с `RemoteErrorKind` |
| `SmbChangeNotifyWatcher` | `RemoteChangeSignalSource`: выделенная watch-сессия, doorbell-callback, re-arm/overflow/lease, capped backoff |
| `Smb*ChangeNotify{Session,Pending,Result,SessionFactory}` | Тестируемый SPI-seam вокруг `CHANGE_NOTIFY`; `Smbj…` — реализация на smbj |
| `SmbRemoteEntry` | Внутреннее remote evidence: path, size, last-write time, directory bit и server file ID |

## Зависимости

**Зависит от:** `ioc-application` (порты `FileTransport`,
`RemoteChangeSignalSource`, sync value objects), `ioc-domain`, platform
errors/observability, **smbj** (единственная внешняя transport-библиотека
модуля), SLF4J API.

**Не импортируется:** bootstrap и соседние адаптеры. Один модуль = одна внешняя
библиотека/интеграция (SMB): смена транспорта затрагивает только этот модуль.

## Связанные документы

- Способность целиком: [../../docs/dev/sync.md](../../docs/dev/sync.md).
- Гайд по эксплуатации/настройке SMB-шары:
  [../../docs/guides/remote-storage-sync.md](../../docs/guides/remote-storage-sync.md).
- Решения: ADR [0011](../../docs/ADR/0011-remote-sync.md),
  [0013](../../docs/ADR/0013-event-driven-coordination.md),
  [0024](../../docs/ADR/0024-managed-dataframe-import.md).
