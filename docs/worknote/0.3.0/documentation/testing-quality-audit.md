---
title: "DOC-01B.3 — Тестирование и качество сборки"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified for DOC-01B.3"
document_type: "Audit and verification evidence"
source_of_truth: false
language: "ru"
---

# DOC-01B.3 — Политика тестов, coverage gate и качество сборки

Дата: 2026-11-02, Asia/Irkutsk. HEAD:
`5edd3af3a955c7cdd087e8b5917176c58285ae9b`, ветка `docs/quality`.
На входе сохранены незакоммиченные `DOC-01B.1` и `DOC-01B.2`.
Контракт — [R030-DOC](../goals/R030-DOC-documentation.md).

Пользователь согласовал TESTING и build-quality с проверкой точности,
полноты, назначения, переводов и особым вниманием к полезным тестам вместо
формального повышения процента. Применены code-documenter и test-quality;
общие рекомендации навыков не заменяют действующую политику проекта.

## Назначение документов

- TESTING владеет качеством тестов, lifecycle/tags, требованиями к покрытию,
  внешним/диагностическим evidence и политикой нестабильных тестов.
- build-quality объясняет исполняемую механику Maven, отчётов, анализаторов и
  сопровождения областей. Не превращается в журнал прежних замеров/извлечений.
- Точные множества, счётчики, зависимости и версии остаются у реестров/POM/tooling;
  локальная структура — у README. Полный аудит всех README этого пакета не выполнен.

## Находки и полнота

| ID | Наблюдение | Результат |
|---|---|---|
| `DOC-T01` | TESTING описывал 24 production-группы/23 локальных отчёта вместо текущих 25/24; стабильность ссылалась на 25 функциональных JAR | Числа сверены с root validate; TESTING ссылается на owning registry, текущий компактный topology overview сохранён в build-quality |
| `DOC-T02` | Минимумы/ratchets были описаны, но путь от непокрытой ветви к полезному тесту отсутствовал | Добавлена отдельная политика покрытия и последовательность contract → отчёт → сценарий → независимое ожидание → направленная проверка → полный gate → review счётчиков |
| `DOC-T03` | Краткое требование behavior tests не объясняло достаточную силу утверждений и выбор уровня | Добавлены нормальные/граничные/отрицательные/частичные/recovery/ownership cases по существенности, независимый oracle, изоляция, осмысленные mocks, запрет padding и проверок private calls ради процента |
| `DOC-T04` | «Малые модули» могли читаться как автоматическое определение политики instruction count; не объяснялись zero-denominator переходы и различия local/aggregate | Источник instruction policy — явный столбец реестра; добавлены точные сравнения, изменение знаменателя, dilution example и авторитетность aggregate groups с downstream execution |
| `DOC-T05` | Последний раздел build-quality называл три admitted components, configuration reactor-only и старые этапные топологии | Описаны шесть текущих библиотек, четыре component consumers + events job и aggregate status; precise dependency/version rules делегированы tooling/catalog/publication guide |
| `DOC-T06` | Порядок добавления Maven-модуля перечислял static-analysis registries, но не coverage/floor/ratchet/report/source-suite admission; skip был описан как property | Добавлены все четыре области, локальный отчёт/group/counters/floor entry и deliberate suite-universe update; явный literal skip нужен в SpotBugs plugin configuration |
| `DOC-T07` | TESTING содержал подробную историю additions/moves suites, build-quality — частный исторический SpotBugs/CPD/PMD refresh | Общие документы оставляют устойчивые контракты и owning links; исходные evidence, baseline и component release records не менялись |
| `DOC-T08` | У обоих canonical EN отсутствовали обязательные RU-пары | Добавлены RU-переводы и навигация; структура, identifiers, локальные targets и literal blocks сверены |
| `DOC-T09` | Схема CI не разделяла successful input revalidation и always-retained reports достаточно явно | Уточнены три этапа: успешный Maven → input validation; завершение CI → доступные отчёты; успешный build job → отдельная best-effort отправка |

## Привязка к живым источникам

Списки приведены в source sets `testing` и `build` в
[source-sets.tsv](source-sets.tsv). Ключевые сопоставления:

| Требование / утверждение | Живой источник | Свидетельство / ограничение |
|---|---|---|
| Минимумы 75/80 reactor и 85/90 domain/application, остальные ratchet-only | `coverage-floors.tsv`; `CoverageVerifier.applyFloor/compareFloor` | Exact integer comparison, floor-negative scenarios; универсальный 80% или обязательный 90% patch gate не вводится |
| Ratio non-regression, missed branches, instruction policy | `coverage-ratchets.tsv`; `applyRatchet/compareRatio` | Даже улучшение 80/100 → 170/200 не пропускает рост missed 20 → 30; `enforced` определяется реестром |
| Production topology, отсутствие ручных exclusions | `coverage-scope.tsv`, root/report POM, `validatePolicy/rejectModuleCoverageFilters` | 31 reactor projects, 25 groups, 24 local reports и aggregate-only errors; TCK вне production |
| Aggregate authority и точная целостность | `CoverageVerifier.verifyReports`, JaCoCo append/report wiring | Группы/суммы/expected local data проверяются отдельно от процентов; не предполагается равенство local counters и downstream aggregate group |
| Discovery/tag/report union | `TestLifecycleVerifier`, `test-lifecycle.properties`, Surefire/Failsafe POM | Текущий source universe: 234 fast, 83 integration, 5 external, 312 deterministic offline; числа остаются в реестре |
| Поведенческий пример | `RecordLifecycleTest.uses_a_half_open_active_interval` и соседние renewal/rejection tests | Точный метод с фикстурами своего класса, явные UTC времена и реальные AssertJ assertions; запуск всех четырёх тестов класса |
| Команды / evidence handoff | Makefile, `tools/ci/build.sh`, `codecov.sh`, `test-pilots.sh`, `codecov.yml`, CI/test-pilots workflows | Selector resolution/dry-run; tools contracts. Полные/дорогие команды не выдаются за выполненные |
| Analyzer / module / publication scope | `BuildQualityVerifier`, `SpotBugsBaselineVerifier`, manifests/POM/rulesets, library-publication tooling и CI | Synthetic policy matrices и root validation; standalone network consumers и анализаторы заново не квалифицированы |

## Содержание политики coverage

Политика явно различает:

1. Исполняемые минимумы, per-scope baseline и целостность полного отчёта.
2. Доказательство поддерживаемого результата: что изменится при ошибке и какое
   независимое утверждение её обнаружит.
3. Выбор теста по владельцу поведения: pure decisions, TCK порта, real adapter
   integration, сквозной flow, provisioned external contract.
4. Диагностику оставшихся gaps, осмысленный residual review и same-change
   ratchet maintenance без ослабления других метрик.

Минимум не является целью остановки; процент не доказывает качество assertions,
полноту recovery, live support или resource acceptance. Повторные easy inputs,
бессмысленные getters/constructors, broad non-null и private-call mirroring ради
числа запрещены как способ достижения gate. Это не запрет проверять реальный
value/constructor contract или законно упрощать код: такие изменения требуют
review поведения, знаменателя, тестов и owning counters.

## Проверки

| Проверка | Результат |
|---|---|
| `make doctor-core` | Пройдено, Java 21 |
| `./mvnw -B -ntp -N validate` | Пройдено: root plugin wiring, registries, coverage и lifecycle source universe |
| Synthetic matrices в root validate | BuildQuality: 8 happy / 59 negative; SpotBugs baseline: 4/24; TestLifecycle: 2/8; Coverage: 7/35. Всего 147 сценариев |
| `make test-one MODULE=core/ioc-application TEST=RecordLifecycleTest` | 4 tests, 0 failures/errors/skips; буквальный Java-пример совпадает с выполненным исходником в полном контексте класса |
| EN/RU | 17/27 headings, по три fenced blocks; локальные targets и inline identifiers (кроме оформления link labels) совпадают; выполнен semantic review |
| Make-примеры | 12 dry-runs, точные selectors разрешаются. Полные verify/integration/pilots не исполнялись как часть этой проверки |
| `bash tools/tests/tools-contract-test.sh` | PASS; проверены фасад команд, Codecov/CI contracts и изоляция tools |
| Inventory / protected history | 342 уникальных paths, 64 source sets; текущие/исходные SHA-256 и все source paths сверены; Java/POM/tools/workflows/policy registries и ADR/catalogs/baseline неизменны |
| `make docs` | 2188 occurrences, 600 unique, 1968 OK, 0 errors, 220 excluded; offline link gate |
| `git diff --check` / новые Markdown | Пройдено; новые файлы отдельно проверены на trailing whitespace |

Нет изменения production/test Java, POM, coverage floors/ratchets/scope,
test-lifecycle counts, analyzer rules/baselines или workflows. Старые ADR,
generated catalogs и исходный release baseline сохранены. Это аудит двух
документов и их переводов, не всего тестового кода репозитория и не новое
измерение production coverage.

## Состояние и оставшиеся обязательства

После пакета: 342 Markdown paths (329 исходных + 13 новых), 64 source sets.
Отсутствуют 70 обязательных RU-пар: 4 root docs, 14 capability docs, 49 ADR,
3 guides. Первый capability doc проверен в этом связанном комплекте;
остальные DOC-02 остаются открытыми.

Полные verify/PMD не перезапускались: сохранённые успешные записи относятся к
`191e74146dddeeab36bf59d9d09020a92bef0620`, freshness=false. Направленный тест
не является свежим доказательством полного source/report union. R030-DOC,
R030-SEC и R030-REL целиком этим пакетом не закрываются.
