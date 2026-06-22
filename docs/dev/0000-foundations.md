# 0000 — Фундаментальные решения

- **Статус:** принято (зафиксировано задним числом)
- **Дата:** 2026-06-21 *(решения приняты на старте проекта)*
- **Связано:** [../architecture.md](../architecture.md), [../principles.md](../principles.md)

## Контекст

Стартовые решения по стилю, стеку и первому набору артефактов, принятые до
появления dev-журнала. Зафиксированы здесь для полноты истории.

## Решения

| # | Вопрос | Выбор | Обоснование | Отклонено |
|---|---|---|---|---|
| 1 | Архитектурный стиль | **Clean Hexagonal (порты и адаптеры) + Onion** | Изоляция домена, заменяемость инфраструктуры, тестируемость | Слоистый монолит; framework-centric |
| 2 | Язык/тулчейн | **Java 21**, сборка **Maven** | Industry standard, records/sealed/switch-patterns; Maven — ubiquitous | Gradle (на старте не нужен) |
| 3 | Runtime/DI | **Spring Boot** (CLI, без web) | Богатый конфиг (`@ConfigurationProperties`), DI, fat-jar; домен держим Spring-free | Micronaut; Guice; ручная сборка |
| 4 | Движок regex | **RE2/J по умолчанию + JDK fallback** за портом `PatternEngine` | Линейное время/ReDoS-safe на мусорном вводе; JDK как запасной | Только JDK (backtracking-риск) |
| 5 | Чтение источника | **Apache Tika** за портом `SourceReader` | Формат-агностично (.htm/.docx/.pdf/…), автоопределение | jsoup (только HTML) |
| 6 | Выходные артефакты | **Раздельные CSV** по назначению: изначально сетевые маски + файловые хэши, затем `ip_list` и `address_blacklist` | Разные схемы/id/нормализация; маршрутизация по `IndicatorType` и artifact filters | Один общий артефакт |
| 7 | CSV | **Apache Commons CSV**; диалект `;`+кавычки+`NULL` | Стандарт; `QuoteMode.ALL_NON_NULL`+`nullString` точно даёт нужный формат | Ручной парсер; univocity |
| 8 | CLI | **picocli** (+ spring-boot-starter) | Декларативные команды, интеграция со Spring `IFactory` | Свой парсер аргументов |
| 9 | STIX/OpenIOC как модель | **Нет** (своя лёгкая доменная модель; словарь типов выровнен под STIX) | Наши артефакты — CSV; STIX — будущий экспорт-адаптер | STIX 2.1 как внутренняя модель |

## Следствия

- Фреймворки (Spring/Tika/RE2J/commons-csv/picocli/Guava) — только в `adapter`/
  `bootstrap`; `domain`/`application` — без них ([boundaries.md](../boundaries.md)).
- Эталоны/историю развития последующих решений см. в dev-документах 0001–0005.

## Открытые вопросы

Нет; последующие уточнения вынесены в профильные dev-документы.
