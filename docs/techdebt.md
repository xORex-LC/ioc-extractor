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
| ING-1 | **Retention reaper** — единый декларативный reaper (`ioc.maintenance.retention`) чистит `partitions` + `done` + `failed` по возрасту/количеству (delete/archive); пул-политика `RetentionPolicy`, порт `RetentionStore`, `DaemonMaintenanceScheduler`. | закрыт | M | dev/0001 #6 |
| ING-2 | **Tail-режим для источников** (растущие append-фиды: offset/rotation/checkpoint). **Descoped:** вне домена document-ingest — источники дискретны (Word/HTML, скрейпинг даёт целые документы). При появлении стриминг-источника — новый режим/`SourceReader` тогда. | descoped | L | dev/0001 #1, dev/0006 |
| ING-3 | **Health-транспорт демона** — actuator/health по HTTP, web включается только в daemon (`DaemonWebEnvironmentPostProcessor` по `ioc.runtime.mode`), loopback-bind, expose `health,info`. Первый камень под web driving-adapter (ING-8). | закрыт | M | dev/0001 |
| ING-4 | **Durability ledger + сторадж** — файловый стор → SQLite (`spring-integration-jdbc`) при росте требований. Тянет за собой схему/кэш/проекцию CSV из БД — отдельная итерация (см. ING-7). Сюда же сводятся два retention-вопроса, которым нужен запрос к стору, а не обход ФС: **(а)** развязка retention↔агрегации (реапить только заведомо агрегированные партиции, а не по голому возрасту/количеству); **(б)** per-group count-retention (счёт «N новейших на артефакт/группу», а не по плоскому пулу листьев). | seam | M | dev/0001, review |
| ING-5 | **Триггер агрегации** — `ioc.aggregation.trigger: interval｜on-partition｜both`; `AggregationTrigger` port, событийный kick из `IngestionService` с коалесингом, интервал-страховка. | закрыт | M | dev/0001 |
| ING-6 | **Partition-wrapper boundary** — инвариант зафиксирован ArchUnit: `..domain..` и `..application.pipeline..` не зависят от `..ingest..` (source-key доходит до ядра только как Envelope-metadata). | закрыт | S | dev/0001 |
| ING-7 | **Инкрементальная запись датафреймов** — сейчас полный rewrite+atomic-move на каждый прогон. Append корректен при keep-first (строки иммутабельны), но экономит только запись, не чтение, и теряет атомарность. Решать вместе со стораджем (ING-4): CSV как проекция из БД, кэш, дельта-запись. | seam | M | review |
| ING-8 | **Web driving-adapter** — HTTP как третья точка входа рядом с CLI/file-poll: ops (ING-3) → REST-ингест/запросы → TAXII/STIX-сервер (синергия с EXP-1) + BFF под фронтенд. Эндпоинты живут в отдельном `adapter-web`. **Требование:** REST-эндпоинты, дёргающие use-cases, обязаны открывать `MdcScope` (run-id), как CLI/демон, иначе прогоны теряют корреляцию в логах. **Связка с актуатором:** при выносе web за loopback `management.endpoint.health.show-details` нужно закрыть auth / перевести в `when-authorized` (сейчас `always` безопасен только из-за loopback-бинда). | seam | L | review |
| ING-9 | **Коллизия имён при архивации партиций** — `FileSystemRetentionStore.archive` сплющивал вложенное дерево до имени файла, поэтому `masks/<day>/<hash>.csv` и `hashes/<day>/<hash>.csv` (одинаковый basename = хэш источника) затирали друг друга при `action: archive` → тихая потеря 2 из 3 файлов. Фикс: `RetentionEntry` несёт корень цели (`baseDir`), архив зеркалит относительный подпуть под archive-dir; `REPLACE_EXISTING` теперь перезаписывает только тот же самый элемент. | закрыт | S | review (ревью ING-1) |

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

## 5. Надёжность конфига (`CFG`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| CFG-1 | **Тихий `catch (NumberFormatException ignored)`** на `id.start` ([AppConfig.java:512](../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java#L512)) — опечатка молча уходит в `auto`. | открыт | S | review |
| CFG-2 | **Нет кросс-проверки имён артефактов** `lookup.artifacts` / `aggregation.artifacts` ↔ `sink.artifacts` — опечатка → молчаливый неверный baseline / no-op агрегации. | открыт | S | review |
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

---

## Рекомендованный порядок

1. **Hardening-проход (дёшево, высокая отдача при онбординге источников):**
   `OPS-1` (проверить мульти-источник) + `CFG-1`/`CFG-2`/`CFG-3` (fail-fast конфиг).
2. **Ценность данных:** `OUT-1` (обогащение meta-колонок).
3. **Фича по выбору:** `EXT-1` (IPv6/email, почти весь config-driven) или `EXP-1`
   (STIX-экспорт — модель готова).

## Недавно закрыто (для контекста)

- **Ревью-проход по ING-1/3/5/6 (hardening side-findings):**
  - **ING-9** — устранена коллизия имён при `action: archive` на вложенном дереве партиций:
    `RetentionEntry` теперь несёт корень цели (`baseDir`), `FileSystemRetentionStore.archive`
    зеркалит относительный подпуть под archive-dir (регресс-тест на одинаковый basename из
    разных под-деревьев). Раньше `masks/<day>/<hash>.csv` и `hashes/<day>/<hash>.csv` затирали друг друга.
  - **Триггер агрегации** — `AggregationTrigger.request()` больше не запускает агрегацию
    синхронно на потоке вызывающего до старта планировщика (контракт «never block»): в этом окне
    запрос дропается с debug-логом — идемпотентность (keep-first) + стартовый прогон покрывают.
  - **Count-retention** — задокументирована грубая семантика `max-count` на вложенном дереве (пул
    всех листьев; для `partitions` предпочитать `max-age`); точный per-group счёт отложен в ING-4.
  - **Actuator** — `show-details: always` явно связан с loopback-биндом + пометка REVISIT для ING-8
    (вынос web за loopback ⇒ auth / `when-authorized`, иначе утечка внутренних путей).
- **Hash-aware lookup** — `CsvArtifactLookupRepository` грузит и дедуплицирует хэши + per-artifact `maxId`.
- **D2** (зависимость `platform-observability` на `application.pipeline`) — снят выносом generic ETL-контрактов в `platform-etl` (этап 9).
- **ING-3** — health-транспорт демона: actuator/health по HTTP, web-сервер поднимается только в daemon-режиме (`DaemonWebEnvironmentPostProcessor` флипает `spring.main.web-application-type` по `ioc.runtime.mode`; oneshot/CLI остаётся non-web), bind на loopback, expose `health,info`, без `shutdown`. Заодно seed под ING-8.
- **ING-5** — триггер агрегации стал конфигурируемым (`ioc.aggregation.trigger: interval｜on-partition｜both`): `AggregationTrigger` port, событийный kick из `IngestionService` после архивации партиции (коалесинг через `pending`-флаг + single-thread executor), интервал остаётся страховкой.
- **ING-6** — инвариант partition-wrapper зафиксирован ArchUnit (`..domain..` и `..application.pipeline..` ⊥ `..ingest..`); код уже был чист, теперь протечка краснит сборку.
- **ING-1** — retention reaper реализован: один `RetentionPolicy` + порт `RetentionStore` (`FileSystemRetentionStore`, реап листовых файлов рекурсивно) + `DaemonMaintenanceScheduler`; конфиг `ioc.maintenance.retention` (targets: partitions/done/failed, max-age/max-count, delete|archive). Заглушка `ioc.aggregation.retention` удалена.
- **Кодировки I/O** — задекларированный `ioc.source.charset` теперь реально соблюдается (форс text/HTML через Tika `EncodingDetector`; docx/pdf — по дизайну нет), добавлен `ioc.sink.csv.charset` для всех писателей **и** чтения артефактов в lookup/агрегации (read=write), непредставимые символы заменяются (не падаем), fail-fast на неизвестном имени кодировки.
- **Атрибуция:** пустой `source` вместо `UNKNOWN` + первый реальный продьюсер диагностик (`SOURCE.MARKERS_UNMATCHED`), частично закрывает OBS-D1.

> Связанные документы: [roadmap.md](roadmap.md) (статус этапов), [dev/](dev/)
> (история решений и исходные `Открытые вопросы`).
