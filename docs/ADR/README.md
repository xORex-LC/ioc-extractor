# Dev-документация

## Назначение

Комплект документов для разработчиков: история обсуждений и **решений**
(ADR-lite), контекст реализации и причины выбора подходов. В отличие от
релизных документов верхнего уровня, эти файлы фиксируют «почему так» и историю
выбора, а не являются пользовательским описанием продукта.

**Правило:** один документ — одно направление/решение. Нумерация по порядку
(`NNNN-slug.md`). Принятое решение отражается в основных доках
(`architecture.md`, `ingestion.md`, …); dev-документ остаётся как обоснование.

## Структура

| Файл | О чём |
|---|---|
| `0000-foundations.md` | Фундамент: гексагон+onion, Maven, Spring Boot (CLI), RE2/J+JDK, Tika, CSV-артефакты |
| `0001-streaming-ingestion.md` | Стриминговый демон-инжест: детект, автомат каталогов, идемпотентность, retention, параллелизм |
| `0002-output-mapping-and-matching.md` | Конфигурируемое заполнение артефактов + 4-вариантная классификация (PSL), id-фикс |
| `0003-diagnostics.md` | Подсистема диагностики: каталог, шаблоны (JSON-схема №8 отменена в пользу ECS — см. 0007) |
| `0004-extraction-and-test-corpus.md` | Типы/нормализация (.onion, telegram), тест-корпус и изоляция тестов |
| `0005-services-and-pipeline.md` | DDD-сервисы, ETL-конвейер (P&F+Envelope+Result), границы, модульность |
| `0006-design-review-refinements.md` | Уточнения по итогам ревью: SourceFeed/driving-порт, PSL в адаптере, статусы инжеста, стабильные id, лимит DSL |
| `0007-logging-observability.md` | Логирование/observability как отдельная подсистема: ECS, MDC←Envelope, отмена 0003 №8 |
| `0008-stage-6-8-implementation-followups.md` | Ревью реализации этапов 6–8: устранённые находки + отложенный техдолг (D1 интеграция diagnostics, D3 ECS-типы; D2 закрыт этапом 9) |
| `0009-modularization-granularity.md` | Гранулярность реактора (14 модулей, единый `ioc-domain`); coupling vs cross-cut; критерий выноса capability; `refang` — кандидат №1; защита границ слоями (Maven/Enforcer/ArchUnit), Modulith отложен |
| `0010-health-actuator.md` | Health/Actuator по HTTP только в daemon (`DaemonWebEnvironmentPostProcessor` гейтит по `runtime.mode`), loopback-bind, прижатый пул Tomcat, systemd-hardening; задел под web driving-adapter (ING-8) |
| `0011-remote-sync.md` | Двунаправленная синхронизация с внешними хранилищами (`ioc.sync`, SMB/smbj): transport-neutral fetch → inbox и publish verified export slices, JDBC ledgers, daemon/CLI/health. **Реализовано (S0–S8).** |
| `0012-streaming-dataframe-emission.md` | Реализованный Artifact Emission поверх БД-truth: atomic revision, strict streaming snapshot, immutable complete-slices + manifest/`_SUCCESS`, CAS saga/recovery, CLI/daemon cadence, health и slice retention. Готовый локальный контракт потребляется будущей доставкой 0011. **Реализовано (C0–C11)** |
| `0013-event-driven-coordination.md` | Гибридная модель координации: control-plane события (`ApplicationEventPublisher` за framework-free портом `ControlEventPublisher`/`platform-events`) + transformation на `platform-etl` (library, не SI) + edge-IO на Spring Integration; detection ⊥ execution, smart-poll fetch / event-driven publish, correctness-via-reconcile, YAGNI-seam'ы (DLQ/outbox/CHANGE_NOTIFY). **Несущий anti-broker инвариант** (платформа = event model + publish contract, доставка/durable — адаптеры; Modulith/брокер за портом). Reference-grounded (EIP, Fowler Event Notification, Spring Modulith, Richardson outbox). Реализует OPS-4 и «Post-v1 уточнение» 0011. **Реализовано базовое ядро S0–S8; durable outbox/broker/CHANGE_NOTIFY остаются отложенными seam'ами.** |
| `0014-event-driven-ingest-to-delivery.md` | Расширение 0013 на цепочку `ingest → export → delivery` (не supersede): инвариант «эмитить факт везде, где он становится истиной» (recovery тоже эмитит `SliceCompleted`); ingest→export fast-path `CanonicalArtifactsChanged` вместо poll-over-revision; delivery fan-out (`SliceCompleted` — факт, N независимых consumers со своими ledger'ами + обобщённый retention guard над `DeliverySink`); retention остаётся periodic, event-nudge отложен. Аддитивно, backstop'ы сохраняются. Связь: EXP-3, OPS-4, ING-7. **Принято; Р1/Р2 реализованы и проверены на стенде, Р3/Р4 остаются trigger-deferred design state.** |
| `0015-retire-legacy-csv-lookup-storage.md` | Вывод legacy CSV lookup/storage-режима после перехода dataframe truth на SQLite/JDBC: `lookup.contains` больше не application policy, storage dedup/provenance принадлежит canonical repository (`row_key` + `<artifact>_sources`), batch-dedup остаётся only within-run, `LookupRepository` разделяется на удаляемый existence check и узкий id-baseline seam, CSV остаётся только projection/export. |
| `0016-config-preflight-strict-binding.md` | Надёжность конфигурации (блок CFG): единый startup preflight для `ioc.*` до runtime graph; semantic collect-all через `configurationPropertiesValidator`, unknown/deprecated keys через reflection-shape preflight + legacy `FailureAnalyzer`, ссылочная целостность config→config (identity↔sink, key-columns⊆columns, дубликаты, id policy), registry-backed keys до обработки файла/записи; compact-конструкторы — только нормализация/дефолты; tombstones удалены из модели; закрытые словари → enum/value types, ранние consumers и `IocProperties` используют одну грамматику, `id.start` — sealed value contract. `IOC_*` также строгий через schema-aware matcher; успешный старт показывает value-free winning overrides. **Реализовано 2026-07-09--10 (`b0e5157..2c224c2`).** |
| `0017-diagnostics-first-class-outcome.md` | Диагностика как first-class processing outcome (OBS-D1): envelope policy и saga state machine остаются разными контурами; `DiagnosticImpact` фиксирует ELEMENT/RUN/OPERATION; runner исполняет exactly-once flush до policy без re-emit; `failure-policy` и bounded accumulation конфигурируемы, а `ExtractionResult` возвращает completion quality + diagnostic summary. Write-path разделён на side-effect-free artifact preparation → policy checkpoint → canonical commit; classification и TRACE питаются едиными pure domain decision outcomes. Raw context проходит redaction; resilient sink не меняет outcome; durable quarantine/report/occurrence delivery оставлены за явными seams без broker. **Принято 2026-07-12; реализовано 2026-07-13 (`354c5c6..32b4202`, review fixes `d34b733`, `c990b98`).** |
| `0018-typed-ecs-structured-logging.md` | Типизированный ECS logging contract (OBS-D3): ambient string correlation остаётся в MDC, event-local duration/counts/boolean идут через SLF4J 2 key/value pairs; `LogField` получает исполнимый JSON scalar type и generated catalog, arbitrary keys/numeric MDC запрещаются. Wire-format migration, collision semantics, async/concurrency и будущий generated Elasticsearch component-template seam описаны явно. **Принято и реализовано 2026-07-16; baseline/physical representation частично superseded ADR-0019.** |
| `0019-spring-boot-4-nested-ecs.md` | Финальный framework baseline релиза (изначально 0.1.1, retargeted на 0.2.0 датированным дополнением): одна supported line Spring Boot `4.0.x` (`4.0.7` candidate), Boot 3.5 только migration bridge. Фиксирует nested ECS JSON как публичный wire format, узкую роль `IocEcsStructuredLogEncoder`, SemVer disposition, plan B и exact-candidate smoke evidence. **Принято и реализовано 2026-07-21; финальный candidate smoke остаётся release gate.** |
| `0020-canonical-record-expiration-lifecycle.md` | Record validity принадлежит canonical artifact lifecycle; internal `valid_until`, exact active-read predicate, bounded expiration/history reconciliation, explicit one-way legacy activation, clock safety и duplicate-receipt fallback образуют один согласованный data-quality contract. Existing public schemas остаются неизменными. **Принято 2026-08-16; P0–P6 lifecycle scope реализован. §4 о never-reused external ID superseded ADR-0021.** |
| `0021-stable-reusable-export-slots.md` | Внешний artifact `id` отделяется от canonical/lifecycle identity и трактуется как stable sparse reusable export slot: surviving active rows не перенумеровываются, expired slots освобождаются при следующем eligible export, новые rows занимают минимальные holes. Узко supersede'ит ADR-0020 §4. **Принято 2026-08-19; P7 candidate реализован, packaged qualification pending.** |

## Формат

`Статус` · `Контекст` · `Решения` (выбор + обоснование + отклонённые варианты) ·
`Следствия` · `Открытые вопросы`. Язык — русский.
