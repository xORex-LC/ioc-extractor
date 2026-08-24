# developer tools

## Назначение

Локальные воспроизводимые сценарии разработки и ручной проверки. Все команды
можно запускать из любого рабочего каталога: repo root определяется относительно
самого скрипта.

## Инструменты

| Команда | Назначение |
|---|---|
| `bootstrap.sh lychee` | Установить закреплённый lychee в `.dev/tools/bin` с SHA-256 verification |
| `context.sh …` | Вывести стабильный `key=value` cold-start context: version, Git, runtime и свежесть последнего `verify` |
| `app.sh …` | Запустить public CLI через единственный найденный bootable jar; optional isolated workspace |
| `doctor.sh [core|dev|ci|security|all]` | Проверить обязательные и optional prerequisites без установки пакетов |
| `fixture.sh …` | Сгенерировать детерминированный HTML/text IOC fixture и JSON manifest |
| `runtime.sh … up|down|status|reset` | Управлять изолированным daemon под `.dev/runtime` |
| `submit.sh … SOURCE` | Атомарно подать fixture/source в inbox developer daemon |
| `database.sh … shell|schema|tables` | Read-only inspection service/dataframe SQLite |
| `smoke.sh [cli|oneshot|daemon|import|all]` | Проверить public CLI, canonical storage/export, daemon ingest/health и полный local managed-import flow |
| `lifecycle-smoke.sh …` | Через daemon проверить active→history expiry, bounded retention, projection/export convergence, query plans и ID non-reuse |
| `dataframe-import-load.sh …` | Выполнить opt-in 100k/1M полный JDBC import profile, проверить SLO/heap/query plans и сохранить evidence |
| `logs.sh …` | Читать и фильтровать ECS JSON по level/event/run/diagnostic |
| `release-notes-context.sh …` | Собрать read-only Git/PR inventory для ручной подготовки release notes |

Перед runtime/smoke должен существовать bootable jar:

```bash
./mvnw -B -ntp -T 1C -DskipTests package
```

Примеры:

```bash
tools/dev/fixture.sh --size 5000 --seed 42
tools/dev/runtime.sh --port 18081 up
tools/dev/submit.sh .dev/fixtures/ioc-5000-seed-42.html
tools/dev/runtime.sh status
tools/dev/database.sh --db dataframe schema
tools/dev/logs.sh --workspace .dev/runtime errors
tools/dev/runtime.sh down
tools/dev/smoke.sh all
tools/dev/lifecycle-smoke.sh --size 1000
tools/dev/dataframe-import-load.sh --profile mixed --size 1000000
tools/dev/release-notes-context.sh --previous-tag v0.1.0 --target HEAD
```

Основной интерфейс для повседневной работы — корневой `Makefile`: `make help`
показывает цели и принимаемые `NAME=value` параметры. Прямой вызов scripts
остаётся доступен для редких расширенных комбинаций.

`make context` намеренно печатает только бесцветные `key=value` строки. Последний
`verify` считается свежим лишь когда evidence из `.dev/state/last-verify.env`
соответствует текущему commit и содержимому working tree. Отсутствующий daemon,
отсутствующий evidence или изменённое после проверки дерево выводятся как
состояния, а не скрываются.

`make release-notes-context PREVIOUS_TAG=v0.1.0` собирает Markdown-инвентарь
из локальной Git-истории: changed areas/modules, commits, references и
dependency/security candidates. `GITHUB=1` дополнительно запрашивает merged PR
через аутентифицированный `gh`. Команда ничего не публикует и не изменяет:
результат служит входом для ручной курации, а не готовыми release notes.

`lychee` отсутствует в обычных Ubuntu APT repositories. `make bootstrap`
загружает закреплённый pre-built release для Linux x86_64/aarch64, проверяет
коммитнутый SHA-256 и атомарно устанавливает binary под `.dev/tools/bin` без
`sudo`, Snap или Rust toolchain. System-wide `lychee` из `PATH` также
поддерживается.

`reset` удаляет только предварительно проверенный workspace внутри repo-local
`.dev/`; symlink и внешние пути отклоняются. Runtime по умолчанию не включает
remote sync и никогда не использует systemd/sudo.

Daemon smoke намеренно использует polling backstop (`use-watch-service=false`),
чтобы проверять переносимый correctness path независимо от WSL/filesystem watch
семантики. Обычный `runtime.sh up` не меняет application default.

Managed-import smoke, напротив, включает local WatchService как latency hint и
оставляет двухсекундный complete-listing reconcile. Он атомарно публикует CSV,
ожидает защищённую terminal source/report unit, проверяет canonical projection и
повторно проверяет daemon health.

Lifecycle smoke также использует только public daemon ingestion. Он читает
SQLite в read-only режиме для assertions/query plans, сохраняет report под
`.dev/` и никогда не вставляет business rows напрямую. Для reference load
профиля используйте `make lifecycle-load`; короткий `make lifecycle-smoke`
проверяет тот же state transition на 1k input rows. Harness также закрепляет
export contract: expiry не создаёт immutable slice, а последующие новые public
rows создают slice с точным active membership.

Managed-import load harness является отдельной opt-in квалификацией, а не частью
обычного `make verify`. Профиль `insert/100000` измеряет полный staging и
canonical promotion. Профиль `mixed/1000000` создаёт валидный active baseline,
а затем одной поставкой проверяет равные доли insert/update/no-op/conflict.
Тестовый seed пишет только во временную JUnit SQLite под `.dev`; production и
developer runtime databases он не открывает. Оба профиля работают с packaged
daemon heap ceiling `-Xmx512m`, закрепляют peak-heap SLO и сохраняют plans/RSS в
`report.md` выбранного evidence workspace.

`lifecycle-load` закрепляет измеримый regression envelope, а не hardware-neutral
benchmark: input fixture маршрутизируется как минимум в 100k canonical rows,
deadline wave не шире 30s, начало
expiry не позже 5s, drain не медленнее 2500 rows/s, retention не дольше 180s и
JVM `VmHWM` не выше systemd `MemoryMax=1GiB`. Harness запускает тот же
`-Xms128m/-Xmx512m` memory profile, что packaged daemon. Throughput floor
составляет менее половины исходного WSL2 baseline и оставляет запас для host noise, но обнаружит
регрессию порядка 2x. Новый reference host/JDK/SQLite или изменение batching
требуют осознанного rebaseline с сохранённым report, а не ослабления assertion
после случайного red run.
