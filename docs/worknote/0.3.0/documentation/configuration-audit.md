---
title: "DOC-02A — Конфигурация и граница запуска"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified for DOC-02A"
document_type: "Audit and verification evidence"
source_of_truth: false
language: "ru"
---

# DOC-02A — Конфигурация, проверка и ошибки запуска

Начало: 2026-11-03; завершение: 2026-11-04, Asia/Irkutsk. HEAD:
`4641e5ea9ff5e75edbf61cdff6581108c60f04e4`, ветка `docs/quality`.
На входе Git-дерево чистое; изменения этого пакета ещё не закоммичены.
Контракт — [R030-DOC](../goals/R030-DOC-documentation.md).

Scope: configuration и configuration-failure-reports в dev/, операторский
configuration guide, три EN/RU-пары, навигация и inventory. Применены
code-documenter, java-code-review, cli-developer и spring-boot-engineer с учётом
действительного Boot 4 и Linux/systemd-контракта проекта. Формат задан
CONVENTIONS и согласованным пакетом. Обзор исходников подтверждает утверждения
документов; это не общий аудит всех публичных Java API, библиотечных guides,
bootstrap/packaging README или installed stand.

## Находки и решения

| ID | Наблюдение и влияние | Результат |
|---|---|---|
| `DOC-CFG01` | Операторский guide называл `ioc-config check` синтаксической проверкой; это скрывало binding, semantic и registry checks | Разделены YAML-only, semantic command и установленный helper; описаны источники, успешные коды и ошибки |
| `DOC-CFG02` | Восстановление YAML после apply могло читаться как возврат всего приложения к прежнему состоянию | Описаны отдельные отказы до замены/после перезапуска, backup/rejected files, повторная проверка health и пределы восстановления: миграции, активация lifecycle, принятая работа и внешние эффекты не отменяются |
| `DOC-CFG03` | Справочник не раскрывал imports, profiles, JSON, затенённые объявления и роль effective environment в check | Добавлены участие источников, приоритет JSON, полная замена списков и объединение словарей, резервирование IOC_*, context/source guards и отсутствие изоляции ресурсов |
| `DOC-CFG04` | Candidate переносится в установленный etc: относительные импорты рядом с исходным файлом не определяют проверяемый вход | Описана база относительных импортов staged copy; check не резервирует candidate/окружение для apply, который проверяет заново |
| `DOC-CFG05` | Предварительная проверка могла восприниматься как полный рабочий startup gate | Явно выделены runtime/state-dependent проверки, файловые права, существующая БД, удалённый доступ и recovery. Отдельный JVM-пример подтверждает: append в export profile получает SEMANTIC_VALID; неподдерживаемое исполнение отклоняет ExportPlanCatalog рабочего графа. Реализация checker в этом пакете не расширялась |
| `DOC-CFG06` | Dev-документ приписывал production template пустые import catalogs; справочник пропускал третий export profile и помещал export ledgers в export root | Разделены пустые classpath catalogs и объявленный production aggregate contract/authority без источников; добавлен ioc-aggregate, координация остаётся в service DB, ID allocation отделено от export slots |
| `DOC-CFG07` | Бюджеты обработки были описаны без существенных взаимных ограничений и с устаревшим утверждением о whole-text/occurrence buffers вне workspace | Описаны потоковый источник/workspace, предел области admission против heap/RSS, field/row/disk relationships, workers ≤ window ≤ pending ≤ 256 и max-source-bytes ≤ workspace-bytes / 4; maximum-row-bytes также задаёт числовой предел символов области HTML parser |
| `DOC-CFG08` | Environment table обещала переключение Java через JAVA_HOME и не различала JVM options daemon/checker/oneshot wrapper | Указан установочный выбор Java, действительная область JAVA_OPTS, права файла, синтаксис EnvironmentFile и необходимость restart |
| `DOC-CFG09` | Maintenance retention не показывал общий scheduler для slice reaper и независимость от leaf enabled | Уточнены общие interval/initial-delay и независимые области хранения slices, leaf files, import terminal units и lifecycle history |
| `DOC-CFG10` | Failure report не перечислял usage/unreadable/source-override outcomes и мог подразумевать общий SEMANTIC_INVALID envelope | Добавлены реальные коды, фаза появления envelope, значения exit, действия оператора и границы собственного вывода против обычных Boot analyzers/DEBUG/appenders |
| `DOC-CFG11` | Две dev RU-пары отсутствовали; старый RU-guide содержал английские фразы и сокращал важные ограничения относительно EN | Созданы две RU-страницы, перепроверены и переработаны таблицы/проза справочника, совпадают структура, identifiers, ссылки и буквальные блоки; добавлена навигация |

## Привязка к живым источникам

Source set `configuration` в [source-sets.tsv](source-sets.tsv) содержит пути.

| Контракт | Владелец / свидетельство | Ограничение |
|---|---|---|
| Shape, defaults, допустимые значения и budgets | IocProperties, IocProcessingProperties, classpath/production YAML, converters, IocConfigPreflight | Имена в guide не доказывают семантику; исходники и scalar defaults проверены отдельно |
| Strict names, environment, shadowed declarations | IocUnknownConfigurationPreflight, IocEnvironmentPropertyMatcher, shared inspector, property shape | Пользовательские неперечислимые источники требуют review полноты; library scope отдельно |
| Plan/registry admission и disabled intake | ConfigRegistryPreflight, ProcessingPlanCatalog, ArtifactPolicyCatalog, import compiler | Выключенный intake не отключает проверку объявленных plans/catalogs |
| Effective loading и context boundary | IocSemanticConfigurationCheck, ConfigPreflightConfiguration, IocConfigurationContextIT | Минимальный граф не ограничивает imports и не проверяет durable runtime state |
| Failure data, paths, names и console boundary | IocConfigurationCheckFailure, YAML/unknown-name analyzers, semantic/syntax tests | Нет универсальной гарантии отсутствия секретов во всём процессе |
| Staging/apply/health/file recovery | packaging/templates/ioc-config, service, deploy-local-root.sh; packaging contracts | Shell contracts и synthetic invocation не доказывают реальный privileged systemd deployment |
| Lazy/eager и early CLI | IocExtractorApplication, EarlyCliLauncher, DaemonWebEnvironmentPostProcessor | Help/version/HTTP health не квалифицируют настройки или рабочий граф |
| Export execution и filesystem/state checks | ExportPlanCatalog, AppConfig, owning stores/recovery | Не включены целиком в semantic-only context; успешный check имеет ограниченный scope |

## Проверки

| Проверка | Результат |
|---|---|
| `make doctor-core` | JDK 21 и базовая среда доступны |
| 10 focused Surefire suites конфигурационной границы | 93 теста, 0 failures/errors/skips |
| IocConfigurationContextIT, IocPropertiesBindingIT, ConfigRegistryPreflightIT через выбранный reactor verify | 11 + 73 + 16 = 100 тестов, 0 failures/errors/skips; не полный make verify |
| ConfigurationDocumentationContractTest после итоговых правок документов | 7 тестов, 0 failures/errors/skips; повтор ранее включённого suite, не ещё 7 уникальных случаев |
| 12 отдельных JVM-проверок команды | Валидный daemon; binding/semantic/unknown failures; отсутствующий import; YAML-only; неполный list; настоящий multiword environment; CLI выше env; source guard; runtime-only export boundary; JSON выше JVM — PASS. Рабочие DB не создавались, sentinel values не попадали в вывод |
| Defaults | 106 конкретных scalar defaults и 16 container paths из classpath YAML сопоставлены с EN-таблицами; defaults/required/optional для декларативных элементов проверены по typed models и owning catalogs |
| Буквальный bash-пример | bash -n и исполнение с synthetic sudo/editor/helper: copy/edit/check/apply передают ожидаемые файлы и аргументы; реальный sudo/systemd и активация не выполнялись |
| `make lint-shell` | ShellCheck, packaging contracts и tools contracts — PASS |
| EN/RU | Три пары: совпадают уровни headings, inline identifiers, нормализованные targets и literal blocks; выполнен смысловой review |
| Inventory / protected inputs | 347 Markdown paths, 64 source sets; current/base hashes и существование source/test paths проверены; исходный denominator 329 сохранён |
| `make docs`, whitespace | 2343 occurrences, 618 unique, 2123 OK, 0 errors, 220 excluded; git diff --check и whitespace новых файлов — PASS |

Команды focused cohort и логи сохранены локально в `.dev/r030-doc-02a`.
Для unit cohort выбран Maven selector списка, поскольку make test-one принимает
один точный selector. Для Failsafe использованы skip.unit.tests и три явных
it.test с upstream modules. Результаты других suites и полного release gate
из этих запусков не выводятся.

## Состояние и ограничения

После пакета отсутствуют 67 обязательных RU-пар: 3 root docs, 12 capability docs,
49 ADR и 3 guides. `DOC-02A` закрывает только выбранный комплект; общие DOC-02/04/05,
R030-DOC, R030-SEC и R030-REL остаются открытыми. SECURITY-ENGINEERING и
THREAT-MODEL по решению пользователя остаются для отдельного комплекта.

Production/test Java, POM, scripts/workflows, analyzer/coverage policies, ADR,
generated catalogs и release records не менялись. Полные `make verify` и
`make pmd-analysis` не перезапускались: последнее полное evidence относится к
`191e74146dddeeab36bf59d9d09020a92bef0620`, freshness=false. Выбранный Failsafe
verify не записывает свежий результат полного gate. Новый live stand,
публикация, deployment и общая совместимость библиотек не квалифицировались.
