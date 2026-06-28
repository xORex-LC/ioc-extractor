# application.sync

## Назначение

Framework-free модель синхронизации с внешними хранилищами. Пакет содержит только
value objects и политики, которые одинаковы для SMB/SFTP/S3 и не знают о Spring,
планировщиках, JDBC или конкретных transport-библиотеках.

## Структура

| Файл | Ответственность |
|---|---|
| `RemoteErrorKind` / `RemoteErrorDisposition` | Закрытая transport-neutral error axis и retry decision |
| `RemoteTransportException` | Исключение, через которое adapters сообщают нейтральный remote error |
| `RetryPolicy` / `Retrier` | Micro-retry executor для `RETRY_NOW` ошибок |
| `RemoteObject` / `RemoteObjectIdentity` | Metadata и fetch-ledger identity удалённого файла |
| `PublishAtomicallyRequest` / `PublishReceipt` | Контракт толстого write-intent для публикации slice-каталога |

## Инварианты

- Retryability не настраивается оператором: `RemoteErrorKind` сам несёт disposition.
- `Retrier` повторяет только `RETRY_NOW`; `RETRY_LATER` остаётся macro-retry задачей scheduler.
- Payload остаётся файловым (`Path`), но lifecycle потоков/соединений не попадает в application.
- Commit marker name — один безопасный leaf segment; remote path остаётся transport path.

## Зависимости

**Зависит от:** JDK.

**Не импортируется:** Spring, JDBC, smbj/transport libraries, adapters, bootstrap.
