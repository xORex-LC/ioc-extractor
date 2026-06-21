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
| 6 | Диагностика: ядро (`Diagnostic`, каталог, `Result`/`Notification`) + `LoggingDiagnosticSink` + MDC + богатый DEBUG + `FailurePolicy` | 1 | юнит-тесты ядра; диагностики в логе по severity; DEBUG-трассировка стадий | mvn test + прогон | да |
| 7 | Конвейер Pipes-and-Filters (`Stage`/`Envelope`/`Result`); эволюция `IocExtractionService` | 6 | стадии за `Stage`; вывод не меняется; collect-and-continue работает | mvn test + прогон | да |
| 8 | Многомодульность (этапы 1→4 из [modularization.md](modularization.md)) | 1,7 | parent-pom+BOM; вынос `ioc-domain`/`ioc-application`; границы держатся компиляцией | mvn -T build | да |
| 9 | Инжест-демон ([ingestion.md](ingestion.md)): `IngestSourceUseCase`, watch (SI), автомат каталогов, `IngestionLedger` (статусы), партиции | 6,7 | whole-file поток; статус-машина + компенсации; идемпотентность | mvn test + e2e | да |
| 10 | Агрегатор (партиции→канон, стабильный `dedupKey→id`) + (опц.) retention | 9 | стабильные id при повторной агрегации; retention off по умолчанию | mvn test + e2e | да |

Порядок гибкий — этапы 0–1 дешёвые и включают остальное; 2→3→4 — трек «доменной
корректности»; 5 параллелен; 6 — фундамент для 7/9. Заказчик выдаёт этапы в
удобном порядке.

## Статус

| Этап | Статус |
|---|---|
| 0 dev-infra (`mvnw`, CI, валидация конфига, context-тест) | `done` |
| 1 ArchUnit-границы | `done` |
| 2 нормализация + признаки + `HostClassifier`/PSL | `done` |
| 3 4-вариантный rule-based `MatchPolicy` | `done` |
| 4 конфигурируемое заполнение артефактов | `done` |
| 5 host-only lowercase (`lower-host`) + golden e2e | `done` |
| 6–10 | `todo` |

(обновляется по мере выполнения: `todo` → `in progress` → `done`)
