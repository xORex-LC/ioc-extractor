# adapters/adapter-regex-re2j

## Назначение

Outbound technical adapter implementing the domain `PatternEngine` port with
RE2J and a JDK fallback.

**Правило слоя:** isolates regex engine dependencies behind the domain port.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/out/regex/` | Regex engine implementations |

## Зависимости

**Зависит от:** `ioc-domain`, RE2J.

**Не импортируется:** application, bootstrap, other adapters.
