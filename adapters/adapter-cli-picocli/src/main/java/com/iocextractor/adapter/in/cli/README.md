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
| `ApplicationBuildInfoReader.java` | JDK-only reader packaged `META-INF/build-info.properties` |
| `BuildInfoVersionProvider.java` | Человекочитаемый Picocli output для `-V/--version` |
| `EarlyCliLauncher.java` | Help, health и ошибки синтаксиса до запуска Spring context |
| `CliRunner.java` | Мост Spring Boot ↔ picocli, проброс exit-кода |

## Точка входа

- `ioc extract --source <file> [--dry-run]`;
- `ioc export --profile <configured-name>`;
- `ioc sync fetch [--source <name>] [--endpoint <name>] [--dry-run]`;
- `ioc sync publish [--profile <name>] [--target <name>] [--endpoint <name>] [--dry-run]`;
- `ioc sync all [fetch/publish filters] [--dry-run]`;
- `ioc health [--json]`;
- `ioc --version` (`ioc -V`).

`EarlyCliLauncher` строит read-only Picocli model из тех же аннотированных command-классов.
Root/subcommand help, version, синтаксически некорректный ввод и `health` поэтому завершаются
до `SpringApplication.run()`. Валидные `extract`, `export` и leaf-команды `sync` передаются
Spring composition root. Для раннего `health` адрес по умолчанию берётся из JVM properties
`server.address`/`server.port`, затем из `SERVER_ADDRESS`/`SERVER_PORT`, затем используется
`127.0.0.1:8081`; нестандартный endpoint также можно явно задать CLI-опциями.

Version provider читает тот же `META-INF/build-info.properties`, который
публикует runnable module. Product version обязательна; commit и build time
выводятся только при фактическом наличии. Этот путь не использует Spring
`BuildProperties`, не обращается к Git и не придумывает значения `unknown`.

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

`extract` печатает completion/diagnostic summary. `COMPLETED_WITH_ERRORS`
возвращает exit code `3`, чтобы не пересекаться с picocli `SOFTWARE=1` и
`USAGE=2`.
