# com.iocextractor.adapter.in.cli

## Назначение

Входной (driving) адаптер: CLI на picocli. Транслирует аргументы командной
строки в команды application driving-портов и не содержит orchestration.

**Правило слоя:** знает о `port/in`; не содержит бизнес-логики. Команды
инстанцируются через Spring `IFactory` (DI работает в под-командах).

## Структура

| Файл | Назначение |
|---|---|
| `IocRootCommand.java` | Корневая команда `ioc` (держит под-команды) |
| `ExtractCommand.java` | Под-команда `extract` (`-s/--source`, `--dry-run`) |
| `ExportCommand.java` | Под-команда `export --profile`: lazy on-demand formation use case |
| `HealthCommand.java` | Запрос actuator health у отдельного daemon process |
| `CliRunner.java` | Мост Spring Boot ↔ picocli, проброс exit-кода |

## Точка входа

- `ioc extract --source <file> [--dry-run]`;
- `ioc export --profile <configured-name>`;
- `ioc health [--json]`.

`ExportCommand` сначала вызывает IO-free profile validator и только затем
разрешает через `ObjectProvider` storage-backed export use case. Построение root
help, `extract`, `health` и отказ для unknown profile поэтому не инициализируют
service datasource/migrations. Exclusive operation lease, forward recovery и
formation упорядочивает application use case; driving adapter не координирует их
самостоятельно.
