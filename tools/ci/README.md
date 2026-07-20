# CI tools

## Назначение

Неинтерактивные leaf-команды, которые являются общей точкой исполнения для
будущего Makefile и GitHub Actions. Скрипты не устанавливают недостающие tools:
окружение подготавливает developer или CI setup step.

| Команда | Gate |
|---|---|
| `build.sh` | Полный Maven reactor `verify` |
| `packaging.sh` | ShellCheck + packaging contract tests |
| `docs.sh` | Offline link check через `lychee` |
| `dependency-security.sh update|scan|report` | OWASP Dependency-Check с единым набором параметров |

Security gate требует `NVD_API_KEY`; значение не печатается. Cache по умолчанию
живёт в `/.dependency-check-data/` и не отслеживается Git.
