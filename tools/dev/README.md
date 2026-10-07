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
| `ioc-aggregate-load.sh …` | Сравнить pre-feature JAR и aggregate candidate на одном duplicate-heavy daemon input; измерить end-to-end/write latency, VmHWM и query plans |
| `router-qualification.sh --size 1000|100000` | Измерить синтетический Camel Router по матрице ветвей, потоков и исходов; отчёты в `.dev/router-qualification` |
| `processing-route-comparison.py …` | Попарно сравнить совместимый и выбранный путь на одинаковом документе с повторами и processed import; проверить равенство результата и измерить время, throughput, allocations, heap/RSS |
| `processing-stage-capacity.py …` | Измерить CAP-3 attribution/diagnostics в попарных JVM со снимками классов; автоматически удалить временные файлы |
| `document-workspace-capacity.py …` | Диагностика CAP-4: инкрементальный дисковый reducer, canonical writer и receipts; удалить все временные базы даже при отказе |
| `service-capacity.py …` | CAP-6: отдельный user-systemd, фактические cgroup limits, прогретый полный локальный цикл, дисковый oracle и очистка |
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
make ioc-aggregate-load SIZE=100000 DUPLICATE_RATE=0.95 BASELINE_JAR=/path/to/pre-feature.jar
make router-qualification SIZE=100000
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
загружает закреплённый static-musl release для Linux x86_64/aarch64, проверяет
коммитнутый SHA-256 и атомарно устанавливает binary под `.dev/tools/bin` без
`sudo`, Snap, Rust toolchain или зависимости от версии host glibc. System-wide
`lychee` из `PATH` также поддерживается.

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

IOC aggregate load harness также является opt-in квалификацией. Он генерирует
один детерминированный документ с высокой долей повторов, пропускает его через
публичный daemon ingest и сохраняет полный ECS/console evidence. При переданном
baseline JAR тот же input сначала обрабатывает pre-feature версия на том же
хосте; candidate проверяет five-column projection, one-carrier invariant,
terminal registration, ordered-field origins и индексированные планы запросов.
По умолчанию end-to-end regression ограничен 2x, а `VmHWM` — systemd
`MemoryMax=1GiB`; изменение envelope требует сохранённого измерения и review.

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

Router qualification запускает синтетические операции без IOC под фиксированным
`-Xms128m/-Xmx512m`. Корректность матрицы 1k также проверяет Surefire;
профиль 100k запускается отдельно. CSV и метаданные остаются в
`.dev/router-qualification`. Его throughput и allocation нельзя сравнивать с
нынешним IOC preparer до появления документной и импортной интеграции.

Режим `--capacity --shape mixed|domains` включает fixed lifecycle и проверяет
активные canonical rows, все public fields и complete row keys независимым
oracle. Реальные результаты читаются cursor-ом после окончания измерения;
документные времена стадий берутся из production pipeline observer. Для
сравнения CAP-1A/CAP-1B доступны profiles `capacity-10k` и `capacity-100k`:
fresh JVM, empty private stores, без прогрева входными файлами. Эти профили
не заменяют отдельную проверку all-five AS_IS import и полного SMB-цикла.

`make data-processing-capacity CAPACITY_ARGS='--mode sql --runtime .dev/FROZEN
--workspace .dev/NEW-SQL-EVIDENCE'` запускает actual packaged matcher с точным
JDBC driver на 1k/10k/100k unrelated aliases. SQL fixture является private
mechanism experiment, а не способом заполнения business databases.

Режим `--mode stand` дополнительно требует `--config`, `--environment` и
`--document`; `--imports` добавляет пять AS_IS SMB deliveries. Он запускает
отдельный daemon с private SQLite/cwd, двумя CPU affinity и собственным SMB
namespace; policy/cadence сохраняются. В evidence явно отделены affinity от
cgroup quota, process RSS от heap/cgroup charge и полный SMB/readback cycle от
pipeline stage times. Полный oracle проверяет все public fields/keys,
provenance, manifest hashes, revisions и sparse import slots. Неизвестные
fixture hosts отклоняются: oracle поддерживает закреплённый `.example.test`
corpus и малый `example.org` fixture, а не произвольную PSL.

Во всех трёх processing comparison/capacity harness временные SQLite базы,
WAL/SHM, staging и повторные CSV удаляются после проверок каждого завершённого
JVM fork, включая ошибочный запуск. Сохраняются входы, конфигурация, логи,
измерения и сжатый полный oracle (`signature.json.gz`); явные diagnostic/JFR
запуски сохраняют JFR. `--retain-state` оставляет базы и CSV только по явному
запросу. Stand по умолчанию записывает manifests состояния без копирования
баз; выбранные SQLite snapshots сохраняются сжатыми. Одинаковые oracle
signatures сохраняются один раз и доступны каждому fork через hard link;
phase harness держит digest/counts вместо всех декодированных signatures.
Новый fork требует
минимум 1 GiB свободного места. Очистка ограничена помеченным private
workspace, не проходит по symlinks и не затрагивает frozen runtime/resources.
`--mode sql --database .dev/STATE.db` дополнительно проверяет планы на
read-only public-path состоянии; VM counters при этом относятся только к
отдельной mechanism fixture. Планы и business workload timings не смешиваются.

`make processing-stage-capacity STAGE_CAPACITY_ARGS='--baseline REV --output
PATH.json --counts 100000 --large-attribution-count 1000000 --pairs 5'` измеряет
отдельные механизмы CAP-3: ordered/unordered attribution и construction-time
diagnostics. Перед запуском закоммитьте изменения и выполните `make test-one
MODULE=bootstrap/ioc-app TEST=ProcessingRouteComparisonTest`. Скрипт компилирует
затронутые классы из baseline и текущих исходников, фиксирует общие классы и
RE2/J, чередует JVM before/after и проверяет signatures всех результатов.
Каждая JVM выполняет полный прогрев перед измерением; Java agent отсутствует.
Временные снимки классов автоматически удаляются, базы не создаются. JSON
содержит время, caller allocations, sampled phase heap/current RSS и retained
heap после GC (включая fixture и конечный результат). Это измерение стадий,
а не полный цикл сервиса. `--profiles` выбирает отдельные стадии; нестандартный
Maven-кэш можно указать через `--re2j-jar`.

`processing-route-comparison.py` собирает test probe вместе с reactor и запускает
Router в отдельных JVM/SQLite/workspace. После полного перехода на Router
прогоны всегда `--selected-only`: совместимый движок удалён, новые отношения
к нему не вычисляются. Старые парные отчёты остаются историческими измерениями
соответствующих ревизий. Отчёт содержит все наблюдения и медианы; проверяются
public CSV, канонические ключи, импортные поля, receipts и COALESCE-статусы.
Время включает синхронную обработку после старта Spring; аллокации охватывают
вызывающий поток. Подготовка JVM и конфигурации не входят во время.


Профиль можно задать через `make processing-route-comparison
COMPARISON_ARGS='--pairs 5 --document-unique 4000 --import-unique 1000 --warmups 1
--workspace .dev/comparison-warm'`. Число уникальных значений задаётся отдельно
для документа и импорта; прогрев использует новые ключи и доставки, затем
измеряется новая вставка. Измеритель проверяет неизменность исходников при
сборке и запускает копию собранного JAR/ресурсов, изолированную от последующих
Maven-сборок. Сохраняются идентичность ревизии, digests, разброс, попарные
отношения, startup/GC, текущий RSS и исторический VmHWM. Аллокации остаются
метрикой вызывающего потока. Старые пороги являются регрессионными границами;
согласованный бюджет производительности ими не определяется.

Отдельный режим `COMPARISON_ARGS='--diagnostics --pairs 5 --workspace
.dev/comparison-diagnostic'` включает только тестовый Java agent и JFR. Он считает
вызовы парсера, PSL, классификации, достигнутых предикатов, views, mapping и
Camel sends; фиксирует подготовленные строки и резервируемые ID. Время подготовки
измеряется вокруг целого документного stage либо суммарно вокруг processed
preparer каждой импортной строки. Прогрев и startup исключены из счётчиков.
Инструментатор использует уже имеющийся Spring ASM и не подменяет компоненты.
Основные замеры запускаются без agent/JFR. Накладные расходы диагностического
режима не являются бюджетом сервиса: превышение старых ресурсных границ в этом
режиме сохраняется в отчёте, но не отклоняет профиль. Отсутствующие замеры,
ошибки инструментатора, семантическое расхождение и ошибка процесса отклоняют
его всегда. `diagnostic.jfr` и полные логи остаются в игнорируемом workspace.

Дополнительные профили comparison: `--shape mixed|long` и
`--workload document|import|both`. Mixed/long используют исходное представление
в выбранном маршруте, чтобы сравнивать одинаковые поля с совместимым путём;
это отдельный профиль, а не измерение очистки host. Прогрев для всех типов
генерирует отдельные валидные ключи, включая IPv4 и хеши. Полный fixture и
конфигурация сохраняются в workspace, доля повторов и выбранная семантика — в
JSON. `samples.json` сохраняет завершённые forks по мере выполнения;
`failure.json` закрепляет ошибку и уже полученные samples, не превращая
неполный профиль в успешный. Neutral Router profile включает также 64 ветви;
его конкурентные callers не являются доказательством throughput всего сервиса.

`--shape host-collapse --selected-only` измеряет массовую очистку разных URL
до 20 host/IP через настоящую host-ветвь. Например:
`--document-rows 8000 --document-unique 2000 --import-rows 2000 --import-unique 500`.
`--collapse-hosts` задаёт положительное чётное число конечных адресов; половина —
домены, половина — IPv4. URL содержат HTTP/HTTPS, порты, пути, query и fragment.
Документ повторяет каждый URL под двумя source-маркерами и проверяет KEEP_FIRST
и LAST_NONEMPTY, конечные поля/ключи и provenance. Импорт использует два label
при фиксированном label для каждого host, чтобы COALESCE оставался совместимым.
Квалификационная policy явно допускает очищенные IPv4 в primary masks.
Этот профиль сравнивает selected-реализации до/после с одинаковыми YAML и inputs;
совместимый путь без очистки не имеет эквивалентной семантики и не измеряется.
Не существующие compatible ratios остаются null; ресурсная приёмка не оценивается.
Число URL должно быть кратно числу host; document rows — 4×числу URL, import rows
— 2×числу URL или кратное им. Прогрев использует отдельные конечные host/IP.

`make processing-optimization-comparison` сравнивает две зафиксированные сборки
из workspaces `processing-route-comparison`, чередуя before/after внутри каждой
пары. Например: `COMPARISON_ARGS='--reference .dev/before --candidate .dev/after
--workspace .dev/optimization --pairs 5'`. Профили `repeat`, `unique`, `collapse`
используют одинаковую selected-policy, отдельный прогрев и полный signature,
включая diagnostics. Новых сборок во время измерений нет. JSON сохраняет SHA-256
каждого runtime-файла, порядок запусков, samples и отношения внутри пар.
`--diagnostics --agent .dev/comparison-diagnostics.jar` задаёт один observer для
обеих версий; этот режим запускают отдельно. `partial.json` и `failure.json`
сохраняют незавершённые серии. `--profile` и `--workload` ограничивают отдельный
эксперимент; эти замеры не устанавливают клиентский ресурсный бюджет.

`make document-workspace-capacity WORKSPACE_CAPACITY_ARGS='--output PATH.json'`
проверяет 10k → 100k → 1m строк на артефакт при двух артефактах,
2 MiB общем workspace-бюджете, 64 KiB SQLite cache и JVM heap 64 MiB.
Проба генерирует кандидатов инкрементально, проверяет полные KEEP_FIRST /
LAST_NONEMPTY строки, positions, canonical commits и повторяемые receipts.
Это диагностика live-memory с явным GC, а не полный SMB/Router benchmark.
Заморозка compiled runtime и базы каждого fork удаляются в `finally`, включая
отказ oracle и timeout; сохраняется только компактный JSON. Перед запуском
нужны committed tree и скомпилированные main/test classes.
Отдельные `upstream_samples` измеряют настоящие Spring/Tika/read/refang/extract/
attribute стадии на 10k/100k/1m occurrences с heap 512 MiB. Их память учитывается
отдельно от workspace; `--upstream-sizes` без значений отключает эту серию.
