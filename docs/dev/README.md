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
| `0013-event-driven-coordination.md` | Гибридная модель координации: control-plane события (`ApplicationEventPublisher` за framework-free портом `ControlEventPublisher`/`platform-events`) + transformation на `platform-etl` (library, не SI) + edge-IO на Spring Integration; detection ⊥ execution, smart-poll fetch / event-driven publish, correctness-via-reconcile, YAGNI-seam'ы (DLQ/outbox/CHANGE_NOTIFY). Реализует OPS-4 и «Post-v1 уточнение» 0011. **Принято, не реализовано (E0–E5).** |

## Формат

`Статус` · `Контекст` · `Решения` (выбор + обоснование + отклонённые варианты) ·
`Следствия` · `Открытые вопросы`. Язык — русский.
