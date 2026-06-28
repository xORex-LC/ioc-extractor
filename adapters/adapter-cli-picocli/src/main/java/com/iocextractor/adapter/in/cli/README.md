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
| `SyncCommand.java` | Родительская команда `sync` и справка по remote delivery |
| `SyncFetchCommand.java` | Явный remote fetch с фильтрами source/endpoint и dry-run |
| `SyncPublishCommand.java` | Явная публикация completed slices с фильтрами profile/target/endpoint |
| `SyncAllCommand.java` | Полностью preflight-валидируемая последовательность fetch → publish |
| `HealthCommand.java` | Запрос actuator health у отдельного daemon process |
| `CliRunner.java` | Мост Spring Boot ↔ picocli, проброс exit-кода |

## Точка входа

- `ioc extract --source <file> [--dry-run]`;
- `ioc export --profile <configured-name>`;
- `ioc sync fetch [--source <name>] [--endpoint <name>] [--dry-run]`;
- `ioc sync publish [--profile <name>] [--target <name>] [--endpoint <name>] [--dry-run]`;
- `ioc sync all [fetch/publish filters] [--dry-run]`;
- `ioc health [--json]`.

`ExportCommand` сначала вызывает IO-free profile validator и только затем
разрешает через `ObjectProvider` storage-backed export use case. Построение root
help, `extract`, `health` и отказ для unknown profile поэтому не инициализируют
service datasource/migrations. Exclusive operation lease, forward recovery и
formation упорядочивает application use case; driving adapter не координирует их
самостоятельно.

Sync-команды следуют той же lazy-схеме. Сначала IO-free validator проверяет feature
flags и соответствие логических имён конфигурации, затем `ObjectProvider` разрешает
JDBC/transport-backed use case. Поэтому `--help` и некорректные фильтры не открывают
service DB и SMB session. `sync all` валидирует обе операции до разрешения любого
use case и только после успешного preflight выполняет fetch, затем publish. Итоговая
строка содержит детерминированные счётчики; ненулевой `failed` даёт exit code `1`.
