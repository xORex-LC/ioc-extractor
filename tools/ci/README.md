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
| `codecov.sh verify-input\|require-report` | Повторная project-owned проверка aggregate JaCoCo evidence либо проверка скачанного CI handoff; без сети |
| `pmd.sh policy\|watchlist` | Ratcheted PMD production-source policy либо отдельный deferred watchlist |
| `test-pilots.sh mutation\|stability` | Report-only domain PIT или seeded random-order/repeat stability evidence |
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
| `make ci-pmd` | Локальная копия регулярного PMD source-policy job |
| `make ci-packaging` | Локальная копия GitHub packaging job |
| `make ci-docs` | Локальная копия GitHub docs job |
| `make ci` / `make pre-push` | Все регулярные gates последовательно |
| `make security-update` | Обновить локальную NVD data (network + API key) |
| `make security-scan` | Быстро проверить reactor по имеющейся локальной data |
| `make mutation-pilot` | Полный PIT diagnostic только для `core/ioc-domain` |
| `make stability-pilot` | Seeded random-order прогоны без retry (`SEED`/`REPEAT` настраиваются) |

`build.sh` сохраняет `last-verify.env` после завершения Maven с commit,
fingerprint рабочего дерева, временем и результатом. Если дерево изменилось во
время проверки, результат получает состояние `invalidated`. Evidence является
локальным developer context для `make context`, игнорируется Git и не заменяет
CI check/run как release-доказательство.

`pmd.sh policy` тем же атомарным протоколом сохраняет `last-pmd.env`.
`make context` показывает его независимо от `last-verify.env`, поэтому полный
локальный quality claim требует двух fresh passed results. `pmd.sh watchlist`
намеренно не обновляет regular-policy evidence.

`codecov.sh verify-input` повторно применяет тот же fail-closed
`CoverageVerifier`, который завершает Maven `verify`. Отсутствующий, устаревший
или неполный aggregate/module report остаётся ошибкой CI до внешнего шага.
Отдельный reporting job вызывает `require-report` после artifact handoff.
Скрипт ничего не загружает: Codecov Action получает только явно указанный
aggregate XML и работает best-effort отдельно от project-owned gate. OIDC
`id-token: write` выдан только reporting job, который не запускает Maven или
код из тестируемого reactor.

`test-pilots.sh mutation` запускает opt-in PIT profile только в
`core/ioc-domain`, сохраняет стабильные HTML/XML reports под
`core/ioc-domain/target/pit-reports/` и машинно-читаемый итог под
`target/test-pilots/`. Нулевые mutation thresholds сохраняют pilot
диагностическим: Maven/test/tool failure остаётся ошибкой, но survived mutant
не превращается в PR gate.

`test-pilots.sh stability` последовательно запускает весь набор functional JAR
modules с random Surefire/Failsafe и JUnit class/method order. Каждый повтор
получает явный seed, проходит exact source/report-union verifier и архивирует
XML до следующей очистки lifecycle. Первый failure останавливает pilot без
retry; seed и частичные reports остаются под `target/test-pilots/stability/`.
