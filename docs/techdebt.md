# Технический долг

Единый реестр технического долга и заложенных seam'ов проекта — одно место
правды. Раньше эти пункты были размазаны по `Открытые вопросы` дев-нот
([dev/](dev/)), `roadmap.md` и ревью; здесь они сведены и приоритизированы.

**Как вести:** у каждого пункта стабильный ID; при закрытии — статус `закрыт`
с ссылкой на коммит/этап, не удаляем. Новый долг → строка в нужную секцию.

**Статус:** `открыт` · `частично` · `seam` (сознательно отложенный задел,
интерфейс/инвариант уже заложен) · `закрыт` · `descoped` (осознанно won't-do).
**Эффорт:** `S` (≤ полдня) · `M` (день-два) · `L` (итерация).

---

## 1. Демон / ингест (`ING`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| ING-1 | **Retention reaper** — единый декларативный reaper (`ioc.maintenance.retention`) чистит `done` + `failed` по возрасту/количеству (delete/archive); пул-политика `RetentionPolicy`, порт `RetentionStore`, `DaemonMaintenanceScheduler`. Partition-specific retention удалён вместе с partition-staging. | закрыт | M | dev/0001 #6, storage collapse |
| ING-2 | **Tail-режим для источников** (растущие append-фиды: offset/rotation/checkpoint). **Descoped:** вне домена document-ingest — источники дискретны (Word/HTML, скрейпинг даёт целые документы). При появлении стриминг-источника — новый режим/`SourceReader` тогда. | descoped | L | dev/0001 #1, dev/0006 |
| ING-3 | **Health-транспорт демона** — actuator/health по HTTP, web включается только в daemon (`DaemonWebEnvironmentPostProcessor` по `ioc.runtime.mode`), loopback-bind, expose `health,info`. Первый камень под web driving-adapter (ING-8). | закрыт | M | dev/0001 |
| ING-4 | **Durability ledger + сторадж** — реализован служебный JDBC storage (`ioc.ingestion.ledger.type: file \| jdbc`, service SQLite datasource, `user_version`-миграции, JDBC `IngestionLedger`, legacy-import, DB health) **и business dataframe truth**: `ioc.storage.dataframe.type: jdbc` (default), per-artifact identity (`identity_hash`/`epoch`), JDBC canonical/lookup repositories с `<artifact>_sources`, CSV (`*_generated.csv`) как проекция из БД для oneshot и daemon. Partition-staging удалён; daemon пишет сразу в canonical store. | закрыт | M | dev/0001, review, worknote/storage-layer |
| ING-4a | **Durable run-ledger + saga (crash-window).** Service schema содержит `ingest_run`; daemon пишет checkpoints `STARTED → DB_COMMITTED → PROJECTION_COMPLETED → COMPLETED` для per-file write→project. Если процесс падает после commit БД, startup recovery повторяет CSV-проекцию из БД и закрывает run. Сбой до DB commit помечается `FAILED`, потому что автоматический replay без повторного расчёта unsafe. | закрыт | M | review, worknote/storage-layer §«Статус Шага 3» |
| ING-5 | **Триггер прежнего merge-pass** — удалён вместе с partition-staging; daemon больше не ждёт отдельный scheduled pass после ingest. | закрыт | M | dev/0001, storage collapse |
| ING-6 | **Partition-wrapper boundary** — исторический guardrail снят после удаления промежуточного staging; source-key теперь доходит в JDBC sink как adapter/application concern. | закрыт | S | dev/0001, storage collapse |
| ING-7 | **Инкрементальная запись локальных датафреймов** — `CsvArtifactProjection` всё ещё полностью перечитывает артефакт из БД и переписывает мутабельный `*_generated.csv` (atomic temp→move) на каждый write. Immutable export из 0012 уже cadence-driven и не зависит от этой проекции, но локальный always-fresh путь остаётся O(N). Остаётся: дельта/кэш либо отказ от per-write projection. | seam | M | review, dev/0012 |
| ING-8 | **Web driving-adapter** — HTTP как третья точка входа рядом с CLI/file-poll: ops (ING-3) → REST-ингест/запросы → TAXII/STIX-сервер (синергия с EXP-1) + BFF под фронтенд. Эндпоинты живут в отдельном `adapter-web`. **Требование:** REST-эндпоинты, дёргающие use-cases, обязаны открывать `MdcScope` (run-id), как CLI/демон, иначе прогоны теряют корреляцию в логах. **Связка с актуатором:** при выносе web за loopback `management.endpoint.health.show-details` нужно закрыть auth / перевести в `when-authorized` (сейчас `always` безопасен только из-за loopback-бинда). | seam | L | review |
| ING-9 | **Коллизия имён при архивации вложенных targets** — `FileSystemRetentionStore.archive` раньше сплющивал вложенное дерево до имени файла. Фикс: `RetentionEntry` несёт корень цели (`baseDir`), архив зеркалит относительный подпуть под archive-dir; после удаления partition-target это остаётся защитой для будущих вложенных targets. | закрыт | S | review (ревью ING-1) |

## 2. Обогащение вывода (`OUT`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| OUT-1 | **Meta-колонки всегда `NULL`** (`score`, `time_last_seen`, `time_first_seen`, `threat_type`, `description`) — провайдеры обогащения не реализованы. Самый заметный пробел по ценности данных. | открыт | M | dev/0002 |

## 3. Экстракция / корпус (`EXT`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| EXT-1 | **Новые типы индикаторов: IPv6, email** — паттернов и типов нет. | открыт | M | dev/0004 |
| EXT-2 | **`.onion` v3** — точные base32-границы (56 символов) при ужесточении паттерна. | частично | S | dev/0004 |
| EXT-3 | **Полноценный тест-корпус** + уточнение «не-PSL провайдеров» (ожидания на вариант 2 классификации). | открыт | M | dev/0004 |

## 4. Наблюдаемость (`OBS`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| OBS-D1 | **Продьюсеры диагностик.** Первый реальный код добавлен (`SOURCE.MARKERS_UNMATCHED`); остальные стадии/адаптеры всё ещё бросают `IocExtractorException` — мигрировать на `Diagnostic` и реально задействовать collect-and-continue. | частично | M | dev/0008 |
| OBS-D3 | **ECS-типы строками** — `event.duration` / `ioc.rows` идут через MDC (только `String`); ECS типизирует `event.duration` как `long` → риск mapping-конфликта в Elasticsearch. | открыт | M | dev/0008 |
| OBS-1 | **Таблица `Severity → log.level`** — финализировать теперь, когда появились первые коды каталога. | открыт | S | dev/0007 |
| OBS-2 | **`SINK.CHARSET_UNMAPPABLE` — полноценная диагностика непредставимых символов на выходе.** Сейчас — только WARN-лог на артефакт (`CsvIocSink`/`CsvArtifactRepositories` через `CountingCharsetWriter`); коды диагностики нет, т.к. запись в адаптере не держит `DiagnosticSink`. Протащить через `WriteArtifactsStage` для collect-and-continue + точный счёт по значению. | частично | M | review |
| OBS-3 | **Свести seed-actions док с `EventAction` + вычистить стэйл.** `logging-taxonomy.md` «Seed actions» разошлась с `EventAction.java`: док перечисляет `storage_write`/`artifact_project` (в enum нет), enum несёт `retention_sweep/schema_migrate/schema_validate/db_open/ledger_import/db_health/maintenance/backfill` (в доке нет); плюс стэйл `aggregation_start/complete` — вырезанный β-коллапсом сабсистем, продакшен-продюсеров нет (живут лишь в enum + `LoggingTaxonomyTest`). Свести таблицу с кодом (или перевести в generated-док из констант) + удалить `aggregation_*` из enum и `LoggingTaxonomyTest`. | открыт | S | обсуждение 0011 (logging) |

## 5. Надёжность конфига (`CFG`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| CFG-1 | **Тихий `catch (NumberFormatException ignored)`** на `id.start` ([AppConfig.java:512](../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java#L512)) — опечатка молча уходит в `auto`. | открыт | S | review |
| CFG-2 | **Нет кросс-проверки имён артефактов** `lookup.artifacts` / `artifact-identity.artifacts` ↔ `sink.artifacts` — опечатка → молчаливый неверный baseline / no-op identity config. | открыт | S | review |
| CFG-3 | **«stage 11» протекло в рантайм-ошибку** ([AppConfig.java:421](../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java#L421)) — внутренний номер этапа в сообщении пользователю. | открыт | S | review |

## 6. Код-смелл (`CODE`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| CODE-1 | **Телескопические конструкторы** — `IocExtractionService` (6 шт.), `CsvIocSink`, `CsvArtifactDefinition`; свернуть в builder/factory. | открыт | M | review |
| CODE-2 | **Дублирование «bare IP»** — доменный `NetworkAddressClassifier` (запись) vs строковый `isBareIp` в `CsvArtifactLookupRepository` (дедуп); эквивалентны, но две формулы. | открыт | S | review |
| CODE-3 | **Повторная feature-extraction** — `featureExtractor.extract()` зовётся многократно на один индикатор (провайдеры + фильтр + matchPolicy); мемоизация в пределах батча. | открыт | M | review |
| CODE-4 | **Хрупкий `DiagnosticCatalogTest`** — хардкодит число кодов в каталоге (правился 17→18). | открыт | S | review |

## 7. Архитектура / модульность (`ARCH`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| ARCH-1 | **Гранулярность platform-модулей** — `platform-errors` (13 строк), `platform-diagnostics-logging` (крошечный): кандидаты на слияние. | открыт | M | dev/0009 |
| ARCH-2 | **Размещение regex SPI** — `PatternEngine` в `ioc-domain` (extract) vs отдельный `platform-regex-api`. | seam | M | dev/0009 |
| ARCH-3 | **Spring Modulith / canvas** — после стабилизации reactor-структуры. | seam | M | dev/0009 |

## 8. Экспорт / упаковка / ops (`EXP` / `OPS`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| EXP-1 | **STIX/OpenIOC export sink** — модель уже несёт `stixType()` под это, ждёт sink-адаптер. | открыт | L | roadmap |
| EXP-2 | **`.deb`-пакет** с maintainer-скриптами (сейчас shell-установщик). | открыт | M | packaging |
| OPS-1 | **Мульти-источник — верификация:** несколько файлов разом + новый файл на работающем демоне (watch-service + reconcile). | открыт | S | review |
| OPS-2 | **Логротация средствами ОС** (сейчас только rolling-appender Logback). | открыт | S | packaging |
| EXP-3 | **Активные delivery-коннекторы** — пуш правил фильтрации в security-appliances (firewall EDL/blocklist, proxy ACL/ICAP, DNS RPZ-зоны, DHCP и т.п.) поверх export-контракта 0012. Каждый appliance = свой driven-адаптер за единым delivery-портом (правило «один адаптер = одна интеграция»), родственно `publish`-половине 0011 (та доставляет файлы/срезы, эти — активный push в API/протокол). Усиливает выбор `complete`-дефолта (appliances тянут полный список/зону). | seam | L | dev/0011, обсуждение 0012 |
| EXP-4 | **DuckDB как read/analytics/Parquet-движок** (на радаре, не сейчас) — embedded OLAP за `SqlDialect`/read-портом (слот worknote под смену СУБД); `ATTACH` SQLite-truth напрямую (`sqlite_scanner`), нативный `COPY TO` CSV/Parquet на шаге «материализовать срез» export-саги (§3a/§4a 0012). Truth остаётся SQLite (OLTP, write-path не трогаем), DuckDB только на read/export-стороне → неразрушающая инкрементальная миграция. **Активировать при появлении columnar-потребителя** (data-lake / TI-platform / отчётность); текущему firewall/proxy-потребителю не нужен. | seam | M | обсуждение 0012 (tooling) |
| EXP-5 | **Append/update export modes** — модель и manifest vocabulary допускают расширение, но v1 намеренно исполняет только `complete`; `append` получает `EXPORT.UNSUPPORTED_MODE` до IO. Активировать только вместе с отдельным durable watermark и consumer contract. | seam | L | dev/0012 F4 |
| EXP-6 | **Publish-ledger retention guard** — закрыто в 0011: `PublishLedgerSliceRetentionGuard` pin-ит missing/`PENDING`/`IN_PROGRESS`/`FAILED`, а `SUCCEEDED`/`ABANDONED` разрешают slice delete; publish phase предшествует retention. | закрыт | M | dev/0011, dev/0012 F2 |
| OPS-3 | **Preflight/health-проба sync-эндпоинтов** — на старте/коннекте: reachability+auth, read-проба (fetch) и write→rename→delete-проба (publish); статусы в `ioc health` + sync-категория диагностик; `ERROR` при недостижимости/недостатке прав (fail-fast), best-effort `WARN` на дёшево-наблюдаемых сигналах (anonymous/guest, запись туда, куда не должны). **Провижн хранилища (создание шары / ACL / security) — явно out-of-scope приложения** (least-privilege, attached-resource): удобство развёртывания отдаётся в packaging/IaC + runbook минимальных прав (operator-run). Полный ACL-аудит не обещаем (частично неосуществим по SMB). **Брать после реализации 0011/0012**, согласовать с `--dry-run`/health. | seam | M | обсуждение 0011 (deploy/UX) |

---

## Рекомендованный порядок

1. **Hardening-проход (дёшево, высокая отдача при онбординге источников):**
   `OPS-1` (проверить мульти-источник) + `CFG-1`/`CFG-2`/`CFG-3` (fail-fast конфиг).
2. **Ценность данных:** `OUT-1` (обогащение meta-колонок).
3. **Фича по выбору:** `EXT-1` (IPv6/email, почти весь config-driven) или `EXP-1`
   (STIX-экспорт — модель готова).

## Недавно закрыто (для контекста)

- **Ревью-проход по ING-1/3/5/6 (hardening side-findings):**
  - **ING-9** — устранена коллизия имён при `action: archive` на вложенном дереве:
    `RetentionEntry` теперь несёт корень цели (`baseDir`), `FileSystemRetentionStore.archive`
    зеркалит относительный подпуть под archive-dir (регресс-тест на одинаковый basename из
    разных под-деревьев). Раньше `masks/<day>/<hash>.csv` и `hashes/<day>/<hash>.csv` затирали друг друга.
  - **Триггер прежнего merge-pass** — удалён вместе с partition-staging; direct-to-canonical daemon write
    закрывает прежний scheduled merge контур.
  - **Count-retention** — после удаления `partitions` target остаётся общий пул для плоских
    `done`/`failed`.
  - **Actuator** — `show-details: always` явно связан с loopback-биндом + пометка REVISIT для ING-8
    (вынос web за loopback ⇒ auth / `when-authorized`, иначе утечка внутренних путей).
- **Hash-aware lookup** — `CsvArtifactLookupRepository` грузит и дедуплицирует хэши + per-artifact `maxId`.
- **D2** (зависимость `platform-observability` на `application.pipeline`) — снят выносом generic ETL-контрактов в `platform-etl` (этап 9).
- **ING-3** — health-транспорт демона: actuator/health по HTTP, web-сервер поднимается только в daemon-режиме (`DaemonWebEnvironmentPostProcessor` флипает `spring.main.web-application-type` по `ioc.runtime.mode`; oneshot/CLI остаётся non-web), bind на loopback, expose `health,info`, без `shutdown`. Заодно seed под ING-8.
- **ING-5/ING-6** — partition trigger и wrapper удалены при storage collapse.
- **ING-1** — retention reaper реализован: один `RetentionPolicy` + порт `RetentionStore` (`FileSystemRetentionStore`, реап листовых файлов рекурсивно) + `DaemonMaintenanceScheduler`; конфиг `ioc.maintenance.retention` (targets: done/failed, max-age/max-count, delete|archive).
- **ING-4a** — durable `ingest_run` реализован: post-DB-commit crash-window восстанавливается startup recovery через адресную CSV-проекцию незавершённых артефактов.
- **Кодировки I/O** — задекларированный `ioc.source.charset` теперь реально соблюдается (форс text/HTML через Tika `EncodingDetector`; docx/pdf — по дизайну нет), добавлен `ioc.sink.csv.charset` для всех писателей **и** чтения артефактов в lookup/storage (read=write), непредставимые символы заменяются (не падаем), fail-fast на неизвестном имени кодировки.
- **Атрибуция:** пустой `source` вместо `UNKNOWN` + первый реальный продьюсер диагностик (`SOURCE.MARKERS_UNMATCHED`), частично закрывает OBS-D1.

> Связанные документы: [roadmap.md](roadmap.md) (статус этапов), [dev/](dev/)
> (история решений и исходные `Открытые вопросы`).
