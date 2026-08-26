# Технический долг

Единый реестр технического долга и заложенных seam'ов проекта — одно место
правды. Раньше эти пункты были размазаны по `Открытые вопросы` дев-нот
([dev/](dev/)), `roadmap.md` и ревью; здесь они сведены и приоритизированы.

**Как вести:** у каждого пункта стабильный ID. Основные разделы содержат только
актуальный backlog; при закрытии строка со статусом `закрыт` и ссылкой на
коммит/этап переносится в архив в конце файла, но не удаляется. Новый долг →
строка в нужную секцию.

**Статус:** `открыт` · `частично` · `seam` (сознательно отложенный задел,
интерфейс/инвариант уже заложен) · `закрыт` · `descoped` (осознанно won't-do).
**Эффорт:** `S` (≤ полдня) · `M` (день-два) · `L` (итерация).

---

## 1. Демон / ингест (`ING`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| ING-2 | **Tail-режим для источников** (растущие append-фиды: offset/rotation/checkpoint). **Descoped:** вне домена document-ingest — источники дискретны (Word/HTML, скрейпинг даёт целые документы). При появлении стриминг-источника — новый режим/`SourceReader` тогда. | descoped | L | dev/0001 #1, dev/0006 |
| ING-7 | **Инкрементальная запись локальных датафреймов** — `CsvArtifactProjection` всё ещё полностью перечитывает артефакт из БД и переписывает мутабельный `*_generated.csv` (atomic temp→move) на каждый write. Immutable export из 0012 уже cadence-driven и не зависит от этой проекции, но локальный always-fresh путь остаётся O(N). Остаётся: дельта/кэш либо отказ от per-write projection. | seam | M | review, dev/0012 |
| ING-8 | **Web driving-adapter** — HTTP как третья точка входа рядом с CLI/file-poll: ops (ING-3) → REST-ингест/запросы → TAXII/STIX-сервер (синергия с EXP-1) + BFF под фронтенд. Эндпоинты живут в отдельном `adapter-web`. **Требование:** REST-эндпоинты, дёргающие use-cases, обязаны открывать `MdcScope` (run-id), как CLI/демон, иначе прогоны теряют корреляцию в логах. **Связка с актуатором:** при выносе web за loopback `management.endpoint.health.show-details` нужно закрыть auth / перевести в `when-authorized` (сейчас `always` безопасен только из-за loopback-бинда). | seam | L | review |
| ING-11 | **Retry × run-ledger: нет resume-протокола.** Retry в `FileSourceMessageHandler` оборачивает весь `useCase.ingest()`; `processClaimed` безусловно `startIngest` на каждой попытке; `markFailed` — только при `!dbCommitted` → сбой проекции (диск/права) даёт N `DB_COMMITTED`-ранов одного source; `IngestRunRecoveryService` не синхронизирован с file-ledger (`CLAIMED`) → после рестарта лишняя полная экстракция. Данные целы, платим CPU/diagnostics/проекции + id-gaps; самовосстанавливается. Нужен resume-протокол: после `DB_COMMITTED` повторять только projection/archive (не extraction/commit) + синхронизация run-ledger ↔ file-ledger; требует мини-дизайна (меняется сага ING-4a). **План: 0.3.0.** | открыт | M | стенд-тест 2026-07-14 (pre-retarget RC 0.1.1) |
| ING-13 | **Failed claim превращает файл в вечный poison.** В candidate-линии 0.2.0 реализована полумера: rejection возвращает `REJECTED/ALREADY_REJECTED`, повторный poll durable `FAILED` завершается без повторного generic/typed лога. Полный дефект остаётся: `markFailed` синтезирует `Path.of("unknown")`, файл остаётся в inbox и не получает явной физической судьбы. Нужен pre-claim dead-letter/quarantine (с реальными path/detectedAt и идемпотентным terminal-фильтром). Поддерживаемой clear/requeue-команды пока нет: source/logs сохраняются для разбора, а recovery выполняется только по reviewed procedure без ручного удаления ledger/SQLite state. **Полный фикс — 0.3.0.** | открыт | M | стенд-тест 2026-07-14 (pre-retarget RC 0.1.1), полумера 2026-07-15 |

## 2. Обогащение вывода (`OUT`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| OUT-1 | **Meta-колонки всегда `NULL`** (`score`, `time_last_seen`, `time_first_seen`, `threat_type`, `description`) — провайдеры обогащения не реализованы. Самый заметный пробел по ценности данных. | открыт | M | dev/0002 |
| OUT-2 | **CSV formula injection — латентный configuration-dependent риск.** `source.label` формируется из нормализованного marker-match недоверенного документа и попадает в CSV; `QuoteMode.ALL_NON_NULL` защищает структуру CSV, но не гарантирует безопасность при открытии в spreadsheet. Для shipped default profile disposition — `not_applicable`: matches начинаются с `БИБ`/`Письмо`, остальные output values ограничены indicator/value/const contract. Универсальной neutralization в коде нет. **Триггер:** marker-regex или новая free-text колонка, допускающие spreadsheet-dangerous prefix после нормализации. Будущий фикс должен выбрать validation/rejection либо отдельную spreadsheet-oriented encoding policy и не менять молча семантику machine-consumed reputation lists. | seam | S | security review; `SEC-OUT-1`; [THREAT-MODEL](THREAT-MODEL.md) |

## 3. Экстракция / корпус (`EXT`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| EXT-1 | **Новые типы индикаторов: IPv6, email** — паттернов и типов нет. | открыт | M | dev/0004 |
| EXT-2 | **`.onion` v3** — точные base32-границы (56 символов) при ужесточении паттерна. | частично | S | dev/0004 |
| EXT-3 | **Полноценный тест-корпус** + уточнение «не-PSL провайдеров» (ожидания на вариант 2 классификации). | открыт | M | dev/0004 |

## 4. Наблюдаемость (`OBS`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| OBS-4 | **Durable diagnostic occurrences/report/quarantine.** Текущий `DiagnosticSink` best-effort и synchronous. При реальной потребности в audit/reprocess ввести `DiagnosticOccurrence` (run/source/stage/sequence/stable id), durable `ReportDiagnosticSink`/`QuarantineStore` и явно решить, является ли write частью processing outcome. Не извлекать durable identity из MDC; не превращать diagnostics в control events/broker без триггера. | seam | L | ADR/0017 реш. 7–8 |
| OBS-5 | **Generated Elasticsearch component template и lifecycle mapping-миграции.** При первом supported Elasticsearch/data-stream consumer генерировать project mappings из `LogField` metadata (`STRING→keyword`, `LONG→long`, `BOOLEAN→boolean`), устанавливать template до первого index и публиковать rollover/reindex runbook для несовместимых legacy mappings. Producer fix OBS-D3 не может изменить уже созданный `keyword` field. До реального ES consumer installer/client не вводить. | seam | M | ADR/0018 реш. 9 |

## 5. Код-смелл (`CODE`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| CODE-1 | **Длинный composition contract extraction.** Шесть convenience-конструкторов удалены: `IocExtractionService` имеет production и explicit test constructor, `ExtractionResult` больше не маскирует completion defaults. Остался длинный dependency list в `IocExtractionServiceFactory`/production constructor; сворачивать только в осмысленный immutable dependency bundle, не в parameter-object ради счётчика. | частично | S | `2c18bbd`, review ADR-0017 |
| CODE-5 | **Единый nullness-контракт и build gate.** Сейчас Eclipse JDT в `automatic`-режиме выбирает разные транзитивные аннотации по classpath модуля (Spring, JSR-305, JSpecify), тогда как Maven/CI nullness не проверяет. Если проект принимает строгую null-safety, сделать это отдельным срезом: выбрать JSpecify как прямой контракт, вводить `@NullMarked` постепенно с явными `@Nullable` на границах и подключить один воспроизводимый анализатор к Maven/CI. До этого `tools/eclipse-jdt.prefs` подавляет только шумный `nullUncheckedConversion`, не скрывая диагностики реального и потенциального null-доступа. Не blocker 0.2.0. | seam | M | IDE warnings review 2026-07-20 |

## 6. Архитектура / модульность (`ARCH`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| ARCH-1 | **Гранулярность platform-модулей** — `platform-errors` (13 строк), `platform-diagnostics-logging` (крошечный): кандидаты на слияние. | открыт | M | dev/0009 |
| ARCH-2 | **Размещение regex SPI** — `PatternEngine` в `ioc-domain` (extract) vs отдельный `platform-regex-api`. | seam | M | dev/0009 |
| ARCH-3 | **Spring Modulith / canvas** — после стабилизации reactor-структуры. | seam | M | dev/0009 |

## 7. Экспорт / упаковка / ops (`EXP` / `OPS`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| EXP-1 | **STIX/OpenIOC export sink** — модель уже несёт `stixType()` под это, ждёт sink-адаптер. | открыт | L | roadmap |
| EXP-2 | **`.deb`-пакет** с maintainer-скриптами (сейчас shell-установщик). | открыт | M | packaging |
| OPS-1 | **Мульти-источник — верификация:** несколько файлов разом + новый файл на работающем демоне (watch-service + reconcile). | открыт | S | review |
| OPS-2 | **Логротация средствами ОС** (сейчас только rolling-appender Logback). | открыт | S | packaging |
| EXP-3 | **Активные delivery-коннекторы** — пуш правил фильтрации в security-appliances (firewall EDL/blocklist, proxy ACL/ICAP, DNS RPZ-зоны, DHCP и т.п.) поверх export-контракта 0012. Каждый appliance = свой driven-адаптер за единым delivery-портом (правило «один адаптер = одна интеграция»), родственно `publish`-половине 0011 (та доставляет файлы/срезы, эти — активный push в API/протокол). Усиливает выбор `complete`-дефолта (appliances тянут полный список/зону). Контракт fan-out (consumer-owned `DeliveryLedger` retention-view + AND-композит guard'ов + шаблон канала) зафиксирован: [ADR/0014](ADR/0014-event-driven-ingest-to-delivery.md) «Детальный дизайн» → Р3. | seam | L | dev/0011, обсуждение 0012, ADR/0014 Р3 |
| EXP-4 | **DuckDB как read/analytics/Parquet-движок** (на радаре, не сейчас) — embedded OLAP за `SqlDialect`/read-портом; `ATTACH` SQLite-truth напрямую (`sqlite_scanner`), нативный `COPY TO` CSV/Parquet на шаге «материализовать срез» export-саги (§3a/§4a 0012). Truth остаётся SQLite (OLTP, write-path не трогаем), DuckDB только на read/export-стороне → неразрушающая инкрементальная миграция. **Активировать при появлении columnar-потребителя** (data-lake / TI-platform / отчётность); текущему firewall/proxy-потребителю не нужен. | seam | M | обсуждение 0012 (tooling) |
| EXP-5 | **Append/update export modes** — модель и manifest vocabulary допускают расширение, но v1 намеренно исполняет только `complete`; `append` получает `EXPORT.UNSUPPORTED_MODE` до IO. Активировать только вместе с отдельным durable watermark и consumer contract. | seam | L | dev/0012 F4 |
| OPS-3 | **Capability preflight/health для SMB boundary.** Generic sync всё ещё требует reachability+auth/read для fetch и write→rename→delete для publish. Для managed import ADR-0025 уже фиксирует более строгий blocking per-source gate: заранее созданный `.ioc-managed-import/{processing,terminal,quarantine,probe}`, positive create/read/no-replace-rename/exact-delete probe до intake и source-scoped `DEGRADED|DOWN`. Приложение не создаёт share/dirs, не меняет ACL и не обещает их полный аудит; producer-denial проверяется operator-run negative test отдельной identity. До H2 это designed, но не enforced. | seam | M | обсуждение 0011; ADR/0025; DATA-IMPORT-01 H2 |
| OPS-4 | **State-driven sync / event coordination hardening.** Базовая часть закрыта ADR 0013: `RemoteSourceMonitor` отделён от `RemoteFetchService`, publish fast-path идёт через `SliceCompleted`, periodic reconcile остаётся correctness backstop, fetch/publish work сериализуется in-memory keyed executor по endpoint, actuator показывает queue/degradation state. SMB `CHANGE_NOTIFY` реализован как optional latency accelerator, не correctness path. Открытые seam'ы: durable outbox/broker adapter при межпроцессной доставке, полноценная high/low-water hysteresis policy вместо текущего bounded admission + shed-to-reconcile. | seam | M | dev/0013, docs/sync.md |
| OPS-5 | **Sync fetch read-model cleanup и targeted discovery.** `remote_fetch_ledger` хранит historical identities `(path,size,mtime)` и не чистится по SMB delete-events; нужен age-retention/reaper с операторскими лимитами. Если `fetchDetection.detectDurationMs`/каталоги с тысячами файлов покажут, что full listing доминирует, добавить targeted stat path поверх notify payload или transport capability, не меняя polling/reconcile correctness. | seam | M | docs/sync.md, CHANGE_NOTIFY review |
| OPS-6 | **Intra-endpoint parallel fetch (bounded).** Кросс-endpoint параллельность уже есть на всех стадиях (detection-пул по источникам, keyed executor с воркерами = числу endpoint'ов, publish submit-all-then-await): N хостов обрабатываются за `max`, не `sum`. **Внутри одного endpoint'а** работа намеренно последовательная двумя слоями — single-flight ключ executor'а + endpoint-lock вокруг одного cached SMB-клиента в `SmbFileTransport` (`withClient` держит лок на всю операцию) — поэтому батч из K файлов с одной шары качается последовательно. Seam: ограниченная параллельность скачиваний внутри endpoint'а = пул SMB-клиентов/сессий на endpoint (lifecycle, idle-reaper) + under-key concurrency в keyed executor (admission/health-модель); ledger/in-flight идемпотентность уже parallel-safe и переделки не требует. **Триггер активации — только по метрикам:** health `fetchDetection.detectDurationMs` + длительности fetch в ECS-логах показывают, что узкое место — последовательный прогон крупных батчей с *одной* шары (а не сеть/сервер), и время батча нарушает SLA. До сигнала — YAGNI; bounded pressure на один файловый сервер — осознанный инвариант текущего дизайна. | seam | L | CHANGE_NOTIFY review (обсуждение multi-endpoint параллельности) |
| OPS-8 | **Managed-import SMB server-family breadth.** Live ownership/materialization/disposition/notification contract квалифицирован на Samba только для P8 baseline. После ADR-0025 каждый заявленный family обязан теми же distinct producer/service identities пройти capability gate, producer-denial, server-side no-replace rename/share modes, stable file ID, disconnect recovery, exact terminal purge и `CHANGE_NOTIFY`. Отсутствие Windows Server/NAS стенда — qualification skip, а не pass; polling заменяет только потерянные notifications, но не ownership или retention checks. Пункт перенумерован с ошибочно повторно использованного active `OPS-7` 2026-08-26; исторический закрытый `OPS-7` остаётся ingest→export fast-path. | qualification | M | DATA-IMPORT-01 P8/P9; ADR/0025 H5 |
| OPS-9 | **Managed-import SMB remote terminal retention.** Candidate перемещает remote occurrence в `terminal`/`quarantine`, но существующий reaper чистит только local terminal/workspace/snapshot, dataframe receipt и ledger row; remote copy остаётся бессрочно. ADR-0025 требует exact regular-file purge до local cleanup, idempotent absence, fail-closed contradiction, replay без remote disposition и CAS-delete ledger row последним. До H1/H3 это открытый capacity/confidentiality и recovery gap; recursive/path-driven delete запрещён. | открыт | M | ADR/0025; DATA-IMPORT-01 H1/H3 |

## 8. Source parsing / dependency surface (`SRC`)

| ID | Долг / seam | Статус | Эфф. | Источник |
|---|---|---|---|---|
| SRC-1 | **Минимизировать Tika parser set для опубликованного supported-format baseline.** [ARCHITECTURE.md](ARCHITECTURE.md#артефакты-и-заполнение) и contract tests закрепляют HTML/PDF/DOCX/XLSX; `tika-parsers-standard-package` всё ещё приносит parser modules и transitives за пределами этого контракта. Сравнить umbrella package с явным набором HTML/Microsoft/PDF modules по размеру boot jar, dependency/CVE surface и качеству extraction. Сужать graph только если выигрыш оправдывает потерю best-effort форматов. Это optimization/packaging seam, не дефект корректности и не blocker 0.2.0. | seam | M | Tika 3.3.1 migration review; [dev/processing](dev/processing.md) |
| SRC-2 | **Bounded resource policy для document parsing.** `SourceReader` обрабатывает потенциально недоверенные документы, а `BodyContentHandler(-1)` намеренно не ограничивает размер извлечённого текста. Нужен единый operator-facing contract: max input bytes, decompression ratio/depth, spool threshold и wall-clock budget/cancellation; настройки Tika `AutoDetectParserConfig` должны дополнять, а не подменять внешние file/time limits. Exhaustion обязан завершаться типизированной диагностикой и согласованным failure-policy outcome без частичного durable write. Это security/operational hardening seam; текущая миграция на Tika 3.3.1 его не создаёт и не делает release blocker. | seam | M | Tika 3.3.1 migration review; ADR/0017; [dev/processing](dev/processing.md) |

## 9. Developer tooling / build (`TOOL`)

| ID | Долг / seam | Статус | Эфф. | Источник |
|---|---|---|---|---|
| TOOL-1 | **Test taxonomy и affected-test accelerator.** Сначала классифицировать существующие тесты и закрепить минимальный JUnit 5 contract: unit по умолчанию, явные `integration`/`e2e` и только при необходимости ортогональный `slow`. После этого добавить быстрые category targets и безопасный `test-affected`, который учитывает staged/unstaged/untracked paths, upstream и downstream reactor closure, а при root/shared/unknown change откатывается к полному test gate. Это только inner-loop accelerator; `verify` остаётся merge/release gate. **Триггер:** отдельный test-taxonomy review или измеримый рост стоимости полного локального цикла. | seam | M | tools facade review 2026-07-22 |
| TOOL-2 | **Детерминированный Java formatting contract.** Выбрать formatter по пробному repo-wide diff, добавить mutating `fmt` и read-only `fmt-check` через единый parent Maven plugin; после чистого baseline включить check в `verify`. Не смешивать с SAST: SpotBugs/FindSecBugs уже отслеживается как `SEC-VER-1` и требует отдельного non-blocking baseline/noise triage. **Триггер:** отдельный post-0.2.0 hygiene slice; массовый mechanical diff не включать в release stabilization. | seam | M | tools facade review 2026-07-22; [SECURITY-ENGINEERING](SECURITY-ENGINEERING.md) |
| TOOL-3 | **Воспроизводимый manual load-smoke.** Замкнуть deterministic fixture → isolated oneshot → стабильный отчёт времени, input/unique/committed rows и diagnostics; daemon mode и thresholds добавлять только после baseline measurements. Не включать в обычный CI/pre-push и не смешивать с malformed/fuzz/parser-bomb корпусом (`SEC-VER-3`, `SRC-2`). **Триггер:** необходимость сравнивать производительность релизов или расследовать эксплуатационную деградацию. | seam | M | tools facade review 2026-07-22; `GenerateIocFixture` |

---

## Рекомендованный порядок

1. **0.3.0 — ingest lifecycle hardening:** `ING-11` (с мини-дизайном
   resume-протокола), полный `ING-13`; заодно `OPS-1` (мульти-источник) —
   верификация на том же стенде.
2. **Ценность данных:** `OUT-1` (обогащение meta-колонок).
3. **Фича по выбору:** `EXT-1` (IPv6/email, почти весь config-driven) или `EXP-1`
   (STIX-экспорт — модель готова).
4. **Source parsing после релиза:** `SRC-2` активировать при расширении контура
   недоверенных входов или по resource-инциденту; `SRC-1` — только после
   измеримого выигрыша dependency surface.

## Архив закрытых долгов

Архив не участвует в первичном аудите backlog, но сохраняет стабильные ID,
содержание решения и implementation evidence.

### Демон / ингест (`ING`)

| ID | Закрытый долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| ING-1 | **Retention reaper** — единый декларативный reaper (`ioc.maintenance.retention`) чистит `done` + `failed` по возрасту/количеству (delete/archive); пул-политика `RetentionPolicy`, порт `RetentionStore`, `DaemonMaintenanceScheduler`. Partition-specific retention удалён вместе с partition-staging. | закрыт | M | dev/0001 #6, storage collapse |
| ING-3 | **Health-транспорт демона** — actuator/health по HTTP, web включается только в daemon (`DaemonWebEnvironmentPostProcessor` по `ioc.runtime.mode`), loopback-bind, expose `health,info`. Первый камень под web driving-adapter (ING-8). | закрыт | M | dev/0001 |
| ING-4 | **Durability ledger + сторадж** — реализован служебный JDBC storage (`ioc.ingestion.ledger.type: file \| jdbc`, service SQLite datasource, `user_version`-миграции, JDBC `IngestionLedger`, legacy ledger import, DB health) **и business dataframe truth**: `ioc.storage.dataframe.type: jdbc` (default), per-artifact identity (`identity_hash`/`epoch`), JDBC canonical repositories с `<artifact>_sources`, schema-aware id baseline, CSV (`*_generated.csv`) как проекция из БД для oneshot и daemon. Partition-staging удалён; daemon пишет сразу в canonical store. | закрыт | M | [dev/ingestion](dev/ingestion.md), storage collapse |
| ING-4a | **Durable run-ledger + saga (crash-window).** Service schema содержит `ingest_run`; daemon пишет checkpoints `STARTED → DB_COMMITTED → PROJECTION_COMPLETED → COMPLETED` для per-file write→project. Если процесс падает после commit БД, startup recovery повторяет CSV-проекцию из БД и закрывает run. Сбой до DB commit помечается `FAILED`, потому что автоматический replay без повторного расчёта unsafe. | закрыт | M | [dev/ingestion](dev/ingestion.md), storage collapse |
| ING-5 | **Триггер прежнего merge-pass** — удалён вместе с partition-staging; daemon больше не ждёт отдельный scheduled pass после ingest. | закрыт | M | dev/0001, storage collapse |
| ING-6 | **Partition-wrapper boundary** — исторический guardrail снят после удаления промежуточного staging; source-key теперь доходит в JDBC sink как adapter/application concern. | закрыт | S | dev/0001, storage collapse |
| ING-9 | **Коллизия имён при архивации вложенных targets** — `FileSystemRetentionStore.archive` раньше сплющивал вложенное дерево до имени файла. Фикс: `RetentionEntry` несёт корень цели (`baseDir`), архив зеркалит относительный подпуть под archive-dir; после удаления partition-target это остаётся защитой для будущих вложенных targets. | закрыт | S | review (ревью ING-1) |
| ING-10 | **Startup recovery отделён от ingestion intake.** Flow имеет `autoStartup=false`; coordinator с `ApplicationRunner.HIGHEST_PRECEDENCE` последовательно восстанавливает run/source ledgers и только затем запускает poller. Ingest/recovery/reject сериализуются по `SourceKey` через общий guard, recovery перечитывает durable state после admission, а file/JDBC ledgers используют монотонные expected-state transitions. Guard сохраняет primary work failure при secondary release failure. Health читает single-writer lifecycle snapshot и показывает aggregate contention без source identities; `concurrency` fail-closed закреплён на `1`. Latch-tests, concurrent adapter TCK, restart/watched-inbox E2E и release-invariant regressions закрывают исходную гонку, `SB04-116` и `I4-SB-01`. Гарантия process-local; multi-daemon требует lease/fencing. | закрыт | M | checkpoints `f4f011e`, `c3a03e2`, `a44d10f`, `7ce5f8f`; [ING-10 worknote](worknote/0.3.0/ing-10-ingestion-lifecycle-hardening.md), 2026-08-02 |
| ING-12 | **Hashing включён в bounded/diagnostic boundary.** `FileSourceMessageHandler` повторяет content SHA-256 в пределах общего retry/backoff; после exhaustion использует fingerprint `path+size+mtime` только как terminal failure identity, записывает durable rejection и один раз эмитит `INGEST.SOURCE_UNREADABLE`. Generic stacktrace на каждом poll устранён; физическая судьба pre-claim файла остаётся ING-13. | закрыт | S | стенд-тест 2026-07-14 (RC 0.1.1), реализация 2026-07-15 |
| ING-14 | **Потеря WatchService-файла на quiet-period устранена.** `IngestFileListFilter` реализует discard-aware контракт Spring Integration: matching-файл, временно отклонённый stability-проверкой, возвращается в retry-set, а постоянные exclude/reject туда не попадают. Реальный `FileReadingMessageSource` закреплён regression-тестом. Shipped default и production correctness-path переключены на полный polling scan (`use-watch-service=false`); WatchService остаётся явно opt-in только для надёжной локальной filesystem и не заявлен как полноценный rescan/backstop. | закрыт | M | стенд-тест 2026-07-23 (`58ab1e7`), pre-release remediation 0.2.0 |

### Наблюдаемость (`OBS`)

| ID | Закрытый долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| OBS-D1 | **Диагностика как first-class outcome.** Закрыто по ADR-0017: impact и generated catalog; exactly-once runner delivery; configurable fail-fast/collect и bounded summary; completion outcome; side-effect-free row preparation до policy checkpoint; pure domain decisions + gated TRACE; SOURCE/EXTRACTION/CLASSIFY/SINK/PIPELINE/INGEST/SYNC producers. Ratchet allowlist пуст. | закрыт | M | `354c5c6..32b4202`, `d34b733`, ADR/0017 |
| OBS-D3 | **Typed ECS fields** — ambient correlation остаётся в MDC, event-local fields идут через typed SLF4J key/value; `LogField` задаёт JSON type schema. Первичная реализация использовала Boot 3.4 `StructuredLogEncoder`; последующие Boot upgrades сохранили контракт. | закрыт | M | ADR/0018; `a8867f9`, `e4e0ea5` |
| OBS-1 | **Таблица `Severity → log.level`** — закрыто: `observability.md` публикует FATAL/ERROR→error, WARN→warn, INFO→info, DEBUG→debug, TRACE→trace; `LoggingDiagnosticSinkTest` пинит все шесть значений. | закрыт | S | `5ba28b1` |
| OBS-2 | **`SINK.CHARSET_UNMAPPABLE` — полноценная диагностика непредставимых символов на выходе.** Закрыто: `ArtifactProjectionResult` входит в completion contract oneshot/daemon/recovery; mutable `CsvArtifactProjection` возвращает одну OPERATION/WARN occurrence с точным счётом logical data values/rows/header values и без raw data. `CountingCharsetWriter` удалён. Immutable slice writers сохраняют `CodingErrorAction.REPORT`. | закрыт | M | `79e85e8`, `3dda716`, ADR/0017 |
| OBS-3 | **Таксономия логов и generated catalog.** Закрыто: `EventAction`/`LogField` несут metadata, `LOGGING-CATALOG.md` генерируется и doc-sync тестируется; stale actions удалены, CSV projection эмитит `artifact_project`, а `CatalogReferenceRatchetTest` не даёт добавить неиспользуемую action без осознанного решения. | закрыт | S | `752d186`, `8277ab7`, `2681c4d` |

### Надёжность конфига (`CFG`)

| ID | Закрытый долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| CFG-1 | **Тихий `catch (NumberFormatException ignored)`** на `id.start` — закрыто: `id.start` стал sealed value contract (`auto` \| explicit long) с binding converters, parser и runtime без silent fallback. | закрыт | S | ADR/0016, `3d45cdd`, `e01e2fa` |
| CFG-2 | **Нет кросс-проверки имён артефактов** `artifact-identity.artifacts` ↔ `sink.artifacts` — закрыто: startup preflight проверяет identity↔sink, key-columns⊆columns, дубликаты, id policy и registry-backed keys до обработки файла/записи. | закрыт | S | ADR/0016, `b0e5157`, `9bb9e63`, `8575527` |
| CFG-3 | **«stage 11» протекло в рантайм-ошибку** — закрыто удалением старого aggregation/storage кода при β-collapse; новая config-error convention запрещает внутреннюю нумерацию/implementation jargon в operator-facing сообщениях. | закрыт | S | storage collapse, ADR/0016 |
| CFG-4 | **Strict configuration binding после миграций.** Закрыто: tombstone-поля `Lookup`/`smb.readTimeout` удалены из `IocProperties`; unknown `ioc.*` keys отбиваются reflection-shape preflight, legacy migration hints живут в `IocConfigurationFailureAnalyzer` с `CONFIG.*`. Переоткрывался 2026-07-10: env-канал молча пропускал `IOC_*` опечатки. Финально закрыт schema-aware env matcher'ом и value-free reporter'ом выигравших overrides. | закрыт | S | ADR/0016, `ec14c8d`, `e01e2fa`, `5851ec1`, `2c224c2` |

### Код (`CODE`) и delivery (`EXP` / `OPS`)

| ID | Закрытый долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| CODE-2 | **Дублирование «bare IP» в legacy CSV lookup** — закрыто удалением `adapter-lookup-csv`; runtime использует доменный `NetworkAddressClassifier`/provider predicates и canonical row-key дедуп. | закрыт | S | review |
| CODE-3 | **Повторная feature-extraction.** Закрыто materialized `ClassificationDecision`: dedup идёт до classification, `MatchPolicy` вызывается один раз только для NETWORK, providers/filter/TRACE читают один outcome. Counting и large-batch tests пинят контракт. | закрыт | M | `4038173`, `5b44f11`, `ae7f268` |
| CODE-4 | **Хрупкий `DiagnosticCatalogTest`** — закрыто: хардкод размера удалён; registration discovery и generated catalog doc-sync проверяют полноту и свежесть контракта без привязки к числу кодов. | закрыт | S | `adc795b` |
| EXP-6 | **Publish-ledger retention guard** — закрыто в 0011: `PublishLedgerSliceRetentionGuard` pin-ит missing/`PENDING`/`IN_PROGRESS`/`FAILED`, а `SUCCEEDED`/`ABANDONED` разрешают slice delete; publish phase предшествует retention. | закрыт | M | dev/0011, dev/0012 F2 |
| OPS-7 | **Ingest→export event fast-path (ADR 0014 Р2).** Закрыто: `CanonicalArtifactsChanged` из `IngestionService` (после `markCompleted`, per-run, claim-check без значения revision) + `nudge()` на `DaemonExportScheduler`: чек через `quiet-period` на его же single-thread executor'е, coalesce повторных nudge, follow-up-чек пока есть pending-работа, startup-nudge после recovery. Cadence остаётся единственным носителем quiet/max-cap-политики; periodic poll — обязательный backstop (после Р2 его можно делать редким). Выигрыш материализуется при `trigger.type: quiet-period`; при `interval` nudge — no-op. Детальный дизайн: [ADR/0014](ADR/0014-event-driven-ingest-to-delivery.md) «Детальный дизайн» → Р2. | закрыт | M | ADR/0014 Р2; commits `d28c74e`, `e265b0d`, `294b67c`, `aefb593` |

### Дополнительный завершённый контекст

- Зависимость observability от IOC pipeline снята выносом generic contracts в
  `platform-etl`.
- `ioc.source.charset` и `ioc.sink.csv.charset` исполняются на I/O boundaries;
  unmappable projection values дают агрегированный WARN.
- Пустая атрибуция использует `source=""` и `SOURCE.MARKERS_UNMATCHED`, а не
  строковый sentinel `UNKNOWN`.

> Связанные документы: [ADR/](ADR/) (история решений и исходные
> `Открытые вопросы`), [dev/](dev/) (как устроены способности).
