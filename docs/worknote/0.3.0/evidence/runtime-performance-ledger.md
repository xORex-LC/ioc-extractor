---
title: "0.3.0 runtime and performance evidence"
version: "0.3.0"
goal_id: "R030-BASE"
status: "Baseline captured"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# BASE-RUNTIME-07 — Runtime/performance baseline

Contract: [R030-BASE](../goals/R030-BASE-baseline.md).

Этот ledger фиксирует локальный comparative baseline для повторения на release
candidate. Он не задаёт SLA, production capacity или performance budget и не
разрешает optimization без отдельного analysis.

## Объект и окружение

| Поле | Значение |
|---|---|
| Commit исходного кода | `92b2e1001d8fdc217b297f248fed3b454902a7e9` |
| Maven revision | `0.3.0-SNAPSHOT` |
| Bootable JAR | `bootstrap/ioc-app/target/ioc-app-0.3.0-SNAPSHOT.jar`, `104632573` bytes |
| JAR SHA-256 | `871d15aaa72eee93b69de30103cb8b6f2541fae8903946e6be51b59650ee7cbe` |
| Runtime JDK | Eclipse Adoptium Temurin `21.0.11+10`, явно выбранный из `${JAVA_HOME}/bin` |
| ОС | Ubuntu `24.04.3 LTS` под WSL2, Linux `6.6.87.2-microsoft-standard-WSL2`, `x86_64` |
| CPU | 12 logical CPU, 6 cores / 12 threads, Intel Core i5-12400F |
| Память при capture | Всего `16619094016` bytes; swap не использовался |
| Время capture | `2026-07-28T20:12:50+08:00` |

CPU pinning, очистка caches, явные `-Xms`/`-Xmx` и benchmark harness не
использовались. Каждая extraction выполнялась в новой JVM и fresh workspace;
OS page cache мог оставаться прогретым между samples. Сохраняются все samples и
их range, а median не выдаётся за лабораторное latency measurement.

### Выбор Runtime JDK

На host доступны две Java 21 runtimes:

- Maven Wrapper разрешает `${JAVA_HOME}` в Eclipse Adoptium Temurin
  `21.0.11+10`;
- обычный `java` из `PATH` разрешается в Ubuntu OpenJDK Runtime
  `21.0.11+10`.

Developer runtime scripts вызывают `java` из `PATH`. Поэтому в принятых
измерениях `${JAVA_HOME}/bin` добавляется явно, чтобы Maven build и application
runtime использовали одну JVM family:

```bash
env PATH="${JAVA_HOME}/bin:${PATH}" java -version
```

Calibration runs до этого выбора отброшены. Будущие comparisons MUST
фиксировать и записывать runtime JDK, а не полагаться на ambient `PATH`.

## Представительные workloads

Оба input являются детерминированными HTML fixtures, созданными repository
generator с seed `42`, default duplicate rate `0.10` и default network defang
rate `0.35`.

| Workload | Input bytes | Rows | Unique input values | Duplicate rows | Defanged rows | SHA-256 |
|---|---:|---:|---:|---:|---:|---|
| `html-5k-seed-42` | `303111` | 5000 | 4513 | 487 | 899 | `962fc76536856744bf5b604187d73370d02dcc92a15bd7a5b8bf2064aa4d7413` |
| `html-100k-seed-42` | `6277629` | 100000 | 89885 | 10115 | 17501 | `84532e5fcb2b86bd90878af656a1afdb6e2e0c2105aafbea3fa6f77eff1a3fe2` |

Воспроизведение:

```bash
tools/dev/fixture.sh \
  --size 5000 \
  --seed 42 \
  --output .dev/performance/base-runtime-07/ioc-5000-seed-42.html \
  --manifest .dev/performance/base-runtime-07/ioc-5000-seed-42.manifest.json

tools/dev/fixture.sh \
  --size 100000 \
  --seed 42 \
  --output .dev/performance/base-runtime-07/ioc-100000-seed-42.html \
  --manifest .dev/performance/base-runtime-07/ioc-100000-seed-42.manifest.json
```

Workload 5k — обычный повторяемый developer case. Workload 100k — bounded scale
case, который проверяет extraction, classification, canonical SQLite commit и
полную mutable CSV reprojection без отдельного synthetic benchmark path.

## Команды измерения

Каждый oneshot sample использует независимый workspace и включает JVM startup,
Spring composition, Tika parsing, полный pipeline, SQLite commit и mutable
projection:

```bash
perf_source="$(realpath -e .dev/performance/base-runtime-07/ioc-5000-seed-42.html)"
perf_jar="$(realpath -e bootstrap/ioc-app/target/ioc-app-0.3.0-SNAPSHOT.jar)"

/usr/bin/time \
  -f 'real_s=%e\nuser_s=%U\nsys_s=%S\nmax_rss_kib=%M\nexit=%x' \
  -o .dev/performance/base-runtime-07/temurin/oneshot-5k-run1/extract-time.txt \
  env PATH="${JAVA_HOME}/bin:${PATH}" \
  tools/dev/app.sh \
    --workspace .dev/performance/base-runtime-07/temurin/oneshot-5k-run1 \
    --jar "${perf_jar}" \
    extract --source "${perf_source}"
```

Та же форма повторяется для runs `1..3` и source 100k. Export измеряется один
раз из завершённого 100k workspace:

```bash
/usr/bin/time \
  -f 'real_s=%e\nuser_s=%U\nsys_s=%S\nmax_rss_kib=%M\nexit=%x' \
  -o .dev/performance/base-runtime-07/temurin/oneshot-100k-run1/export-time.txt \
  env PATH="${JAVA_HOME}/bin:${PATH}" \
  tools/dev/app.sh \
    --workspace .dev/performance/base-runtime-07/temurin/oneshot-100k-run1 \
    --jar "${perf_jar}" \
    export --profile reputation-lists
```

Daemon startup — elapsed time от вызова repository runtime wrapper до успешного
loopback actuator health gate:

```bash
/usr/bin/time \
  -f 'real_s=%e\nuser_s=%U\nsys_s=%S\nmax_rss_kib=%M\nexit=%x' \
  -o .dev/performance/base-runtime-07/temurin/daemon-run1/startup-time.txt \
  env PATH="${JAVA_HOME}/bin:${PATH}" \
  tools/dev/runtime.sh \
    --workspace .dev/performance/base-runtime-07/temurin/daemon-run1 \
    --port 18271 \
    --jar "${perf_jar}" \
    --health-attempts 30 \
    --health-interval 1 \
    --set ioc.ingestion.detect.use-watch-service=false \
    up
```

Разрешение startup включает секундную health-poll cadence wrapper.
Memory из `/usr/bin/time` для этой команды относится к wrapper и не используется
как daemon memory evidence. Сразу после состояния healthy значения RSS/threads
прочитаны из `/proc/<pid>/status`, file descriptors — из `/proc/<pid>/fd`, а
heap/metaspace — через `${JAVA_HOME}/bin/jcmd <pid> GC.heap_info`. Evidence из
`/proc` снято до подключения `jcmd`.

## Результаты extraction

Все принятые процессы завершились с кодом `0`, не выдали error/exception signal
и сформировали одинаковые canonical counts и per-artifact CSV SHA-256 внутри
каждого workload.

| Workload | Run | Real time | User time | System time | Peak RSS |
|---|---:|---:|---:|---:|---:|
| 5k | 1 | `5.20 s` | `15.28 s` | `0.78 s` | `361488 KiB` |
| 5k | 2 | `3.37 s` | `15.08 s` | `0.59 s` | `374344 KiB` |
| 5k | 3 | `4.92 s` | `15.15 s` | `0.61 s` | `366668 KiB` |
| **5k median/range** | — | **`4.92 s`** (`3.37–5.20`) | — | — | **`366668 KiB` / `358.1 MiB`** (`353.0–365.6 MiB`) |
| 100k | 1 | `14.83 s` | `31.94 s` | `1.71 s` | `763596 KiB` |
| 100k | 2 | `13.55 s` | `29.71 s` | `1.82 s` | `908128 KiB` |
| 100k | 3 | `14.97 s` | `32.40 s` | `1.65 s` | `750260 KiB` |
| **100k median/range** | — | **`14.83 s`** (`13.55–14.97`) | — | — | **`763596 KiB` / `745.7 MiB`** (`732.7–886.8 MiB`) |

Median end-to-end rate составляет приблизительно 1016 input rows/s для 5k и
6743 input rows/s для 100k. На разницу в том числе влияют фиксированная стоимость
JVM/Spring startup и прогретые host caches; это не является доказательством
super-linear pipeline scaling и MUST NOT экстраполироваться как production
capacity.

### Correctness и детерминированный output

| Workload | `masks` | `ip_list` | `hashes` | Canonical total | `address_blacklist` |
|---|---:|---:|---:|---:|---:|
| 5k | 1504 | 753 | 2256 | 4513 | 2257 |
| 100k | 29962 | 14981 | 44942 | 89885 | 44943 |

Canonical total равен `masks + ip_list + hashes` и совпадает с unique input
count fixture. `address_blacklist` является отдельной projection всех network
values и поэтому не добавляется к этому total.

Per-artifact hashes совпали во всех трёх runs:

| Workload | address blacklist | hashes | IP list | masks |
|---|---|---|---|---|
| 5k | `8046bfa3e109…` | `e01b8d4122ab…` | `ce9a053f494f…` | `5a1303368588…` |
| 100k | `e76c92b1b599…` | `10406b5ff278…` | `21e07d47cd53…` | `2b89da6080a0…` |

## Результат export

Один immutable export `reputation-lists` из 100k canonical store:

| Metric | Result |
|---|---:|
| Exit | `0` |
| Real time | `4.27 s` |
| User/system time | `12.32 s` / `0.65 s` |
| Peak RSS | `355968 KiB` / `347.6 MiB` |
| Exported canonical rows | 89885 |
| Export files | three CSVs + `manifest.json` + `_SUCCESS` |
| Export slice bytes | `8904191` |

Profile намеренно исключает `address_blacklist`; три exported CSV
byte-identical соответствующим mutable projections в том же workspace.

## Daemon startup и idle resources

Каждый run использовал fresh workspace, отдельный loopback port, polling
detection, default application configuration и отключённый remote sync. После
snapshot процесс был штатно остановлен.

| Run | Healthy startup | RSS / high-water | Threads | FDs | Heap committed | Heap used | Metaspace used |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | `6.29 s` | `384776 KiB` | 45 | 21 | `131072 KiB` | `41631 KiB` | `66812 KiB` |
| 2 | `4.67 s` | `359584 KiB` | 45 | 21 | `118784 KiB` | `53836 KiB` | `66856 KiB` |
| 3 | `6.29 s` | `397344 KiB` | 45 | 21 | `131072 KiB` | `40751 KiB` | `66824 KiB` |
| **Median/range** | **`6.29 s`** (`4.67–6.29`) | **`384776 KiB` / `375.8 MiB`** (`351.2–388.0 MiB`) | **45** | **21** | **`131072 KiB` / `128 MiB`** (`116–128 MiB`) | **`41631 KiB` / `40.7 MiB`** (`39.8–52.6 MiB`) | **`66824 KiB` / `65.3 MiB`** |

JVM сообщила приблизительно 8.3 GiB virtual address space в `VmPeak`. Это
reserved address space, а не resident memory или committed heap, поэтому оно
исключено из resource comparisons.

## Размер storage/output

| Workload/state | Dataframe SQLite | Service SQLite | Mutable projections | Immutable export |
|---|---:|---:|---:|---:|
| 5k после extraction | `2568192` bytes | не создана | `520744` bytes | не выполнялся |
| 100k после extraction + export | `49721344` bytes | `110592` bytes | `10676261` bytes | `8904191` bytes |

Service database появляется в строке 100k, потому что export использует durable
service coordination state. Для одной oneshot extraction она не требуется.

## Интерпретация и dispositions

1. Измерения задают локальную точку сравнения, а не release thresholds. Более
   медленный или ресурсоёмкий release candidate требует analysis и принятого
   disposition, но не проваливается по выдуманному проценту.
2. Case 5k чувствителен к startup. Все три samples и их range должны оставаться
   видимыми; один самый быстрый run не является валидным evidence.
3. Range peak RSS для 100k заметно шире range elapsed time. В будущем перед
   привязкой изменения к application code нужно сравнивать и время, и memory.
4. Явный heap cap не использовался. Будущее изменение JVM ergonomics, container
   limits или `-Xms`/`-Xmx` делает прямое сравнение memory некорректным, если оно
   не записано.
5. Расхождение JVM между ambient `PATH` и `${JAVA_HOME}` — seam
   воспроизводимости environment. Для candidate measurement `R030-REL` должен
   закрепить runtime JVM; изменение developer tooling не входит в
   `BASE-RUNTIME-07`.
6. Live SMB latency/throughput, long-running daemon soak и capacity production
   host здесь не представлены. Если изменение 0.3.0 затрагивает эти пути,
   owning goal должен добавить targeted measurement, а не экстраполировать этот
   local baseline.

## Правило сравнения с release candidate

`R030-REL` MUST повторить те же fixture hashes, artifact profile, выбор runtime
JDK и процедуру fresh workspace. Для extraction выполняются три samples с
публикацией каждого значения, median и range. Export и daemon resource snapshots
должны иметь ту же measurement boundary. Любая regression или unavailable
measurement получает явный disposition.

## DATA-TTL-01 P6 lifecycle profile

Targeted lifecycle evidence дополняет, но не заменяет BASE-RUNTIME-07. На clean
commit `b5bdd1a10802b9f5b7158d2e39ed9d34c2d98537` команда
`make lifecycle-load` провела `100001` canonical artifact rows через daemon
ingestion, expiry, typed history и retention purge с launcher-equivalent JVM
flags `-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError`.

| Metric | Result | Regression guard |
|---|---:|---:|
| expiry start after earliest deadline | `1001 ms` | `≤ 5000 ms` |
| archive/drain throughput | `10984.29 rows/s` | `≥ 2500 rows/s` |
| history retention drain | `40602 ms` | `≤ 180000 ms` |
| maximum JVM high-water | `571144 KiB` | `≤ 1048576 KiB` |

Все четыре artifact expiry и retention paths использовали covering indexes;
minimum bounded expiry transaction count составил `103`. Floor `2500 rows/s`
равен примерно четверти qualifying measurement и предназначен для обнаружения
крупного algorithm/query-plan regression, а не для объявления production SLA.
Полный environment, correctness assertions и calibration disposition:
[P6 load profile](../data-ttl-01/evidence/p6-load-profile.md).

## Завершение

- [x] Runtime JDK и host environment зафиксированы
- [x] Детерминированные representative fixtures зафиксированы
- [x] Повторные extraction 5k и 100k измерены
- [x] Canonical counts и детерминированные projections сверены
- [x] Representative export измерен
- [x] Daemon healthy startup измерен
- [x] Heap, RSS, threads и file descriptors зафиксированы
- [x] Размеры DB и output зафиксированы
- [x] Правило сравнения с release candidate определено
