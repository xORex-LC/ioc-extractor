# developer tools

## Назначение

Локальные воспроизводимые сценарии разработки и ручной проверки. Все команды
можно запускать из любого рабочего каталога: repo root определяется относительно
самого скрипта.

## Инструменты

| Команда | Назначение |
|---|---|
| `bootstrap.sh lychee` | Установить закреплённый lychee в `.dev/tools/bin` с SHA-256 verification |
| `app.sh …` | Запустить public CLI через единственный найденный bootable jar; optional isolated workspace |
| `doctor.sh [core|dev|ci|security|all]` | Проверить обязательные и optional prerequisites без установки пакетов |
| `fixture.sh …` | Сгенерировать детерминированный HTML/text IOC fixture и JSON manifest |
| `runtime.sh … up|down|status|reset` | Управлять изолированным daemon под `.dev/runtime` |
| `submit.sh … SOURCE` | Атомарно подать fixture/source в inbox developer daemon |
| `database.sh … shell|schema|tables` | Read-only inspection service/dataframe SQLite |
| `smoke.sh [cli|oneshot|daemon|all]` | Проверить public CLI, canonical storage/export и daemon ingest/health |
| `logs.sh …` | Читать и фильтровать ECS JSON по level/event/run/diagnostic |

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
tools/dev/logs.sh errors
tools/dev/runtime.sh down
tools/dev/smoke.sh all
```

Основной интерфейс для повседневной работы — корневой `Makefile`: `make help`
показывает цели и принимаемые `NAME=value` параметры. Прямой вызов scripts
остаётся доступен для редких расширенных комбинаций.

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
