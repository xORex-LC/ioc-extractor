# com.iocextractor.bootstrap

## Назначение

Composition root и конфигурация. Единственное место, где фреймворк (Spring)
связывает агностичное ядро с конкретными адаптерами и где известна форма внешней
конфигурации.

**Правило слоя:** зависит на всё (собирает граф). Ни один внутренний слой не
зависит на `bootstrap`. Смена реализации порта — изменение только здесь.

## Структура

| Файл | Назначение |
|---|---|
| `IocProperties.java` | Типобезопасная привязка дерева `ioc.*` (`@ConfigurationProperties`) |
| `AppConfig.java` | Сборка beans: движок, рефанг, экстрактор, политика, sinks, storage и use cases |
| `*HealthIndicator.java` | Actuator health contributors для ledger, artifact storage и JDBC storage |

## Заметки

Артефакты собираются из конфигурации (`buildSinks`): маппер + id-стратегия +
диалект CSV. В daemon mode здесь же связываются ingest flow, JDBC storage,
projection и health contributors. Доменные объекты остаются framework-free —
Spring живёт здесь.
