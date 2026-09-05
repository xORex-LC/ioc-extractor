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
| `src/test/java/com/iocextractor/adapter/out/regex/` | Shared executable `PatternEngine` contract |

## Зависимости

**Зависит от:** `ioc-domain`, RE2J; test scope uses `ioc-application-tck`.

**Не импортируется:** application, bootstrap, other adapters.
