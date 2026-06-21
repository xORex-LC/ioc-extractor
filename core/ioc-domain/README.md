# core/ioc-domain

## Назначение

Единый IOC bounded context: общий язык IOC and domain capabilities
`model/refang/extract/feature/classify/attribute`.

**Правило слоя:** domain framework-free, не знает про ETL `Envelope`, IO,
application ports, adapters, bootstrap or logging.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor and domain dependency guard |
| `src/main/java/com/iocextractor/domain/` | Domain model and business rules |
| `src/test/java/com/iocextractor/domain/` | Domain unit tests and capability DAG test |

## Зависимости

**Зависит от:** JDK; test scope uses ArchUnit.

**Не импортируется:** Spring, Tika, CSV, Guava, RE2J, SLF4J/Logback,
`platform-etl`, application, adapters, bootstrap.
