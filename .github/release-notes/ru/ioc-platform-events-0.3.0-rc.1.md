# ioc-platform-events 0.3.0-rc.1

[English version](../ioc-platform-events-0.3.0-rc.1.md) ·
[Руководство](../../../docs/guides/ru/libraries/events.md) ·
[Парный артефакт](ioc-events-spring-0.3.0-rc.1.md)

Выпуск опубликован в Central и GitHub Packages 2026-10-25. Для каждого
репозитория прошли отдельные cold consumers. Это выпуск Maven-библиотеки,
а не релиз или deployment сервиса IOC. PR [19](https://github.com/xORex-LC/ioc-extractor/pull/19)
объединил E1–E5; оба тега указывают на одни проверенные исходники/дерево.

## Неизменяемая идентичность

| Поле | Значение |
|---|---|
| Coordinate | `io.github.xorex-lc:ioc-platform-events:0.3.0-rc.1` |
| Исходный коммит | `46c914418e721322903a127584fd5d99dff9f52b` |
| Дерево исходников | `0cd880399fd5dce1db6bdc92f40ebc5762c300c9` |
| Annotated tag | `ioc-platform-events-v0.3.0-rc.1` |
| Tag object | `2e5e604a261bf41bd4d574b595c94d10647d05f3` |
| Ветка выпуска | `release-0.3.0` |
| Валидация | [40159541648](https://github.com/xORex-LC/ioc-extractor/actions/runs/40159541648) |
| Публикация | [40160627532](https://github.com/xORex-LC/ioc-extractor/actions/runs/40160627532) |
| Central deployment | `61ceaa74-dc39-451e-a989-21c8c2181f99` |
| Отпечаток подписи | `F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9` |
| SHA-256 подписанного `identity.json` | `1445412b74d2d02533ce679970b977e16a64393f8ae64169adcf228a5b5ff575` |

Владелец подтвердил validation/publication review обоих компонентов.
Каждая валидация один раз подписала чистый комплект. Публикация пропустила
сборку/подпись и использовала исходные файлы и deployment. В каждом комплекте
20 подписанных/checksum-файлов. При восстановлении нельзя пересобирать,
переподписывать, перезаписывать эти coordinates или двигать теги.

| Основной файл | SHA-256 |
|---|---|
| POM | `08df0b9ba848f7e7b92383f3492e17ad70c25dd1791423f5c4eaf150c1ede578` |
| Main JAR | `4ee663ce8773ac90f195ab863ac4e5675f2349f57f6bc8f02fdda78d12db7d4e` |
| Sources JAR | `68c89bbff8533200387629bce120b67d548ccbd95f8b6ee54248f03a028d8360` |
| Javadoc JAR | `b2824ffc918992e3c70a1a72636fb2acc1fca9ff584670336cf438119fd3e374` |

## Consumers и результаты выделения

JDK consumer скомпилировал/выполнил исходный `LocalEventsExample` прямо из
руководства и независимо проверил метаданные, no-ops, повторные ссылки и
поверхностные снимки. Spring, SLF4J и IOC runtime-классы отсутствовали.
Четыре основных файла совпали с подписанным manifest и выбранным Maven origin.
Пустой selected repository с заполненным alternate не мог пройти проверку;
unfiltered positive controls подтверждали доступность правильных байтов.

| Этап | Сохранённый результат |
|---|---|
| E0 | Сверены producers, payload и пределы доставки; local hints отделены от durable recovery и межсервисных сообщений |
| E1 | Семь JDK-only типов, явные metadata/null/concurrency контракты, Javadoc, 43 focused cases и исполняемый пример другого сервиса |
| E2 | Spring-доставка вынесена в другой артефакт; конкретные события, listeners, изоляция dispatch и recovery остались в сервисе |
| E3 | Самостоятельный POM и строгие проверки dependency-free архива/embedded POM |
| E4 | Отдельный JDK consumer, полный graph, реальный пример, provenance четырёх файлов и missing-target controls |
| E5 | Protected-публикация сначала core и передача того же core той же версии Spring-адаптеру |

Текущее поведение описано в поддерживаемом руководстве и package Javadoc.
Нет subscriber SPI, очереди, wire-формата, подтверждений, durable ledger,
keyed executor, генерации IDs или автоматического переноса контекста.
Recorder — неограниченный copy-on-write fixture со ссылками, а не журнал.
[Запись Spring-адаптера](ioc-events-spring-0.3.0-rc.1.md) сохраняет исторические
исправления Spring-границ и проверку пары.

## Архивы, release gates и ограничения

Исходные файлы, workflow inputs/logs и проверенные receipts сохранены в
`.dev/events-release-0.3.0-rc.1/`. SHA-256 `core-release.tar.gz`:
`f8cc5924affb8f64cf74afaffd38b7dbfaa78c7eb79675ed96f06e91517d9bd2`. Actions retention истекает 2027-01-23;
локальные копии сохраняют неизменяемые байты и логи дольше.

Release CI `40157656528` прошёл на точном source `46c91441`, включая пять
обязательных gates и оба packaged consumers. Каждая protected RC-валидация
прошла полный `verify`, 215 publication-контрактов, выбранный consumer и PMD.
Сохранены 137 принятых raw SpotBugs/0 visible/errors/missing, CPD 22 и
PMD 19 advisory/0 blocking. Покрытие release CI: 27133/29440 lines,
10296/12009 branches, 25 production groups. Это исторические RC-измерения,
а не последующие проверки документации. Floors/suppressions и
analyzer/dependency scope ради публикации не ослаблялись.

Историческая проверка runtime advisory не нашла совпадений для 12 пар
package/version обычного и BOM-managed graphs; vulnerable/fixed controls и
исходные ответы сохранены в `library-runtime-advisories.json`.
У core нет сторонних runtime-зависимостей. Это не whole-service NVD clearance:
запуск `40157773054` отменён во время NVD update до выполнения scan.
Service jsoup/Jackson findings оставались отдельной работой. Эти исторические
запросы не устанавливают текущий vulnerability status.

Baseline адаптера: Java 21, Context 7.0.9 и SLF4J API 2.0.18,
обычный и Boot 4.0.8 BOM-managed graphs. Версии продукта/библиотек общие.
Второй развёрнутый сервис, другой framework baseline, асинхронное завершение
всех handlers и внешний broker не проверены. ETL остаётся внутренним.
Исходный Central CI-отказ configuration и protected recovery сохраняются
как отдельные доказательства TOOL-8; результат events этот пункт не закрывает.
