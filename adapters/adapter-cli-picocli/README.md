# adapters/adapter-cli-picocli

## Назначение

Inbound CLI adapter implemented with picocli and Spring-managed command objects.

**Правило слоя:** translates CLI arguments into application use-case calls; it
does not implement business rules.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/in/cli/` | CLI root, extract/export/health commands |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-platform-observability`, Spring Boot,
picocli.

**Не импортируется:** bootstrap and outbound adapters.

`ExportCommand` зависит только от lazy `ObjectProvider` driving-портов. Поэтому
парсинг root help и выполнение остальных команд не разрешают JDBC/service-DB
graph; recovery и export создаются только после фактического вызова
`ioc export --profile ...`.
