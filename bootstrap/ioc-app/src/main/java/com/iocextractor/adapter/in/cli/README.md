# com.iocextractor.adapter.in.cli

## Назначение

Входной (driving) адаптер: CLI на picocli. Транслирует аргументы командной
строки в `ExtractionCommand` и вызывает driving-порт.

**Правило слоя:** знает о `port/in`; не содержит бизнес-логики. Команды
инстанцируются через Spring `IFactory` (DI работает в под-командах).

## Структура

| Файл | Назначение |
|---|---|
| `IocRootCommand.java` | Корневая команда `ioc` (держит под-команды) |
| `ExtractCommand.java` | Под-команда `extract` (`-s/--source`, `--dry-run`) |
| `CliRunner.java` | Мост Spring Boot ↔ picocli, проброс exit-кода |

## Точка входа

`ioc extract --source <file> [--dry-run]`.
