> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0048-independent-library-versions-and-reuse.md).

<a id="adr-0048-independent-library-versions-and-reuse-before-duplication"></a>
# ADR 0048: Независимые версии библиотек и переиспользование до дублирования

Статус: принято, 2026-11-01. Реализация независимых версий ещё не выполнена.

<a id="context"></a>
## Контекст

У шести reusable artifacts теперь есть опубликованные релизы и парные
английские/русские guides. Они остаются в IOC monorepo, а source POMs всё ещё
наследуют продуктовую `${revision}`. Publication admission также требует,
чтобы component version соответствовала выбранной product release branch.

Общая версия была сознательным упрощением первой публикации в
[ADR 0029](../0029-release-branch-library-publication.md). Будущие сервисы
должны иметь возможность принимать library fixes без следования ритму релизов
IOC. Общему коду также нужно явное правило разработки: использовать существующий
подходящий API и развивать владеющую им библиотеку, когда не хватает реальной
общей возможности. Публикация не замораживает разработку, а локальная копия
создала бы ещё одну сопровождаемую реализацию.

<a id="decision"></a>
## Решение

<a id="release-ownership"></a>
### Владение релизами

Принять независимые release versions библиотек. Это заменяет product/library
lockstep policy ADR 0029 и соответствующие version clauses в
[ADR 0044](../0044-reusable-diagnostics-contract.md),
[ADR 0045](../0045-reusable-slf4j-logging-contract.md),
[ADR 0046](../0046-reusable-spring-boot-configuration-inspection.md) и
[ADR 0047](../0047-explicit-spring-control-event-adapter.md), при условии
прохождения implementation gate ниже. Их API и ownership decisions не меняются.

Concurrency, diagnostics, logging и configuration имеют собственные release
versions. Events core и его Spring adapter первоначально образуют одно release
family: оба используют одну version, и adapter требует ровно эту core version.
Они сохраняют отдельные artifacts, component tags, signed bundles и repository
qualification. Core публикуется и квалифицируется до adapter. Выпуск любого
участника продвигает family version и выпускает обоих, включая неизменённый
core, когда меняется только реализация adapter.

Application IOC и internal modules сохраняют product version. Product release
не требует новой версии неизменённой библиотеки. Каждый consuming service
закрепляет выбранную released library version и владеет временем обновления.
Библиотеки остаются в этом repository; отдельные repositories, branches на
библиотеку и public BOM этим решением не требуются.

Сопровождаемая [release policy](../../RELEASE-PROCESS.md#reusable-library-versions)
владеет version increments, compatibility, immutable releases и activation
criteria. Существующие coordinates, tags и signed bundles `0.3.0-rc.1`
остаются неизменяемыми.

<a id="development-and-reuse"></a>
### Разработка и переиспользование

До введения механизма, покрываемого
[каталогом библиотек](../../guides/libraries/README.md), изучить соответствующие
guide, module reference, Javadoc, implementation, tests и существующие места
вызова. Использовать существующий API, если он удовлетворяет требованию.
В противном случае объяснить недостающее поведение и выбрать владельца
по ответственности и dependency direction.

Сфокусированный generic fix или extension принадлежит владеющей библиотеке
с contract review, regression tests и documentation. Второй deployed consumer
не является условием исправления или расширения уже допущенной библиотеки.
Service catalogs, schemas, business rules, wiring и recovery policy остаются
в сервисах. Не расширять API для гипотетических использований и не протаскивать
framework dependency в framework-free слой только ради reuse.

Изменения библиотеки и feature могут разрабатываться вместе в одной branch
или PR с development versions. Отдельный library PR полезен, когда контракту
нужно независимое review, но не обязателен для каждого изменения. Опубликовать
и квалифицировать требуемый library release до выпуска consuming service;
разработка consumer может продолжаться до publication. Одни изменения кода
не разрешают signing, pushing tags, публикацию artifacts или выпуск сервиса.

<a id="implementation-gate"></a>
### Gate реализации

Решение утверждает policy, а не завершённую миграцию Maven или CI. Текущие
reactor и publication admission операционно остаются lockstep до выполнения
activation criteria release policy. В частности, существующий `-Drevision`
override не может независимо версионировать одну библиотеку; произвольную
component version нельзя принудительно провести через текущую release-branch check.

Реализация должна отделить library versions от product version в POMs,
dependency management, publication identity и consumer fixtures. Она также
должна доказать, что service release использует выбранное published library
content, в том числе когда reactor resolution иначе выбрал бы local source.
При изменении branch/version relationship сохранить component source/tag
provenance и recovery checks. Оставшаяся реализация отслеживается как
[TOOL-9](../../KNOWN-ISSUES.md).

<a id="alternatives-and-consequences"></a>
## Альтернативы и последствия

- Постоянный product/library lockstep проще, но связывает не связанные
  расписания релизов сервисов и библиотек.
- Вынос каждой библиотеки в другой repository дал бы физическое разделение,
  но добавляет coordination overhead без необходимости для independent versions.
- Выпуск библиотеки до любой consumer development изолировал бы изменения,
  но задержал integration feedback, необходимую для проверки API.
- Независимые версии двух events artifacts потребовали бы отдельной
  compatibility matrix; exact-version family сохраняет текущее dependency rule.

Independent versions добавляют работу выбора version и release evidence.
Reuse уменьшает дублирование механизмов, но не оправдывает перенос service
policy в библиотеку. Source/binary API comparison остаётся открытым TOOL-6;
до принятия автоматизации contract review и behavioral tests должны честно
указывать своё покрытие.

<a id="validation"></a>
## Проверка

[Руководство разработки](../../guides/libraries/development.md) и его
[русский вариант](../../guides/ru/libraries/development.md) описывают один
workflow. Release policy различает принятый target и текущие POM/workflow
behavior. Это изменение документации не пересоздаёт существующих publications.
Техническая активация требует зафиксированной TOOL-9 evidence.
