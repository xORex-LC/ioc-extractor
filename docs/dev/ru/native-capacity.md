# Ёмкость Native SQLite и границы проверок

[English version](../native-capacity.md)

Документ сохраняет подтверждённые затраты выбранной модели обработки/хранения
Native и границы этих свидетельств. Он объясняет, как оценивать нагрузку,
различая замеры этапов, задержку полного сервиса и память JVM/cgroup.
Переносимая гарантия throughput и политика приёмки ресурсов заказчика ему не принадлежат.

## Поток измерений и границы

```text
owned physical input -> source processing -> canonical mutation/receipt
  -> mutable CSV -> immutable snapshot -> durable SMB publication
  -> independent readback and receipt recovery checks
```

Считайте входные IOC occurrences **до** дедупликации отдельно от итоговых строк
артефактов и физических строк SQLite. Время canonical completion, durable remote
completion и независимого readback указывайте отдельно. Локальный processed
import, завершившийся export, не является измеренным SMB-циклом. Сохранённые
revision timestamps отражают эффективное время транзакции, а не завершение commit.

Основные замеры исключают instrumentation, если это предусмотрено конкретной
серией; diagnostic JFR/agents, startup и последующие receipt/cleanup probes имеют
свои области. Calling-thread allocations, cumulative allocations всей JVM,
sampled live heap, текущий RSS, lifetime VmHWM и shared cgroup charge не
взаимозаменяемы. Shared charge включает collectors и файловый cache.
Cumulative allocations отражают оборот выделений, а не одновременно удерживаемую RAM.

Временный implementation/experiment bundle и его измерительные программы удалены
после выбора Native. Таблицы ниже сохраняют рассмотренные факты, идентичности
измерений и решения по неудачным screens. Это исторические наблюдения конкретных
бинарных сборок, а не свежая нагрузочная проверка каждого последующего cleanup
commit. Raw benchmark files не являются версионируемыми артефактами репозитория.
Поддерживаемые smoke/load команды остаются в [developer tools](../../../tools/dev/README.md).

## Текущие ограничения исполнения и допуска

Canonical backend — Native SQLite, выбираемый через `ioc.storage.dataframe.type=jdbc`.
`native` не является значением конфигурации или вторым storage selector. Подготовка
документа использует отдельный private workspace; managed CSV import имеет свои
snapshot/staging limits. Эти бюджеты не резервируют место для публичных БД,
immutable exports, архивов ingestion или backup.

Детектор daemon остаётся последовательным. Preparation workers и dispatch window
ограничивают одновременно подготавливаемые документы и ready references; только
самый старый незавершённый документ может перейти к promotion. Эффективное window
также ограничено общей ёмкостью workspace factory. Увеличение detector concurrency
не является поддерживаемым способом повышения throughput. См.
[ingestion](../ingestion.md#admission-and-pressure).

Workspace `memory-bytes` — оценочный резерв допуска. Текущая оценка одной lease:
`cache-kib * 1024 + maximum-row-bytes * 64 + 65536`; factory допускает не более
`min(128, memory-bytes / leaseBytes)` workspaces. Штатные 64 MiB, cache 4096 KiB
и row limit 256 KiB допускают три lease, поэтому configured window четыре
фактически ограничено тремя. Это не JVM heap cap и не измерение parser objects,
SQLite/native memory или RSS. Увеличение row/cache limits расходует больше этого
бюджета допуска.

Disk admission резервирует полное значение `workspace-bytes` для каждого live pin
и учитывает сохранённые неактивные pins в `total-disk-bytes`. Копирование source
имеет отдельный предел в четверть workspace. Ограничения row/field, SQLite page
ceilings и повторные filesystem checks отвергают слишком большую работу; quota
failure возможен при свободном месте на host. Эти проверки не являются filesystem
quota или гарантией preallocation. UTF-8 expansion полей/строк, текст source и
candidate occurrences существенны даже при малом числе итоговых ключей после reduction.

Document path передаёт decoded/refanged text и ordered occurrences через
disk-backed cursors. HTML удаляет завершённые поддеревья, DOCX использует SAX,
но слишком большой HTML repair region всё равно отвергается до promotion.
Bootstrap также использует числовое значение `maximum-row-bytes` как бюджет
символов HTML parser region; его увеличение меняет и parser admission, и encoded
rows. Нельзя переносить HTML/DOCX bounds на любой автоматически распознанный
формат Tika. Удобный reader API, возвращающий String, по-прежнему материализует
текст; production document path использует writer overload. См.
[processing](../processing.md#preparation-and-bounded-data).

Закрытие sealed workspace освобождает lease, но может сохранить replay files.
Новая unsealed и явно discarded работа очищаются при close. Просроченные неактивные
pins удаляются при открытии нового workspace; `retention=24h` не означает отдельный
cleanup timer или обещание уменьшения idle disk usage через 24 часа. Live leases
не удаляются. При разборе давления нужно соблюдать recovery ownership; правка
private pins не является поддерживаемым способом освободить ёмкость.

Canonical writer admission не вытесняет активную операцию. Приоритет/aging
влияет на следующую операцию, а не на уже выполняющуюся artifact transaction.
Wait/hold counters — process-local telemetry допущенной операции, не длительности
SQL locks, percentiles или deadlines. Число завершённых операций включает
неудачные допущенные вызовы, а не только успешные commits. `busy_timeout` не
ограничивает ожидание Java admission. Document writes фиксируются по артефакту; managed import
фиксирует принятые cross-artifact mutations вместе. Сохраняйте эти границы
атомарности при оценке control latency и partial failures.

Export snapshot readers имеют bounded admission и могут удерживать WAL history.
Закрытие освобождает это владение; `journal_size_limit` tuning preset управляет
сохраняемым размером после допустимого reset/checkpoint, а не максимальным live
WAL size. Cache/mmap settings также не ограничивают всю память приложения.
Оставляйте место для перекрывающихся readers, writers и cleanup; см.
[SQLite PRAGMA reference](https://www.sqlite.org/pragma.html#pragma_journal_size_limit).

Daemon health `dataProcessingCapacity` показывает dispatch counts/oldest age,
writer wait/hold totals и maxima в наносекундах, queued writers, snapshot readers
и cached workspace pressure. `preparationPressure.lastCheckedDiskBytes` —
последнее quota observation, не новый scan или сумма reserved disk; `walBytes` —
размер настроенного dataframe WAL, не общий объём обеих БД. Contributor возвращает
DOWN при stopped/blocked dispatch или inspection failure. Рост очереди, quota
pressure или большая control delay сами по себе могут не менять UP.
Оценивайте их за объявленный интервал по
[sizing guide](../../guides/ru/bulk-resource-sizing.md#наблюдение-за-прогоном).
Health не является подтверждением завершения каждого документа.

## Историческая калибровка lifecycle-load

Прогон lifecycle P6 от 2026-08-19 установил привязанные к хосту регрессионные
пределы, которые ещё выбирает `make lifecycle-load`. Использованы source commit
`b5bdd1a10802b9f5b7158d2e39ed9d34c2d98537`, чистое дерево и JAR SHA-256
`84c5a76957c8cf5ee1d0f615fe5155162718d8c41631ab085430e9a7e8fbc299`.
Хост: x86_64 WSL2 Linux `6.6.87.2`, OpenJDK `21.0.11`, SQLite `3.45.1`,
12 логических CPU и приблизительно 15.5 GiB RAM. JVM использовала
`-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError`.

Штатный daemon ingestion 66 667 входных строк создал 100 001 canonical artifact
rows при mapping той версии. Это исторический зависящий от mapping счётчик,
а не ожидаемое число для любой последующей конфигурации артефактов.

| Метрика | Результат P6 | Опорная граница |
|---|---:|---:|
| Canonical active rows | 100 001 | не менее 100 000 |
| Разброс deadlines | 5 306 ms | не более 30 000 ms |
| Archive/drain throughput | 10 984.29 rows/s | не менее 2 500 rows/s |
| Retention restart-to-drain | 40 602 ms | не более 180 000 ms |
| JVM lifetime VmHWM | 571 144 KiB | не более 1 048 576 KiB |

Порог 2 500 rows/s — около 23% квалификационного результата и ниже более медленной
диагностической калибровки; он выявляет крупные регрессии, не обещая переносимую
ёмкость. Retention limit является регрессионной границей, не production SLA.
Прогон проверял expiry, history, проекции и query plans; старое утверждение
о непереиспользовании публичных ID заменено в P7 стабильными переиспользуемыми
export slots. Непереиспользование внутренних canonical/lifecycle ID остаётся
отдельным контрактом.

Этот rootless-прогон не квалифицирует packaged upgrade/rollback или текущий source.
Нынешний terminal-filename mismatch TOOL-10 по-прежнему требует исправления
harness и переквалификации, как описано ниже. Идентичности квалификационных
кандидатов и границы замены прежних утверждений сохраняют
[свидетельства релиза 0.3.0](../../../.github/release-evidence/ru/0.3.0.md).

## Исходный инцидент и подтверждённые исправления

2026-10-04 установленный release `860a3b2bdf3c-20261004T082925Z`
(JAR SHA-256 `8a1ddccb7ff669a56271719824e474ea1d44b30ab914cb027eb3ee28a1c12e43`)
обрабатывал `ioc-100000-seed-43.html`: 100,000 occurrences, 89,917 уникальных
входных значений и шесть типов, 6,277,616 bytes, input SHA-256
`8e27297b0aed5bc6e4a9dad1e1c81e55caa09aeb8696328fdbaa42d6e4a2ec9b`.
Ingest шёл с 08:59:56.153432238Z до 09:36:52.064Z; физический aggregate CSV
и `_SUCCESS` проверены. Этот инцидент длительностью примерно 36 минут 56 секунд
не был чистым empty-database benchmark или таймером от SMB upload до completion.
Его нельзя делить на более поздние million-input results для заявления ускорения.

Старый matcher сочетал per-row calls с alias-wide query plan. Mechanism probe
на точном driver со 100,000 посторонних aliases измерил 753.919 ms на 32 запроса
для этого query, 4.378 ms для request-first join и 0.855 ms для complete-key direct
lookup. Это свидетельство SQL-механизма, не throughput всего сервиса. Native
теперь использует indexed exact material checks и принадлежащие транзакции
matching/mutation statement sessions.

Другие сохранённые изменения: bounded source streaming, disk reduction,
bounded semantic reuse, prebound mapping/conditions, native sequential Camel
dispatch, ordered receipt-position streaming и меньше row-decoder temporaries.
Они не отменяют обработку значимых occurrences и атомарный commit
canonical/provenance/receipt facts. Текущие алгоритмы описаны в
[processing](../processing.md) и [storage](../storage.md).

## Исторические сравнения обработки

URL -> host/IP серия использовала production Spring binding/preflight, реальные
extractor/classifier/Camel/mapping, URL ports/paths/query/fragment, повторные URL,
разные sources и 20 итоговых domain/IP keys. Production before — `08d30621`,
after — `c279f885`, с одинаковым probe/resources overlay. Шестьдесят основных JVM
forks и четыре отдельных diagnostic прогона прошли независимые проверки
fields/keys/winner, receipt и provenance. В таблице — медианы пяти forks.

| Профиль | Before/after time ms | Before/after caller allocation MB | Before/after sampled heap MiB | Before/after current RSS MiB |
|---|---:|---:|---:|---:|
| Первый документ, 8k occurrences | 1,724.1 / 1,565.8 | 476.7 / 356.3 | 116.2 / 113.1 | 371.4 / 380.8 |
| Первый import, 2k rows | 1,459.4 / 1,218.0 | 119.4 / 94.4 | 111.6 / 110.5 | 372.9 / 373.1 |
| Прогретый документ, 8k occurrences | 795.3 / 695.1 | 433.7 / 314.2 | 130.9 / 120.2 | 404.6 / 382.3 |
| Прогретый import, 2k rows | 750.4 / 679.9 | 104.6 / 80.4 | 114.8 / 112.6 | 394.9 / 379.2 |
| Большой прогретый документ, 100k occurrences | 6,359.4 / 5,732.6 | 5,147.8 / 3,668.4 | 499.7 / 266.6 | 772.0 / 573.8 |
| Большой прогретый import, 10k rows | 1,904.9 / 1,672.8 | 480.5 / 361.4 | 114.5 / 114.3 | 395.2 / 395.7 |

Allocation MB — десятичные cumulative значения. Эти revision-block measurements
не являются alternating crossover pairs; они включают synchronous processing
после Spring startup, но не полный transport/service cadence. Сравнения с
uncleaned path нет: у него другая output semantics. Resource acceptance остаётся
отдельной ([OUT-4](../../KNOWN-ISSUES.md)).

Native sequential Camel prototype использовал три alternating local service
pairs при одинаковых heap 512 MiB и cgroup limits 768 MiB/1 GiB. Reference/prototype
JAR hashes: `0314687cfa6f6278af7696ec3c08c086005ac4754fba72e59c9567dea899383c`
и `e2242a33651d0d96cc4e996439364b4001a96f04fb80729cf712038ab3b505c6`.
Медиана service window — 62.764/60.043 s; CPU — 54.460/52.200 s; sampled RSS —
388,268,032/382,726,144 B; sampled heap — 89,723,187/88,567,193 B. Итоговая
packaged implementation отдельно измерила 62.005 s и сохранила проваленные
legacy time/writer-hold screens. Отношение prototype не переименовывается
в свидетельство итоговой реализации.

## Сравнение bounded Native decoder

Одно сохранённое production изменение (`36672551`) уменьшило transient row decoding.
Before JAR на `22467cdd`: SHA-256
`1cfe167d9ba00f77c668361f9defce803c281ee2ad6319f3263e5ca1b4cec393`.
After JAR на `d13c3a7b`: SHA-256
`e9e49a2b1cd93e2243354021187e254945da85ef509a11591467ca84ccaa5b47`.
Различался только production bytecode `DocumentRowCodec`. Три forks на workload
использовали два CPU equivalents, heap 512 MiB, shared cgroup 768 MiB/1 GiB,
zero swap и неизменные cache/row/disk limits. Независимые полные проверки
canonical/receipt/CSV/slot совпали; variable UTC/occurrence IDs исключены
из cross-fork hashes.

| Метрика, медиана | Before | After |
|---|---:|---:|
| 100k unique DOCX, local seconds | 70.988 | 141.006 |
| DOCX whole-JVM allocated bytes | 15,953,998,640 | 12,418,735,800 |
| DOCX sampled heap / RSS MiB | 92.60 / 380.70 | 91.58 / 378.62 |
| DOCX workspace / canonical WAL peak MiB | 144.18 / 190.53 | 144.31 / 190.54 |
| 100k processed import -> 20 hosts, local seconds | 18.280 | 32.425 |
| Import whole-JVM allocated bytes | 3,355,615,472 | 3,331,114,976 |
| Import RSS / workspace peak MiB | 345.08 / 133.97 | 344.58 / 154.77 |

Поздний контроль на неизменённом старом binary занял **154.704 s**, с allocations
в пределах 0.22% прежней медианы; замедлились даже неизменённые read/refang/extract
stages. Изменение времени не было изолировано на decoder. Сохраняется
**22.16% allocation reduction**, без заявления стабильного end-to-end ускорения.
Отдельный JFR field-decoding sampled weight снизился с 5,145,007,024 до
1,127,139,752 B; post-GC live heap остался 42.37/42.22 MiB. Все семь основных DOCX
forks сохранили FAIL по прежним screens 45-s local/5-s hold/10-s wait. Отдельные
import screens прошли, но after median превысила исторический 30-s series target.
Эти неудачные screens не превращались в acceptance.

## Наблюдения полного Native-сервиса

Поздний reference envelope использовал shared four-CPU application/collector slice,
memory high 5 GiB и hard limit 6 GiB, no swap, JVM Xms256m/Xmx2g, balanced SQLite
и явно увеличенный workspace 8 GiB / aggregate private-disk allowance 32 GiB.
WSL получил примерно 16 GiB. SMB server был настоящим, но loopback, а не
provisioned 1-Gbit LAN/reference NVMe machine. Это результаты одного fork;
resource profiles и corpus/cadence отличаются от bounded table выше.

| Вход и область завершения | Seconds | Input/s | Whole-JVM allocation GiB | Bulk heap / RSS MiB |
|---|---:|---:|---:|---:|
| 100k distinct IOC, один DOCX, durable SMB completion | 65.56 | 1,525 | 11.72 | 243.92 / 691.64 |
| 1m distinct IOC, один DOCX, durable SMB completion | 705.29 | 1,418 | 116.87 | 207.96 / 868.16 |
| 1m IOC, 100 DOCX, workers/window 2/4, durable SMB completion | 640.05 | 1,562 | 385.31 | 266.07 / 820.97 |
| 1m IOC, 100 DOCX, corrected terminal CAS, workers/window 1/1, durable SMB completion | 909.48 | 1,100 | 389.93 | 297.97 / 774.31 |
| 100k detailed URL import rows -> 20 hosts, local export | 14.76 | 6,775 | 3.21 | 178.69 / 586.21 |
| 1m detailed URL import rows -> 20 hosts, local export | 117.80 | 8,489 | 31.68 | 193.43 / 630.53 |

Strict 1/1 batch сначала упал на terminalization CAS (ING-17). SHA-256
исправленного binary — `85f1cbd135eb2700c842b6513710b02c5469dd48be0d29713801651c04f241e3`;
от frozen baseline менялся только production code `DocumentAdmissionService`.
Успешный повтор не стирает неудачу и не устанавливает причинное отношение времени
2/4 к 1/1. Он проверил 100 bulk completions, 102 receipts с warmups,
2,167,020 artifact rows, keys/provenance, CSVs, sparse slots, hashes и три новых
успешных SMB slices. Terminal CAS conflicts и cleanup errors не было. Полный
receipt replay занял 10.58 s; maximum promotion ownership — 1.873 s, overall
writer ownership — 5.294 s в slots, CONTROL wait — 4.899 s. После 96 дополнительных
maintenance calls (0.117 s) dataframe size — 3,719.99 MiB, named WAL — 64 MiB.
CPU — 866.26 CPU-s, GC — 3.556 s, shared peak — 5,120.20 MiB; max/OOM events
и swap отсутствовали. Почти пустой cleanup не доказывает устойчивую reclamation rate.

Giant DOCX сохранил обратный компромисс: writer hold 169.205 s и control wait
168.469 s при completion 11 минут 45 секунд. Receipt replay прочитал 2,166,987
rows за 7.98 s; после cleanup DB/WAL — 3,754/64 MiB. Разделение входа меняет lock
occupancy, per-document work и publication cadence; его нельзя представлять
как изолированное ускорение backend.

Отклонённый versioned candidate завершил giant service interval за 1,934.65 s,
затем провалил 900-s full-receipt diagnostic; дополнительный cleanup занял
740.85 s, DB size — 8,024.54 MiB. Более короткий writer hold не компенсировал
copying/versioning/read/cleanup cost. Это объясняет выбор Native, но не является
вторым поддерживаемым backend или основанием принять candidate UTC semantics.

## Текущие проверки и оставшиеся ограничения

`NativeDeliveryLifecycleIT` использует production YAML/preflight, Tika/RE2/J,
classification, Camel, mapping, Native DBs, receipts, CSV/export и настоящие
ledgers; подменены только UTC и SMB wire boundary. `RouterSelectedImportDeliveryIT`
связывает processed-route configuration и физический CSV с COALESCE/canonical
receipt и receipt-only recovery. JDBC/TCK suites покрывают transactional rollback,
expiry equality, ID non-reuse, source/registration authority, field positions,
retention snapshots и connection ownership. Gates проверяют correctness, не load SLO.

Отдельный штатно упакованный live Native smoke 2026-10-13 прошёл настоящий
SMB-цикл (1,000 IOC в десяти DOCX плюс fixed additional/recovery inputs), с
14.91 s до durable completion и 18.02 s вместе с independent readback.
Его точная идентичность и проверки указаны в
[ingestion](../ingestion.md#verification-boundaries). Этот smoke относится
к своему binary; offline checks не создают нового live transport evidence
для более позднего source.

У текущего harness `make lifecycle-smoke` / `make lifecycle-load` открыт
terminal-filename mismatch (`TOOL-10` в [known issues](../../KNOWN-ISSUES.md)):
он ждёт исходный суффикс `.html`, тогда как архивированный sealed source может
оканчиваться на `.html.sealed`. Timeout на этом ожидании не проверяет последующие
expiry, retention и non-reuse assertions. Сохраняйте неудачный результат; команды
нуждаются в исправлении и переквалификации, прежде чем их результаты смогут
подтверждать текущую ёмкость. Старый успешный Native smoke остаётся историческим
свидетельством.

Известные затраты: один SQLite writer, nonpreemptive large atomic promotions,
полные projection/export, per-occurrence mapping, allocations и file-cache
pressure, удерживаемый reader WAL и зависящий от workload retention. Политика
30 дней требует расчёта storage по arrival rate, не одному batch. Старые defaults
heap 512 MiB/cgroup 1 GiB/workspace 2 GiB не квалифицированы для любой формы
million-input. [Bulk sizing](../../guides/ru/bulk-resource-sizing.md) описывает
операторские настройки; их изменение не ослабляет durability или safety checks.

Обновляйте исторические измерения только с named binary/configuration/corpus,
точными границами времени и ресурсов, независимыми output/recovery checks и
сохранёнными failure dispositions. Определите production arrival rate,
populated-base size и требования к control delay до заявления sustainable
capacity или p95/p99 SLO.

## Источники истины

- [Workspace limits](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/DocumentPreparationLimits.java),
  [factory](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcDocumentPreparationWorkspaceFactory.java)
  и [workspace contracts](../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcDocumentPreparationWorkspaceIT.java).
- [Dispatcher](../../../adapters/adapter-ingest/src/main/java/com/iocextractor/adapter/in/ingest/DurableDocumentDispatcher.java),
  [writer admission](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcWriterAdmission.java),
  [snapshot reader](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcSnapshotSliceReader.java)
  и [capacity health](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/DataProcessingCapacityHealthIndicator.java).
- [Classpath defaults](../../../bootstrap/ioc-app/src/main/resources/application.yml),
  [production template](../../../packaging/templates/application.yml) и
  [workspace configuration model](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocProcessingProperties.java).

## Когда обновлять

Обновляйте обе версии при изменении admission, retention, writer/reader ownership,
telemetry или поддерживаемых команд проверки. Исторические таблицы, binary
identities и failed-screen dispositions остаются привязаны к исходным workloads;
для нового профиля добавляется отдельно идентифицированное свидетельство.

Связанные документы: [storage](../storage.md), [processing](../processing.md),
[ingestion](../ingestion.md), [testing policy](../../TESTING.md),
[known issues](../../KNOWN-ISSUES.md), [ADR 0042](../../ADR/0042-native-sqlite-production-storage.md).
