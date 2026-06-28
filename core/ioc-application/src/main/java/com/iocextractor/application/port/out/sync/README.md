# application.port.out.sync

## Назначение

Outbound-порты синхронизации. Application use cases зависят только от этих
интерфейсов; SMB/SFTP/S3 и их connection/session lifecycle остаются во внешних
adapter-модулях.

## Структура

| Файл | Ответственность |
|---|---|
| `FileTransport` | Stateless remote file operations и atomic publish intent |

## Инварианты

- Порт не раскрывает `RemoteSession`, stream ownership или transport-specific exceptions.
- Raw `put`/`rename` не являются application API.
- `publishAtomically` — единственная write-side операция с multi-file инвариантом.
- `delete` существует только как seam для opt-in remote retention/cleanup, не для fetch claim.

## Зависимости

**Зависит от:** `application.sync` value objects, JDK `Path`.

**Не импортируется:** smbj, Spring, JDBC, adapters, bootstrap.
