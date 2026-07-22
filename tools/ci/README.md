# CI tools

## Назначение

Неинтерактивные leaf-команды — общая точка исполнения для корневого Makefile и
GitHub Actions. Makefile является только локальным developer-facing фасадом;
workflows вызывают scripts напрямую и не зависят от Make. Скрипты не
устанавливают недостающие tools: окружение подготавливает developer или CI setup
step (`make bootstrap` локально, `tools/dev/bootstrap.sh lychee` в CI).

| Команда | Gate |
|---|---|
| `build.sh` | Полный Maven reactor `verify` + atomic evidence под `.dev/state/` |
| `packaging.sh` | ShellCheck + packaging contract tests |
| `docs.sh` | Offline link check через `lychee` |
| `dependency-security.sh update|scan|report` | Явное обновление NVD data, offline scan или поиск готового отчёта |

Только `update` требует `NVD_API_KEY`; значение не печатается. `scan` намеренно
не обращается к NVD и работает только по существующей локальной базе. Локально
без override используется стандартный Maven Dependency-Check data directory;
`DEPENDENCY_CHECK_DATA` задаёт изолированный путь и при отсутствии базы даёт
подсказку сначала выполнить `make security-update`. Weekly/manual workflow
задаёт repo-local `./.dependency-check-data/` для cache и явно выполняет
`update`, затем отдельный offline `scan`.

| Make target | Локальное использование того же leaf script |
|---|---|
| `make ci-build` | Локальная копия GitHub build job |
| `make ci-packaging` | Локальная копия GitHub packaging job |
| `make ci-docs` | Локальная копия GitHub docs job |
| `make ci` / `make pre-push` | Все регулярные gates последовательно |
| `make security-update` | Обновить локальную NVD data (network + API key) |
| `make security-scan` | Быстро проверить reactor по имеющейся локальной data |

`build.sh` сохраняет `last-verify.env` после завершения Maven с commit,
fingerprint рабочего дерева, временем и результатом. Если дерево изменилось во
время проверки, результат получает состояние `invalidated`. Evidence является
локальным developer context для `make context`, игнорируется Git и не заменяет
CI check/run как release-доказательство.
