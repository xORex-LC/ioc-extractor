---
title: "DOC-02J — Библиотеки координации и событий"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation slice"
document_type: "Execution evidence"
source_of_truth: false
language: "ru"
---

# DOC-02J — Библиотеки координации и событий

Проверка 2026-11-07 (Asia/Irkutsk), HEAD
`37eb6a0714f5e08ecad067403280ce2eff2934f4`, ветка `docs/quality`.
Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).

Комплект — три EN/RU-пары библиотечных guides:
[concurrency](../../../guides/libraries/concurrency.md),
[events](../../../guides/libraries/events.md),
[events-spring](../../../guides/libraries/events-spring.md).
Проверены корректность, полнота и перевод; уточнения относятся к использованию
библиотек вне IOC. Существующие Java/XML examples сохранены побайтово.
Применены code-documenter, java-code-review, spring-boot-engineer,
maven-dependency-audit и multi-module-maven.

На входе готовые незакоммиченные DOC-02H/I. Сохранены hashes 369 Markdown paths
и исходные inventory/source-set rows. Их содержательные файлы, как и A–G,
сохранены; общие registry/status дополнены без сброса чужих состояний.
Java/tests/POM/scripts, quality scope, generated catalogs, принятые ADR,
release records и отложенные root security docs не менялись. Полный аудит
связанных module README не входит в этот срез.

## Находки и решения

| ID | Приоритет / наблюдение | Результат |
|---|---|---|
| `DOC-CL01` | M: исключение одного ключа можно ошибочно распространить на общие данные разных ключей или на guard и executor | В concurrency явно описаны границы одного координатора и необходимость защиты общих изменяемых данных |
| `DOC-CL02` | M: очередь не фиксирует значения объектов, захваченных задачей, и не переносит контекст вызывающего потока | Добавлены владение `Runnable`, безопасные immutable inputs/durable references и отсутствие автоматического переноса thread-local/MDC/tracing |
| `DOC-CL03` | M: приём задачи можно принять за дескриптор её результата | `WorkAdmission` отделён от completion/cancellation; ожидание поздней задачи внутри её же очереди блокирует её запуск |
| `DOC-CL04` | M: record и immutable recorder snapshot не обеспечивают глубокую неизменяемость payload | Events guide объясняет копирование изменяемых входов при создании события и service-owned защиту совместно используемых данных |
| `DOC-CL05` | M: metadata `eventType` можно принять за Spring subscription/routing key | Уточнены Java-type selection, `PayloadApplicationEvent` для обычного объекта и получение payload через типизированный `@EventListener` |
| `DOC-CL06` | M: logging provider сам по себе не делает ordinary publication failure видимой | Явно сказано: с `NoopControlEventObserver` обычная ошибка delegate обычно не бросается и не вызывает adapter WARN; fallback WARN обслуживает сбой observer |
| `DOC-CL07` | Проверка: реакторная политика версий, опубликованный POM и реальный consumer graph имеют разные роли | JDK-only core/concurrency, exact-version events family, Spring/SLF4J, default/BOM graphs и негативные dependency/routing cases подтверждены; версия/политика не менялись |
| `DOC-CL08` | Проверка: совпадение fences не подтверждает исполнение, полноту prose или опубликованные bytes | Примеры выполнены на исходных JAR; 23 Java sources совпали, API surface и EN/RU prose проверены отдельно; release identities сохранены |

## Источники и покрытие контракта

| Guide | Живые источники / проверенная поверхность |
|---|---|
| concurrency | [Пакет и Javadoc](../../../../platform/platform-concurrency/src/main/java/com/iocextractor/platform/concurrent/), [тесты](../../../../platform/platform-concurrency/src/test/java/com/iocextractor/platform/concurrent/): все 12 public types, ключи, guard/reentrancy/order, queue admission, worker ownership, dispatch/task/observer failures, snapshots и shutdown |
| events | [Пакет и Javadoc](../../../../platform/platform-events/src/main/java/com/iocextractor/platform/events/), [тесты](../../../../platform/platform-events/src/test/java/com/iocextractor/platform/events/): все 7 public types, metadata validation/identity, publisher/observer semantics, no-ops, shallow recorder и concurrent snapshots |
| events-spring | [Adapter/Javadoc](../../../../adapters/adapter-events-spring/src/main/java/com/iocextractor/adapter/out/events/spring/), [тесты](../../../../adapters/adapter-events-spring/src/test/java/com/iocextractor/adapter/out/events/spring/): единственный public type, constructor/publish, исходные failure identities, suppression, wrapped Error, partial delivery, service configuration и telemetry boundary |

Сверены parent и три module POM, published flattened POM, фактические dependency
graphs, [publication tooling](../../../../tools/ci/library-publication.py),
[JDK concurrency consumer](../../../../tools/fixtures/library-consumer/README.md),
[events consumer](../../../../tools/fixtures/events-consumer/README.md),
[Spring consumer](../../../../tools/fixtures/events-spring-consumer/README.md).
IOC composition/observer сверены как один потребитель библиотек; описание
durable backstops остаётся в [event coordination](../../../dev/event-coordination.md).
Это coverage выбранных guides, а не метрика Javadoc/coverage всего репозитория.

## Исполненные проверки

- **85 unit/contract cases, 10 suites:** concurrency 23, events 43, Spring adapter
  16, IOC composition/observer 2 и DocumentationConventionTest 1. Нет failures,
  errors или skips. Счётчики взяты только из suite names фактического Maven log;
  старые reports других suites не включены. Выбранные XML сохранены до IT.
- **4 IT:** `SpringControlEventPublisherIT` на реальном Spring context:
  partial delivery, annotated wrapped Error, service-owned error handler и
  отсутствие listeners/автоматически добавленных beans. Нет failures/errors/skips.
- **228 offline publication contracts:** `make library-publication-test` прошёл.
  Сообщения о публикации/деплое внутри synthetic fixtures не обозначают внешних
  publish actions этого аудита.
- **6 дополнительных boundary cases на original published JAR:** guard/executor
  с одинаковым ключом независимы; разные ключи могут перекрываться; queued
  capture остаётся live и обычный ThreadLocal не переносится; record/recorder
  не замораживают payload; typed listeners и raw wrapper сохраняют исходную
  ссылку и выбираются по Java type; Noop observer не вызывает fallback WARN,
  а failing observer вызывает. Все прошли в отдельном JVM без IOC runtime.
  Concurrency cases имеют timed latches, JUnit `@Timeout`, `finally` cleanup и
  проверку ограниченного завершения pool; процесс также имеет общий timeout.
- **Три Central consumers:** три независимых проекта с тремя изначально пустыми
  private Maven caches. Оба concurrency Java examples, JDK event example и
  Spring example извлечены из guides и выполнились. По компонентам подтверждены
  4 / 4 / 8 primary artifact hashes и выбранные repository origins. Повторно
  проверенный core в Spring consumer не является четвёртым уникальным bundle.
- **Spring default и Boot 4.0.8 BOM:** обе dependency closures и plugin/direct-JVM
  исполнения прошли; второй graph использует private cache того же Spring
  проекта. Excluded/unavailable core/Context/SLF4J, incompatible Spring и missing
  selected adapter/core отвергнуты; unfiltered controls разрешили ожидаемые
  альтернативные origins. JDK-only libraries не получили production dependencies.
- **EN/RU:** проверены смысл, границы, headings, literal blocks, inline-code
  counts и normalized targets. Все executable blocks шести guides совпали с HEAD.
  Inventory/current/base hashes, protected input files, links и diff whitespace
  проверены отдельно от поведения examples: сохранён 361 входной Markdown path,
  все non-J source-set rows неизменны, проверены 12 local Markdown fragments.
  `make docs`: 2910 total / 704 unique / 2665 OK / 0 errors / 245 excluded.
  `git diff --check` прошёл.

## Published identity

| Компонент / permanent release record | Source commit | Signed manifest SHA-256 | Java files |
|---|---|---|---:|
| [concurrency 0.3.0-rc.1](../../../../.github/release-notes/ioc-platform-concurrency-0.3.0-rc.1.md) | `c0f0f12f74a01615605b11b74e7b523bbc116cf5` | `be2311c54f06420cf86a8e6943658214e240853620b78f184ca425a20cca371e` | 13 |
| [events 0.3.0-rc.1](../../../../.github/release-notes/ioc-platform-events-0.3.0-rc.1.md) | `46c914418e721322903a127584fd5d99dff9f52b` | `1445412b74d2d02533ce679970b977e16a64393f8ae64169adcf228a5b5ff575` | 8 |
| [events-spring 0.3.0-rc.1](../../../../.github/release-notes/ioc-events-spring-0.3.0-rc.1.md) | `46c914418e721322903a127584fd5d99dff9f52b` | `e358c7006a87ac98bcbd81b3bde1334c45a61e6bb75bc1969efe5dcf96caa800` | 2 |

Для consume использованы собственные копии identity.json/repository исходных
signed bundles. Все manifest entries проверены по SHA-256, четыре primary hashes
каждого bundle совпали с release record, production Java bytes — с sources JAR
(включая package-info). Исходные manifests, component tags и release records
не менялись. Новая cryptographic trust/signature qualification, сборка релиза,
подпись, публикация и protected CI recovery не выполнялись.

## Ограниченный аудит зависимостей

Default/BOM consumer graphs дают **12 различных внешних package/version pairs**:
пять Spring modules 7.0.9, два Micrometer modules 1.16.7, SLF4J API 2.0.18,
Commons Logging 1.3.5/1.3.6 и JSpecify 1.0.0/1.0.1. Это полный union именно
этих runtime closures; build/test plugins и весь IOC reactor сюда не включены.

2026-11-07 в 10:03:57 UTC запрос по
[OSV API](https://google.github.io/osv.dev/api/) не нашёл advisory matches для
этих 12 package/version pairs. Положительный контроль log4j-core 2.14.1 вернул
advisories. Raw request/response и точные версии сохранены в локальном evidence.
Это датированный package/version screen, не доказательство отсутствия
уязвимостей, reachability analysis или общий release security gate.

Central metadata также проверены read-only. Среди числовых release versions
на момент проверки есть Spring 7.0.10, SLF4J 2.0.20, Micrometer 1.17.2,
Commons Logging 1.4.0 и JSpecify 1.0.1. Поля latest/release ряда artifacts
указывали на RC/alpha; они не использованы как рекомендация обновления.
Квалифицированный consumer baseline остаётся прежним. Апгрейд требует отдельного
compatibility/release среза и не меняет immutable POM уже выпущенного RC.

## Учёт и свежесть

Текущий census — **370 Markdown paths / 64 source sets**, отсутствуют прежние
53 RU-пары: 3 root, 49 ADR и 1 guide. В этом комплекте существовали все три
перевода; добавлен только audit record. Исходный census 329 на
`2c76d8b095a2162c7038c64de896b244517cf4c7` и base hashes сохранены.
Изменены только три owning source sets; H/I и остальные registry states
сохранены. Shared inventory/README/status отражают завершённый DOC-02J.

Локальный ignored evidence — `.dev/r030-doc-02j/`: input hashes/rows,
unit/integration XML и logs, publication-contracts, published-identity.json,
три consumer logs, boundary fixture/results, dependency-review и raw metadata,
parity/anchor/inventory/link checks. Новых commit/push/deployment нет.

Full verify/PMD не запускались: прежние PASS относятся к
`191e74146dddeeab36bf59d9d09020a92bef0620`, freshness обоих false.
TOOL-6/7/8/9, OUT-4, TOOL-10 и OPS-10/11/12 не закрываются этим срезом.
Другой Spring/Boot baseline, второй deployed service, async all-handler
completion и внешний broker/wire format не квалифицированы.
DOC-02J закрывает выбранный документный комплект; R030-DOC/SEC/REL остаются
открытыми. Следующий комплект автоматически не начинается.
