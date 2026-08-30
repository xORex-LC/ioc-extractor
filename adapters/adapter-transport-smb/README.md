# adapters/adapter-transport-smb

## Назначение

Outbound remote-transport adapter over **SMB2/3** (smbj): реализует
`FileTransport` (list/stat/get/delete + atomic multi-file publish), managed
dataframe-import ownership/source access/disposition/retention и опциональный
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
| `SmbTransportTelemetry` | Framework-free thread-safe snapshot активных connection/session/tree-connect leases, open/operation failures и resource exhaustion по bounded endpoint/role dimensions |
| `SmbFileTransport` | `FileTransport`: list/stat/get/delete + `publishAtomically` поверх общего pool |
| `SmbShareClient` / `SmbjShareClient` (+ `Factory`) | Обёртка над smbj share; открытие/аутентификация/операции |
| `SmbManagedImportSourceLifecycle` | Positive pre-provisioned namespace probe, server-side claim rename, orphan adoption, write-exclusive claimed-byte access, remote disposition и exact terminal-source purge |
| `SmbImportChangeSignalSource` | Source-scoped import doorbells поверх transport-neutral watch port |
| `SmbEndpointSettings` / `SmbEncryptionPolicy` | Настройки соединения (host/share/domain/creds/encryption policy/таймауты); пароль — defensive copy, `<redacted>` в `toString` |
| `ConnectTimeoutSocketFactory` | Настоящий TCP connect-timeout (smbj его не даёт) |
| `SmbExceptionMapper` | smbj/IO ошибки → `RemoteTransportException` с `RemoteErrorKind`; resource/quota NTSTATUS классифицируются по raw numeric status, включая отсутствующие в enum smbj |
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

Managed import не расширяет read-only контракт ordinary sync fetch. Runtime не
создаёт `.ioc-managed-import/{processing,terminal,quarantine,probe}`: operator
provisions namespace/ACL, adapter проверяет положительный private-object flow.
Локальный snapshot хранится общей реализацией за application port, переданной
composition root; SMB module не зависит от `adapter-ingest`.

Encryption policy также остаётся общей для ordinary sync и managed import:
`required` допускает только SMB3 и проверяет effective session encryption до
share I/O, `preferred` явно разрешает fallback, `disabled` не запрашивает
client-preferred encryption. Policy mismatch публикуется как общий
`SECURITY_POLICY_UNMET`, а SMBJ mechanics не выходят из adapter.

SMB session capacity не discoverable через обычный client protocol. Adapter
считает только установленные ресурсы, которыми владеет приложение, и переводит
серверные resource/quota отказы в общий `RESOURCE_EXHAUSTED`. Micrometer и
Actuator проецируют framework-free snapshot только в bootstrap.

## Связанные документы

- Способность целиком: [../../docs/dev/sync.md](../../docs/dev/sync.md).
- Гайд по эксплуатации/настройке SMB-шары:
  [../../docs/guides/remote-storage-sync.md](../../docs/guides/remote-storage-sync.md).
- Гайд managed import namespace/ACL:
  [../../docs/guides/dataframe-import.md](../../docs/guides/dataframe-import.md#submit-an-smb-delivery).
- Решения: ADR [0011](../../docs/ADR/0011-remote-sync.md),
  [0013](../../docs/ADR/0013-event-driven-coordination.md),
  [0024](../../docs/ADR/0024-managed-dataframe-import.md).
