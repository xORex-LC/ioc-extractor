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
make mutation-pilot
make stability-pilot SEED=42 REPEAT=3
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
22 точным rules и 25 production `src/main/java` roots. Команда выбирает
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

## Упаковка библиотек

`make library-bundle`, `make library-consumer` и `make library-publication-test`
проверяют независимую упаковку и публичные контракты. Параметр
`LIBRARY_COMPONENT` выбирает `concurrency` (по умолчанию), `diagnostics`, `logging`,
`configuration`, `events` или `events-spring`;
для каждого bundle задавайте отдельный `LIBRARY_BUNDLE`. Изолированные проекты
расположены в [fixtures](fixtures/README.md), процедура публикации — в
[руководстве](../docs/guides/library-publication.md). Требуются полный JDK 21
с `javadoc`, Python 3, GnuPG 2 и сеть для зависимостей пустого Maven-кэша.
Для logging запускаются отдельные SLF4J- и Boot-потребители с примерами из
руководств. Разрешена только зависимость `slf4j-api:2.0.18`; провайдер выбирает
потребитель. JDK-библиотеки concurrency, diagnostics и events не допускают зависимостей.
Селекторы events/events-spring пока квалифицируют только упаковку и проверку
manifest: подпись, публикация и consumer явно отклоняются до E4. Spring-адаптер
требует events-контракты той же версии, Spring Context 7.0.9 и SLF4J API 2.0.18;
каждый артефакт имеет отдельный bundle. В будущем сначала выпускаются контракты,
затем адаптер; отдельный выпуск в этом этапе не выполняется.

Для configuration проверяются инспекция/миграции, точный пример ограниченного
контекста, BOM приложения и отказы при несовместимом выборе зависимостей.
Resolver 1.9.22 использует явные списки групп для всех маршрутов репозиториев;
отдельные проверки доказывают запрет fallback нашей библиотеки при пустом
выбранном репозитории. Внешние зависимости и плагины доступны из Central.
