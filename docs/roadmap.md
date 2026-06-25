# Реализационный статус

Исторический статус реализации по этапам. Детальные implementation plans перед
релизом удалены; этот документ оставлен как компактная трассировка, какие
крупные блоки уже вошли в 0.1.0 и какой технический долг остался.

## Процесс

- **Поэтапно.** Этап → реализация → проверка → ревью → следующий этап.
- **Сборка всегда зелёная**; один этап ≈ один сфокусированный набор изменений.
- **Тесты изолированы** ([conventions.md](conventions.md#тесты)): синтетические
  фикстуры в `@TempDir`/`src/test/resources`, **не** артефакты проекта.
- `id`-фикс (ascending + `auto`) закоммичен (`e3d680a`).

## Этапы

| # | Этап | Зависит | Критерии приёмки | Проверка |
|---|---|---|---|---|
| 0 | dev-infra: `mvnw`, CI (build+test), `@Validated` на `IocProperties`, context-smoke-тест | — | `./mvnw test` зелёный; CI на push; невалидный конфиг падает рано и понятно; контекст поднимается на тест-конфиге | mvn test, CI |
| 1 | ArchUnit-правила границ ([boundaries.md](boundaries.md)) | 0 | правила зелёные на текущем коде; намеренное нарушение краснит сборку | mvn test |
| 2 | `IndicatorNormalizer` + `IndicatorFeatures` + `IndicatorFeatureExtractor` + порт `HostClassifier` + `PslHostClassifier` (Guava, `adapter-psl`) | 1 | case-table (синтетика) на нормализацию/признаки/вид хоста (PSL private, `.onion`); домен без Guava (ArchUnit) | mvn test |
| 3 | 4-вариантный rule-based `MatchPolicy` над `IndicatorFeatures` + `classify.rules` | 2 | case-table по бакетам (вар. 1–4, onion, не-PSL провайдер); прогон показывает варианты 2/4 | mvn test + прогон |
| 4 | Конфигурируемый маппинг (`ConfigurableRowMapper` + `ValueProvider`/`Transform` + `columns`); удаление хардкод-мапперов; правка устаревших комментариев конфига | 3 | вывод воспроизводится 1:1 до/после; case-table маппера; DSL ограничен | mvn test + diff прогона |
| 5 | Паттерны `.onion`/telegram + нормализация (authority-port, trailing punct, host-extract) + golden e2e (синтетика) | 2 | корпус + golden зелёные; прогон на полном источнике вменяемый | mvn test + прогон |
| 6 | Диагностика: `Diagnostic` ядро + каталог кодов + `Result`/`Notification` + `FailurePolicy` + порт `DiagnosticSink` ([diagnostics.md](diagnostics.md)) | 1 | юнит-тесты ядра; collect-and-continue; карта ошибок генерится из каталога | mvn test |
| 7 | Конвейер Pipes-and-Filters (`Stage`/`Envelope`/`Result`); эволюция `IocExtractionService` | 6 | стадии за `Stage`; вывод не меняется; collect-and-continue работает | mvn test + прогон |
| 8 | Observability/logging ([logging.md](logging.md), [logging-taxonomy.md](logging-taxonomy.md)): ECS, `MdcScope` из `Envelope.meta`, seed-таксономия, stage-события, bridge `LoggingDiagnosticSink`, daemon ECS-file | 6,7 | MDC без протечек (изолир. тест); ECS-ключи стабильны; diagnostics→`ioc.diagnostic.*` через bridge; daemon rolling ECS-file | mvn test + прогон |
| 9 | Многомодульность ([modularization.md](modularization.md)) | 1,7,8 | Maven reactor; вынос platform/core/adapters/bootstrap; границы держатся компиляцией, Enforcer и ArchUnit | `./mvnw -B -ntp -T 1C verify` |
| 10 | Инжест-демон ([ingestion.md](ingestion.md)): `ioc.runtime.mode`, `IngestSourceUseCase`, watch/poll (SI), автомат каталогов, `IngestionLedger` | 6,7,8,9 | daemon whole-file поток; CLI остаётся `oneshot`; статус-машина + компенсации; идемпотентность | mvn test + daemon e2e |
| 11 | Storage finalization: JDBC dataframe truth, artifact-aware lookup, CSV projection, run-ledger recovery, health | 10 | stable row identity; daemon и oneshot пишут в один canonical store; CSV — проекция; health indicators | mvn test + e2e |

Порядок гибкий — этапы 0–1 дешёвые и включают остальное; 2→3→4 — трек «доменной
корректности»; 5 параллелен; **6 диагностика → 7 pipeline (`Envelope`) → 8
observability** (логирование читает `Envelope.meta`); далее 9 модули, 10 инжест,
11 storage finalization. Заказчик выдаёт этапы в удобном порядке.

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

Единый реестр долга (включая прежние D1/D3 из ревью этапов 6–8 и заложенные
seam'ы из дев-нот) ведётся отдельно — см. **[techdebt.md](techdebt.md)**.
Здесь не дублируем, чтобы было одно место правды.
