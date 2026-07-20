# CI tools

## Назначение

Неинтерактивные leaf-команды — общая точка исполнения для корневого Makefile и
GitHub Actions. Makefile является только локальным developer-facing фасадом;
workflows вызывают scripts напрямую и не зависят от Make. Скрипты не
устанавливают недостающие tools: окружение подготавливает developer или CI setup
step (`make bootstrap` локально, `tools/dev/bootstrap.sh lychee` в CI).

| Команда | Gate |
|---|---|
| `build.sh` | Полный Maven reactor `verify` |
| `packaging.sh` | ShellCheck + packaging contract tests |
| `docs.sh` | Offline link check через `lychee` |
| `dependency-security.sh update|scan|report` | OWASP Dependency-Check с единым набором параметров |

Security gate требует `NVD_API_KEY`; значение не печатается. Cache по умолчанию
живёт в `/.dependency-check-data/` и не отслеживается Git.

| Make target | Локальное использование того же leaf script |
|---|---|
| `make ci-build` | Локальная копия GitHub build job |
| `make ci-packaging` | Локальная копия GitHub packaging job |
| `make ci-docs` | Локальная копия GitHub docs job |
| `make ci` / `make pre-push` | Все регулярные gates последовательно |
| `make security-scan` | Scheduled/manual Dependency Security workflow |
