# 0019 — Spring Boot 4 baseline и nested ECS wire format

## Статус

**Принято и реализовано 2026-07-21.** Для release `0.1.1` поддерживаемой
framework line является Spring Boot `4.0.x`; текущий candidate baseline —
`4.0.7`.

ADR частично supersede'ит [0018](0018-typed-ecs-structured-logging.md) только в
трёх местах:

- точная Spring Boot baseline меняется с `3.4.13` на `4.0.x`;
- physical ECS JSON representation становится nested, а не flat dotted-key;
- статический `event.dataset` добавляется узким bootstrap encoder adapter-ом,
  а не конфигурацией formatter-а.

Остальные решения ADR-0018 сохраняются: typed event-local fields, string-only
ambient MDC, `event.action`/`event.outcome`, collision semantics, redaction,
generated catalog и OBS-5 seam.

## Контекст

### Framework remediation

Dependency scan baseline выявил findings в старой Spring/Tomcat линии. Сначала
проект был переведён на последнюю `3.5.x` как рекомендованный migration bridge,
затем — на Boot 4. Такая последовательность позволила отделить изменения Spring
Framework 6.2/Boot 3.5 от модульных и API-изменений Boot 4.

Полный reactor `verify`, golden e2e и последующие CI-прогоны на `4.0.7` зелёные.
Возврат на `3.5.x` снова потребовал бы отдельного security disposition и полного
повтора release gates; поэтому он не является более консервативным изменением
в уже стабилизированном кандидате.

### ECS representation changed in Boot 3.5

Spring Boot 3.5 изменил built-in ECS rendering: dotted logical paths теперь
материализуются вложенными JSON objects. Логический контракт поля не изменился,
но изменился наблюдаемый wire format:

```json
{"ecs.version":"8.11","event.action":"app_start","event.outcome":"success"}
```

стал:

```json
{
  "ecs": {"version":"8.11"},
  "event": {"action":"app_start","outcome":"success"}
}
```

Следовательно, прежний consumer query `jq '.["event.action"]'` больше не
читает значение; его nested-эквивалент — `jq '.event.action'`.

`event.outcome` не является новым полем релиза `0.1.1`: оно существовало ранее.
Изменилась только его physical representation вместе с другими dotted paths.

### Static dataset collision

После перехода к nested representation независимое добавление
`event.dataset` поверх уже существующих `event.action`/`event.outcome` может
создать два top-level объекта `event`. Поэтому dataset должен попадать в тот же
context-pair stream до стандартной ECS-сериализации.

## Решения

### 1. Поддерживаемая линия — Spring Boot 4.0.x

Release `0.1.1` остаётся на `4.0.x`, с candidate baseline `4.0.7`. Версия в
parent POM является источником истины. До обновления Boot BOM допустимы только
явные parent-owned patch overrides для уже проверяемой линии зависимостей
(сейчас Tomcat, Jackson 2 и Log4j 2); их наличие и причина должны оставаться
видимыми в dependency management и security evidence.

Переход на `4.1.x` или другую feature line в ходе стабилизации запрещён. Он
требует отдельного upgrade-среза после релиза.

### 2. Boot 3.5 остаётся migration bridge, а не supported baseline

Версия `3.5.x` использовалась для поэтапной миграции, но не является второй
поддерживаемой runtime line. Проект проверяет и выпускает один framework
baseline.

Возврат к `3.5.x` допустим только как plan B при воспроизводимом блокере exact
candidate smoke на Boot 4. Такой rollback требует повторного dependency scan,
полного `verify`, packaging contract и стендового smoke; прошлые evidence на
Boot 4 к нему неприменимы.

### 3. Публичный ECS wire format — nested JSON

Имена в Java-каталоге и ECS vocabulary остаются логическими dotted paths
(`event.action`, `ecs.version`, `ioc.run.id`). Физическое представление daemon
JSON — вложенные objects (`event.action`, `ecs.version`, `ioc.run.id` читаются
как `.event.action`, `.ecs.version`, `.ioc.run.id`).

Это observable compatibility break для jq-запросов, ingest pipelines и
дашбордов, даже если логическое имя и JSON scalar type поля не изменились.
Release notes обязаны показать before/after payload и миграцию consumer query.

### 4. Encoder остаётся узким bootstrap adapter-ом

`IocEcsStructuredLogEncoder` только добавляет статический `event.dataset` в тот
же key/value stream и делегирует итоговую сериализацию стандартному Spring Boot
ECS formatter-у. Он не становится общей JSON-библиотекой, не владеет ECS schema
и не проникает в domain/application.

Regression contract обязан проверять:

- один nested объект `event` без duplicate keys;
- nested `ecs`, `service`, `event` и `ioc` paths;
- сохранение number/boolean JSON scalar types;
- наличие `event.action`, `event.outcome` и `event.dataset` в одном объекте.

### 5. SemVer disposition для 0.1.1

Nested representation и typed scalar migration являются публичным wire-break.
Для текущего pre-`1.0.0` релиза сохраняется версия `0.1.1`, потому что проект не
объявлял поддерживаемого внешнего ECS consumer-а и не имеет production
Elasticsearch integration. Исключение должно быть явно раскрыто в release
notes.

Если до тега будет обнаружен поддерживаемый consumer, решение пересматривается:
нужна либо совместимость/переходный период, либо релиз `0.2.0`.

### 6. Exact-candidate evidence нельзя наследовать от миграционных коммитов

Перед тегом проверяется именно собранный candidate с финальными version/config/
packaging inputs. Минимальный стендовый контракт:

1. clean `verify` и packaging contract;
2. запуск packaged daemon с production-like logging configuration;
3. валидность JSON и representative nested ECS assertions;
4. `/actuator/health`, `/actuator/info` и `ioc --version`;
5. graceful shutdown;
6. dependency security scan;
7. representative operator queries для новых nested paths.

Evidence фиксирует commit, version, artifact digest, effective config и дату.
Любое изменение этих входов после прогона инвалидирует стендовый результат.

## Следствия

- Проект имеет одну поддерживаемую Spring Boot line, без двойной матрицы 3.5/4.0.
- Потребители daemon ECS JSON должны мигрировать с bracket-access dotted keys на
  nested paths.
- ADR-0018 остаётся источником typed logging semantics; этот ADR владеет
  framework baseline и physical representation.
- Финальный стендовый smoke остаётся release gate, а не историческим аргументом
  в пользу выбранной версии.
- Кастомный encoder намеренно мал и должен быть удалён, если стандартный Boot
  configuration сможет безопасно выразить тот же dataset contract.

## Отклонённые варианты

### Вернуться на Boot 3.5 перед релизом только ради меньшего номера

Отклонено без фактического Boot 4 blocker-а: это новая миграция, возврат security
surface и полная инвалидизация уже полученных build/security evidence.

### Поддерживать одновременно Boot 3.5 и Boot 4

Отклонено: удваивает runtime/test matrix и не даёт ценности небольшому
приложению с одним deployable artifact.

### Восстановить flat dotted-key JSON собственным formatter-ом

Отклонено: проект стал бы владельцем полной ECS-сериализации ради сохранения
pre-release physical shape. Явная миграция consumers дешевле и прозрачнее.

## Связанные документы и источники

- [ADR-0018](0018-typed-ecs-structured-logging.md) — typed logging semantics;
- [observability.md](../dev/observability.md) — текущий runtime contract;
- [RELEASE-PROCESS.md](../RELEASE-PROCESS.md) — release evidence и notes;
- [Spring Boot 3.5 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.5-Release-Notes);
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide);
- [Spring Boot structured logging reference](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured).
