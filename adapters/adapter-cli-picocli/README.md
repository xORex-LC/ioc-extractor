# adapters/adapter-cli-picocli

## Назначение

Inbound CLI adapter implemented with picocli and Spring-managed command objects.

**Правило слоя:** translates CLI arguments into application use-case calls; it
does not implement business rules.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/in/cli/` | CLI root, extract/export/health/import commands |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-platform-diagnostics`,
`ioc-platform-observability`, Spring Boot, picocli и SLF4J API.

**Не импортируется:** bootstrap and outbound adapters.

`ExportCommand` зависит только от lazy `ObjectProvider` driving-портов. Поэтому
парсинг root help и выполнение остальных команд не разрешают JDBC/service-DB
graph; recovery и export создаются только после фактического вызова
`ioc export --profile ...`.

`ExtractCommand` создаёт run id до `command_start`, передаёт его application
use case и публикует terminal completion как операторский контракт. Stdout
содержит run id, `CompletionStatus`, total/suppressed и ненулевые severity
counts; `COMPLETED_WITH_ERRORS` возвращает отдельный exit code `3`.

`-V`/`--version` читает обязательный `META-INF/build-info.properties` до
старта Spring. Отсутствующая или некорректная build identity не подменяется
fallback-версией: CLI возвращает exit code `1` и одну операторскую строку в
stderr без stack trace.

`ioc import validate` выполняет side-effect-free preview по caller-owned CSV,
`ioc import status` показывает только aggregate recovery/head state, а
`ioc import replay` создаёт новую causally linked occurrence из retained
terminal unit. Эти команды используют lazy `ObjectProvider`: root help не
поднимает storage graph, а disabled capability завершается явной ошибкой.
