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
| `0011-remote-sync.md` | Двунаправленная синхронизация с внешними хранилищами (`ioc.sync`, SMB/smbj): транспорт за портом (`FileTransport`), вход → существующий inbox, выход → сформированные артефакты (агностично к output-mode из 0012); v1 = демон + CLI `ioc sync`. **Проектирование.** Открыто: граница транспорт-агностичности |
| `0012-streaming-dataframe-emission.md` | Формирование датафреймов над непрерывным потоком: окна/триггеры/watermark (Dataflow-модель), output-modes `complete`/`append`, immutable-снимки + маркер готовности, дешёвый консистентный снимок со стороны хранилища (WAL). Cross-cutting: sink ↔ aggregation ↔ storage (ING-4), потребляется доставкой 0011. **Исследование** |

## Формат

`Статус` · `Контекст` · `Решения` (выбор + обоснование + отклонённые варианты) ·
`Следствия` · `Открытые вопросы`. Язык — русский.
