# Дорожная карта реализации

Живой операционный план: дизайн зафиксирован в `docs/`, здесь — **порядок
реализации** по этапам. Обновляется по мере выполнения (колонка «Статус»).

## Процесс

- **Поэтапно.** Заказчик выдаёт этап → реализация → проверка → отчёт с
  доказательствами → ревью → следующий этап.
- **Plan Mode** — перед **нетривиальными** этапами: вхожу в Plan Mode, показываю
  file-level план + критерии приёмки, заказчик одобряет, затем код. Тривиальные —
  сразу.
- **Сборка всегда зелёная**; один этап ≈ один сфокусированный коммит.
- **Тесты изолированы** ([conventions.md](conventions.md#тесты)): синтетические
  фикстуры в `@TempDir`/`src/test/resources`, **не** артефакты проекта.
- `id`-фикс (ascending + `auto`) закоммичен (`e3d680a`).

## Этапы

| # | Этап | Зависит | Критерии приёмки | Проверка | Plan Mode |
|---|---|---|---|---|---|
| 0 | dev-infra: `mvnw`, CI (build+test), `@Validated` на `IocProperties`, context-smoke-тест | — | `./mvnw test` зелёный; CI на push; невалидный конфиг падает рано и понятно; контекст поднимается на тест-конфиге | mvn test, CI | нет |
| 1 | ArchUnit-правила границ ([boundaries.md](boundaries.md)) | 0 | правила зелёные на текущем коде; намеренное нарушение краснит сборку | mvn test | нет |
| 2 | `IndicatorNormalizer` + `IndicatorFeatures` + `IndicatorFeatureExtractor` + порт `HostClassifier` + `PslHostClassifier` (Guava, `adapter-psl`) | 1 | case-table (синтетика) на нормализацию/признаки/вид хоста (PSL private, `.onion`); домен без Guava (ArchUnit) | mvn test | да |
| 3 | 4-вариантный rule-based `MatchPolicy` над `IndicatorFeatures` + `classify.rules` | 2 | case-table по бакетам (вар. 1–4, onion, не-PSL провайдер); прогон показывает варианты 2/4 | mvn test + прогон | да |
| 4 | Конфигурируемый маппинг (`ConfigurableRowMapper` + `ValueProvider`/`Transform` + `columns`); удаление хардкод-мапперов; правка устаревших комментариев конфига | 3 | вывод воспроизводится 1:1 до/после; case-table маппера; DSL ограничен | mvn test + diff прогона | да |
| 5 | Паттерны `.onion`/telegram + нормализация (authority-port, trailing punct, host-extract) + golden e2e (синтетика) | 2 | корпус + golden зелёные; прогон на полном источнике вменяемый | mvn test + прогон | да |
| 6 | Диагностика: `Diagnostic` ядро + каталог кодов + `Result`/`Notification` + `FailurePolicy` + порт `DiagnosticSink` ([diagnostics.md](diagnostics.md)) | 1 | юнит-тесты ядра; collect-and-continue; карта ошибок генерится из каталога | mvn test | да |
| 7 | Конвейер Pipes-and-Filters (`Stage`/`Envelope`/`Result`); эволюция `IocExtractionService` | 6 | стадии за `Stage`; вывод не меняется; collect-and-continue работает | mvn test + прогон | да |
| 8 | Observability/logging ([logging.md](logging.md), [logging-taxonomy.md](logging-taxonomy.md)): ECS, `MdcScope` из `Envelope.meta`, seed-таксономия, stage-события, bridge `LoggingDiagnosticSink`, daemon ECS-file | 6,7 | MDC без протечек (изолир. тест); ECS-ключи стабильны; diagnostics→`ioc.diagnostic.*` через bridge; daemon rolling ECS-file | mvn test + прогон | да |
| 9 | Многомодульность ([modularization.md](modularization.md)) | 1,7,8 | Maven reactor; вынос platform/core/adapters/bootstrap; границы держатся компиляцией, Enforcer и ArchUnit | `./mvnw -B -ntp -T 1C verify` | да |
| 10 | Инжест-демон ([ingestion.md](ingestion.md)): `ioc.runtime.mode`, `IngestSourceUseCase`, watch/poll (SI), автомат каталогов, `IngestionLedger`, partition output | 6,7,8,9 | daemon whole-file поток; CLI остаётся `oneshot`; статус-машина + компенсации; идемпотентность; канон. агрегатор не входит | mvn test + daemon e2e | да |
| 11 | Финализация daemon: агрегатор (партиции→канон), stable id sidecar, artifact-aware lookup, health | 10 | стабильные id при повторной агрегации; `AGGREGATED` в ledger; retention off; health indicators | mvn test + e2e | да |

Порядок гибкий — этапы 0–1 дешёвые и включают остальное; 2→3→4 — трек «доменной
корректности»; 5 параллелен; **6 диагностика → 7 pipeline (`Envelope`) → 8
observability** (логирование читает `Envelope.meta`); далее 9 модули, 10 инжест,
11 агрегатор. Заказчик выдаёт этапы в удобном порядке.

## Статус

| Этап | Статус |
|---|---|
| 0 dev-infra (`mvnw`, CI, валидация конфига, context-тест) | `done` |
| 1 ArchUnit-границы | `done` |
| 2 нормализация + признаки + `HostClassifier`/PSL | `done` |
| 3 4-вариантный rule-based `MatchPolicy` | `done` |
| 4 конфигурируемое заполнение артефактов | `done` |
| 5 host-only lowercase (`lower-host`) + golden e2e | `done` |
| 6 диагностика | `done` |
| 7 pipeline (Envelope) | `done` |
| 8 observability/logging | `done` |
| 9 многомодульность | `done` |
| 10 инжест-демон | `done` |
| 11 daemon finalization | `done` |

(обновляется по мере выполнения: `todo` → `in progress` → `done`)

## Открытый технический долг

По итогам ревью этапов 6–8 (подробно — [notes/0008](notes/0008-stage-6-8-implementation-followups.md)):

- **D1 — интеграция diagnostics в реальный поток.** Producer-ов `Diagnostic` ещё
  нет (адаптеры/стадии бросают `IocExtractorException`); `FailurePolicy` и
  `DiagnosticSink` подключены, но пока работают вхолостую. Кандидат на отдельный
  под-этап перед/вместе с инжестом (этап 10).
- **D3 — ECS-типы `event.duration`/`ioc.rows`** сериализуются строками (MDC). К
  пересмотру при подключении внешнего collector/строгого ECS-маппинга.

Закрыто этапом 9: прежний долг D2 (`platform-observability` зависел на
`application.pipeline`) снят переносом generic ETL-контрактов в
`platform/platform-etl`; `platform-observability` зависит только на generic
`PipelineObserver` API, без зависимости на `ioc-application`.
