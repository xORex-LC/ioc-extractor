# tools

## Назначение

Воспроизводимые developer/CI-инструменты поверх Maven wrapper и bootable jar.
Корневой [`Makefile`](../Makefile) — стабильный локальный интерфейс для людей и
агентов, а этот каталог содержит вызываемую им реализацию. GitHub workflows
вызывают CI leaf scripts напрямую, чтобы pipeline оставался прозрачен без чтения
Makefile. Tools-layer не заменяет Maven lifecycle и не содержит production
deployment automation — она остаётся в [`packaging/`](../packaging/README.md).

## Состав

| Каталог | Ответственность |
|---|---|
| [`dev/`](dev/README.md) | Doctor, IOC fixtures, изолированный runtime, smoke, ECS logs и release context |
| [`ci/`](ci/README.md) | Неинтерактивные leaf-gates для build, docs, packaging и Dependency-Check |
| [`tests/`](tests/README.md) | Contract-тесты самого tools-layer |

Файл [`eclipse-jdt.prefs`](eclipse-jdt.prefs) задаёт узкую IDE-политику для
Eclipse JDT: игнорируется только `nullUncheckedConversion` на границе generic
method references и сторонних null-аннотаций. Реальные и потенциальные
null-доступы не отключаются. VS Code Java подключает файл через локальный
`java.settings.url`; путь должен указывать на текущий checkout.

```json
{
  "java.compile.nullAnalysis.mode": "automatic",
  "java.settings.url": "/absolute/path/to/ioc-extractor/tools/eclipse-jdt.prefs"
}
```

Начальная точка:

```bash
make help
make context
make doctor
make bootstrap  # repo-local static-musl lychee, если его нет в PATH
make dependency-analysis
make pmd-analysis
make pmd-watchlist
make spotbugs-baseline-proposal  # после SpotBugs ratchet failure
make release-notes-context PREVIOUS_TAG=v0.1.0
make pre-push
```

`make spotbugs-baseline-proposal` не запускает анализатор повторно и не меняет
tracked baseline. Команда читает уже сформированные module-local raw XML и пишет
только диагностическую разницу в
`target/build-quality/spotbugs-baseline-proposal.xml`: analyzer identity для
новых candidates и ID устаревших acceptances. В proposal намеренно отсутствуют
`disposition`, `owner`, `evidence`, review trigger, rationale и suppression;
эти решения добавляются человеком только в reviewed diff
`spotbugs-accepted-findings.xml`. Команду следует запускать сразу после
SpotBugs-отказа полного `make verify`; отсутствующий или повреждённый raw report
завершает proposal с ошибкой.
Полный lifecycle, identity contract и правила принятия finding описаны в
[build-quality capability](../docs/dev/build-quality.md).

`make dependency-analysis` последовательно собирает main/test bytecode с
`-DskipTests` и выполняет быстрый dependency-hygiene report для всех
функциональных JAR-модулей. Полный reactor path с тестами, SpotBugs и
CPD доступен через `./mvnw -B -ntp -T 1C -Pdependency-analysis verify`.
Findings остаются advisory: обычный `make verify` и CI не включают
профиль. Не заменяйте `-DskipTests` на `-Dmaven.test.skip=true`: второе
отключает компиляцию test bytecode и обедняет анализ scope.

`make pmd-analysis` запускает принятую blocking/advisory PMD source policy по
22 точным rules и 19 production `src/main/java` roots. Команда выбирает
`build-support/pmd-report` и его upstream reactor через `-pl ... -am`, чтобы
PMD aggregate mojo не конкурировал в parallel build с независимыми JaCoCo,
SpotBugs и CPD aggregators. XML/HTML появляются в
`build-support/pmd-report/target/pmd/`. Правила вне non-zero snapshot имеют
zero tolerance; для пяти разобранных advisory rules проверяются точные counts.
Любой count drift, scope/ruleset/engine drift, analyzer error или отсутствующий
report завершает команду ошибкой. Успешный policy run сохраняет отдельное
`last-pmd.env` evidence, которое `make context` показывает как `pmd.fresh`.
Регулярный CI запускает эту же policy отдельным job; обычный `make verify` PMD
source profile не активирует.

`make pmd-watchlist` тем же механизмом формирует отдельный отчёт в
`build-support/pmd-report/target/pmd-watchlist/` только для
`PreserveStackTrace`, `CloseResource` и `NcssCount`. Watchlist не входит в
регулярный CI и не является suppressions/baseline: его запускают при изменении
resource/exception ownership, lifecycle contracts, PMD/JDK или перед повторным
решением об adoption этих правил. Watchlist не обновляет PMD policy freshness.

`make pre-push` последовательно выполняет те же leaf scripts, а значит те же
Maven, shell-contract и offline-documentation gates, что обычный GitHub CI.
Workflows вызывают scripts напрямую, не зависят от Makefile и остаются
прозрачными.
Dependency-Check остаётся отдельным security gate: `make security-update`
явно обновляет NVD data по сети, а `make security-scan` быстро и детерминированно
анализирует reactor только по имеющейся локальной базе. Scheduled workflow
выполняет эти операции последовательно отдельными шагами.

Все runtime-файлы developer environment создаются только под `/.dev/` и
игнорируются Git. Скрипты не должны писать business data напрямую в SQLite:
fixtures проходят через публичные `extract`/daemon ingest пути.

## Поддерживаемое окружение

Tools-layer поддерживает **GNU/Linux**; проверяемая среда — Debian/Ubuntu с
Bash, GNU coreutils/findutils и Linux `/proc`. Это осознанно совпадает с
production deployment target. Некоторые команды дополнительно требуют `jq`,
`sqlite3`, `curl` или `shellcheck`; точный набор проверяет `make doctor`.

Native macOS и Windows не являются поддерживаемыми средами для этих scripts из-за
различий `realpath`, `find`, `tail` и process inspection. На таких хостах следует
использовать WSL, Linux VM или container, не заменяя GNU-команды несовместимыми
алиасами. Цвет developer-сообщений включается только для соответствующего TTY;
переменная `NO_COLOR` отключает его явно.
