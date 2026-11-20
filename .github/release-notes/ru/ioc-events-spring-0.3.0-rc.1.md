# ioc-events-spring 0.3.0-rc.1

[English version](../ioc-events-spring-0.3.0-rc.1.md) ·
[Руководство](../../../docs/guides/ru/libraries/events-spring.md) ·
[Парный артефакт](ioc-platform-events-0.3.0-rc.1.md)

Выпуск опубликован в Central и GitHub Packages 2026-10-25. Для каждого
репозитория прошли отдельные cold consumers. Это выпуск Maven-библиотеки,
а не релиз или deployment сервиса IOC. PR [19](https://github.com/xORex-LC/ioc-extractor/pull/19)
объединил E1–E5; оба тега указывают на одни проверенные исходники/дерево.

## Неизменяемая идентичность

| Поле | Значение |
|---|---|
| Coordinate | `io.github.xorex-lc:ioc-events-spring:0.3.0-rc.1` |
| Исходный коммит | `46c914418e721322903a127584fd5d99dff9f52b` |
| Дерево исходников | `0cd880399fd5dce1db6bdc92f40ebc5762c300c9` |
| Annotated tag | `ioc-events-spring-v0.3.0-rc.1` |
| Tag object | `554343b6b72602c742c407e2e09fa7a47c72073c` |
| Ветка выпуска | `release-0.3.0` |
| Валидация | [40161887317](https://github.com/xORex-LC/ioc-extractor/actions/runs/40161887317) |
| Публикация | [40162675262](https://github.com/xORex-LC/ioc-extractor/actions/runs/40162675262) |
| Central deployment | `aceef9e3-888d-44aa-9bc6-45a1f57130ad` |
| Отпечаток подписи | `F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9` |
| SHA-256 подписанного `identity.json` | `e358c7006a87ac98bcbd81b3bde1334c45a61e6bb75bc1969efe5dcf96caa800` |

Владелец подтвердил validation/publication review обоих компонентов.
Каждая валидация один раз подписала чистый комплект. Публикация пропустила
сборку/подпись и использовала исходные файлы и deployment. В каждом комплекте
20 подписанных/checksum-файлов. При восстановлении нельзя пересобирать,
переподписывать, перезаписывать эти coordinates или двигать теги.

| Основной файл | SHA-256 |
|---|---|
| POM | `183eb1c85bb20c8858665357e05e72664e65af7bb2820a36b9193252dea1776a` |
| Main JAR | `2ba1695c8ea007ef793339ca30fbba1b0781192ceff7f2f962f5fd0ac3177bbd` |
| Sources JAR | `58ff3ca379b6969e8c7ea6cdee7e797b0e72e1650741d62ce3c54d7809d69c50` |
| Javadoc JAR | `cc01ecaf05db5545d8bb618eb251ac173ed4b4de6b40a0439b5b9410f320ded9` |

## Consumers и сохранённые доказательства

Валидация адаптера допустила успешную публикацию core `40160627532`.
Публикация адаптера восстановила исходные `events-core` и qualification,
не принимая замену core-run input. Сохранённый core manifest:
`1445412b74d2d02533ce679970b977e16a64393f8ae64169adcf228a5b5ff575`.

Отдельные Central/GitHub consumers скомпилировали оба исходных примера и
выполнили real-context проверки при обычном разрешении зависимостей и
Boot 4.0.8 BOM приложения. Восемь основных файлов сверены с обоими manifests
и selected origins; Boot runtime и logging provider не подключались.
Отрицательные случаи: исключённые/недоступные core, Context и SLF4J,
несовместимый Spring, отсутствие adapter и core. Заполненные alternate
repositories не исправляли filtered probes; оба unfiltered eight-file controls
подтвердили доступность нужных байтов.

Проверены фактические логи и qualification receipts, а не только зелёные
Actions conclusions. Receipts связывают source/version, signed manifest и
хеши consumer-логов; adapter receipt дополнительно связывает исходный core.
Локальный review helper сначала ожидал два success markers, хотя fixture
выполняется четыре раза для двух graphs и plugin/direct-JVM путей.
Утверждение аудита исправили, реальные логи/хеши проверили; workflow/artifacts
не перезапускались и не заменялись ради этой ручной проверки.

E4 также сохранил одиночный отказ создания временного GPG-ключа fixture.
Причина остаётся открытой как TOOL-7 в [KNOWN-ISSUES](../../../docs/KNOWN-ISSUES.md).
Сохранение stderr улучшили, automatic retry не добавляли. Последующий
успешный прогон контрактов не закрывает этот отдельный долг.

## Исторические исправления и владение

| Этап / проблема | Итог |
|---|---|
| E0 / ARCH-4 | Воспроизведён Spring wrapper вокруг Error annotated listener; E2 пробрасывает непосредственный исходный Error без смены идентичности |
| E0 / OBS-7 | Воспроизведена потеря исходной ошибки при сбое observer; E2 сохраняет publication failure основной и добавляет отличающуюся observer failure в suppressed, если разрешено |
| E0 / OBS-8 | Воспроизведён выход/reclassification fallback logger failure; E2 отделяет success observation и сдерживает runtime-сбой последнего logger без рекурсии |
| E2 | Явный адаптер и принятый [ADR 0047](../../../docs/ADR/0047-explicit-spring-control-event-adapter.md); без установки beans/executors/error handlers |
| E3 | Самостоятельный POM с core ровно той же версии, Context 7.0.9 и SLF4J API 2.0.18 |
| E4 | Отдельные consumers пары, независимые проверки, оба graphs, dependency/routing negatives и точный provenance восьми файлов |
| E5 | Успешный qualified core обязателен для protected-выпуска адаптера; восстановление использует исходные неизменяемые пару/deployment |

ARCH-4/OBS-7/OBS-8 — исправленные исторические дефекты. Стандартный синхронный
multicast всё ещё может остановиться после частичной доставки. Error handler
сервиса и async hand-off могут скрыть ошибку от внешнего вызова.
Прямые Errors из observation/logging выходят наружу; общего nonthrowing
контракта или retry нет. Payload, listeners, безопасный текст исключений,
потокобезопасность delegate, shutdown и durable recovery принадлежат сервису.

## Архивы, release gates и ограничения

Исходные файлы, workflow inputs/logs и проверенные receipts сохранены в
`.dev/events-release-0.3.0-rc.1/`. SHA-256 `spring-release.tar.gz`:
`eb7d65a7ec4cf94f4e21bf8ea00ff92f9194e9d6cfce66603e6cfa382139276c`. Actions retention истекает 2027-01-23;
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
