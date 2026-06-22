# Технический долг

Единый реестр технического долга и заложенных seam'ов проекта — одно место
правды. Раньше эти пункты были размазаны по `Открытые вопросы` дев-нот
([dev/](dev/)), `roadmap.md` и ревью; здесь они сведены и приоритизированы.

**Как вести:** у каждого пункта стабильный ID; при закрытии — статус `закрыт`
с ссылкой на коммит/этап, не удаляем. Новый долг → строка в нужную секцию.

**Статус:** `открыт` · `частично` · `seam` (сознательно отложенный задел,
интерфейс/инвариант уже заложен) · `закрыт`.
**Эффорт:** `S` (≤ полдня) · `M` (день-два) · `L` (итерация).

---

## 1. Демон / ингест (`ING`)

| ID | Долг | Статус | Эфф. | Источник |
|---|---|---|---|---|
| ING-1 | **Retention / `PartitionReaper`** — партиции не чистятся никогда; нужен критерий (возраст/объём) и режим (delete/archive/compress). Сейчас `ioc.aggregation.retention.enabled` — заглушка-`throw`. | открыт | M | dev/0001 #6 |
| ING-2 | **Tail-режим** для растущих фидов (offset/rotation/checkpoint; inode vs file-id). Сейчас только whole-file. | seam | L | dev/0001 #1, dev/0006 |
| ING-3 | **Health-транспорт демона** — health-indicators зарегистрированы, но не экспонированы (`web-application-type: none`); нужен Actuator/heartbeat-контур. | открыт | M | dev/0001 |
| ING-4 | **Durability ledger** — файловый стор → SQLite (`spring-integration-jdbc`) при росте требований. | seam | M | dev/0001 |
| ING-5 | **Триггер агрегации** — по расписанию (сейчас, интервал) vs событие «партиция готова». | открыт | M | dev/0001 |
| ING-6 | **Partition-wrapper boundary** — не протаскивать source-key в domain/application API сверх envelope-metadata. | наблюдение | S | dev/0001 |

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
| OPS-3 | **`AGENTS.md`** — устаревший почти-дубликат `CLAUDE.md` (оба gitignored): синхронизировать или удалить. | открыт | S | review |

---

## Рекомендованный порядок

1. **Hardening-проход (дёшево, высокая отдача при онбординге источников):**
   `OPS-1` (проверить мульти-источник) + `CFG-1`/`CFG-2`/`CFG-3` (fail-fast конфиг).
2. **Эксплуатация вдолгую:** `ING-1` (retention) — иначе партиции копятся бесконечно.
3. **Ценность данных:** `OUT-1` (обогащение meta-колонок).
4. **Фича по выбору:** `EXT-1` (IPv6/email, почти весь config-driven) или `EXP-1`
   (STIX-экспорт — модель готова).

## Недавно закрыто (для контекста)

- **Hash-aware lookup** — `CsvArtifactLookupRepository` грузит и дедуплицирует хэши + per-artifact `maxId`.
- **D2** (зависимость `platform-observability` на `application.pipeline`) — снят выносом generic ETL-контрактов в `platform-etl` (этап 9).
- **Атрибуция:** пустой `source` вместо `UNKNOWN` + первый реальный продьюсер диагностик (`SOURCE.MARKERS_UNMATCHED`), частично закрывает OBS-D1.

> Связанные документы: [roadmap.md](roadmap.md) (статус этапов), [dev/](dev/)
> (история решений и исходные `Открытые вопросы`).
