# adapters/adapter-psl

## Назначение

Outbound domain-support adapter implementing `HostClassifier` with the Guava
Public Suffix List.

**Правило слоя:** isolates PSL/Guava dependency behind the domain port.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/out/psl/` | PSL host classifier |
| `src/test/java/com/iocextractor/adapter/out/psl/` | PSL classifier tests |

## Зависимости

**Зависит от:** `ioc-domain`, Guava.

**Не импортируется:** application, bootstrap, sibling adapters.
