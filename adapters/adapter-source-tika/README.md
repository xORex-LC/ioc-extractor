# adapters/adapter-source-tika

## Назначение

Outbound source adapter implementing `SourceReader` with Apache Tika.

**Правило слоя:** contains document parsing details only; application sees the
`SourceReader` port.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/out/source/` | Tika-backed source reader |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-platform-errors`,
`ioc-platform-observability`, Tika, SLF4J API.

**Не импортируется:** bootstrap and other adapters.
