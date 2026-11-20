# ioc-platform-logging-slf4j 0.3.0-rc.1

[English version](../ioc-platform-logging-slf4j-0.3.0-rc.1.md) ·
[Руководство](../../../docs/guides/ru/libraries/logging-slf4j.md) ·
[Boot ECS-рецепт](../../../docs/guides/ru/libraries/logging-slf4j-boot.md)

Выпуск опубликован в Central и GitHub Packages 2026-10-21. Для каждого
репозитория прошли отдельные cold plain Java и Boot consumers. Публикация
использовала исходные подписанные файлы и Central deployment без пересборки
и повторной подписи. Это подтверждает Java/SLF4J API и описанный синхронный
Boot-рецепт, а не второй развёрнутый сервис или другой backend/provider.

## Неизменяемая идентичность

| Поле | Значение |
|---|---|
| Coordinate | `io.github.xorex-lc:ioc-platform-logging-slf4j:0.3.0-rc.1` |
| Исходный коммит | `5b9761b7ab0b3d6033e51a82fb735f1a3049ef08` |
| Annotated tag | `ioc-platform-logging-slf4j-v0.3.0-rc.1` |
| Tag object | `0ed9d086e20a23a2f96f6aaf1b7930ed09121bb7` |
| Ветка выпуска | `release-0.3.0` |
| Проверенный PR | [17](https://github.com/xORex-LC/ioc-extractor/pull/17), head `716049cf`; дерево merge-коммита совпадает |
| Merge CI | [39432555971](https://github.com/xORex-LC/ioc-extractor/actions/runs/39432555971) |
| Валидация | [39433697620](https://github.com/xORex-LC/ioc-extractor/actions/runs/39433697620) |
| Публикация | [39455105300](https://github.com/xORex-LC/ioc-extractor/actions/runs/39455105300) |
| Central deployment | `c03543e6-6ce0-426a-905b-b9e18197f4e0` |
| Отпечаток подписи | `F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9` |
| SHA-256 подписанного `identity.json` | `36d37dac0a91454c7e306003d433aa8da912040166ebd99b22780b2876020ca0` |

Владелец подтвердил оба protected-environment review. Валидация прошла чистую
RC-сборку, строгий Javadoc, 83 publication-контракта, отдельные plain/Boot
consumers, PMD и подпись. Публикация пропустила сборку, продолжила исходную
валидацию/deployment и отправила те же байты в GitHub Packages. Сопоставлены
все 20 подписанных/checksum-файлов; четыре дополнительных анонимных скачивания
из Central совпали с основными артефактами.

| Основной файл | SHA-256 |
|---|---|
| POM | `8913a98ed2a639df62e62e152b5ae8d4c1d6bc2b04ed8cfd7e7698b5a8621d64` |
| Main JAR | `e11f4b6e14018317f9ce5866c531042a6e7d0ab7fd9c2b1c600d9098536f8a53` |
| Sources JAR | `89de9ff4ad6f525546a6813a765c5b6026bd5f76ce0e31d7382eded324213f6b` |
| Javadoc JAR | `61a90ecb428c4ef1c42b12b91ced58a350d11bffe69283bde1c34cd7c1a9119b` |

## Проверка репозиториев и сохранённые материалы

Каждый consumer использовал отдельный временный проект и пустые Maven
cache/settings. Plain consumer выполнил все три класса из руководства и
проверил ожидаемые отказы без SLF4J API и с недоступной его версией.
Зависимости classifiers и провайдера не могли скрыть отсутствие main SLF4J API.
Boot consumer скомпилировал точные Java/YAML/XML и нейтральный driver,
проверил пять JSON-записей с обнаружением повторных ключей и encoder status.
IOC runtime-классов не было. Каждый fixture проверил четыре основных хеша
и Maven origin; public dependency fallback не мог подтвердить GitHub resolution.
Реальные consumer-логи подтверждают успех; все 20 файлов выпуска побайтно
совпали с валидацией.

Исходные файлы, workflow inputs/logs, проверки исходников и оба consumer-лога
сохранены в `.dev/l3-5-publication/`. Архив восстановления `signed-release.tar.gz`
имеет SHA-256 `f3016d5faa8fba0f02e0e48cb5f31650fb5ea846bcddf2ce2b50b1770fac4039`.
Actions artifacts `signed-library` `12281355410`, `central-deployment`
`12280977687` и `repository-consumers` `12279819488` истекают
`2027-01-18T16:10:38Z`; локальные оригиналы сохраняют дольше.
При восстановлении нельзя пересобирать/переподписывать эту версию или двигать тег.

На исходном tagged source локальные `make verify` и `make pmd-analysis` прошли
`2026-10-20T13:49:05Z` и `2026-10-20T13:49:37Z`. Разобранные замечания:
137 принятых raw SpotBugs/0 видимых, CPD 22, PMD 19 advisory; изменения
идентичностей, состава групп и сообщений отсутствовали. Resource watchlist
проверен на идентичном pre-merge дереве L3-4. Это исторические измерения,
а не результаты последующих проверок документации или свежий security scan.

## Результаты выделения и владение

| Этап | Сохранённый результат |
|---|---|
| L3-0 | Восемь публичных типов, идентичность скалярных полей, пять ECS-дескрипторов, outcome/duration, маскирование и MDC одного потока; массивы и глобальная регистрация исключены |
| L3-1 | SLF4J-only библиотека, строгие проверки, сохранение типа после null, возвращаемые провайдером builders, восстановление MDC после конфликтов и исполняемые примеры |
| L3-2 | Каталоги IOC и observer adapters остались локальными; исправления производителей закрыли OBS-9/OBS-10 с JSON-регрессиями |
| L3-3 | Принят [ADR 0045](../../../docs/ADR/0045-reusable-slf4j-logging-contract.md), определено владение backend и добавлен Boot-рецепт приложения |
| L3-4 | Самостоятельный POM, точная SLF4J-зависимость, отдельные plain/Boot consumers, отрицательные dependency/routing проверки и допуск к protected-публикации |

ECS-анализ выявил неверное представление `event.type` и конфликт явного
`error.type` с Throwable, при котором appender мог отбросить запись. Миграция
IOC перенесла внутренние типы в поля сервиса, убрала неприменимые классификации
и outcomes и отдала объект ошибки encoder исключения. Это исторические
исправленные дефекты. L3-1 также исправил воспроизведённое переполнение стека
regex на длинных ключах через possessive repetition и сохранил descriptor-регрессию. Текущее подключение описано в
[observability, EN](../../../docs/dev/observability.md). Строгие ошибки logging
по-прежнему выходят наружу; отдельная изоляция OBS-6 остаётся в
[KNOWN-ISSUES](../../../docs/KNOWN-ISSUES.md).

Исторический L3-0 probe выполнил 62 случая на заменённых копиях старого кода
в `662aa880587e11cea08204c250d89894abf1834c`, а не на опубликованном API.
Часть случаев утверждала исходные дефекты; их успех не означал исправления.
Семь файлов остаются в Git history и перед удалением скопированы в
`.dev/logging-docs/retired-l3-0-probe/`. SHA-256 `baseline.json`:
`15b83441bec29b7b1eb5533e153eea034422833228735e016df3c190aaaf6954`.
Probe не запускался заново как текущее доказательство. Поддерживаемое поведение
принадлежит обычным library tests и IOC/Boot-регрессиям; перенос всех 62 случаев
один к одному не заявляется.

Версии продукта/библиотек остаются общими. Библиотека не содержит provider,
encoder, starter, каталог IOC, diagnostics bridge или автоматический перенос
контекста. Рецепт поддерживает только синхронные изменяемые события.
Массивы type/category, второй развёрнутый сервис, collector/Elasticsearch,
нагрузка, другие providers и асинхронный encoder этой проверкой не покрыты.
