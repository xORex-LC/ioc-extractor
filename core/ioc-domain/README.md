# core/ioc-domain

## Назначение

Единый IOC bounded context: общий язык IOC and domain capabilities
`model/refang/extract/feature/classify/attribute`.

**Правило слоя:** domain framework-free, не знает про ETL `Envelope`, IO,
application ports, adapters, bootstrap or logging.

Потоковые контракты `MatchCursor`, `MarkerCursor`, `TextRewrite` и
`ExtractionClaims` сохраняют порядок refang, приоритет regex типов, абсолютные
UTF-16 offsets и NBSP marker precedence без зависимости от дискового backend.
Domain не открывает файлы/SQLite; их ownership остаётся в adapters.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor and domain dependency guard |
| `src/main/java/com/iocextractor/domain/` | Domain model and business rules |
| `feature/NetworkAddressParser.java`, `NetworkHostDeriver.java` | Единая форма сетевого адреса и вывод типизированного хоста без IO |
| `src/test/java/com/iocextractor/domain/` | Domain unit tests and capability DAG test |

## Зависимости

**Зависит от:** JDK; test scope uses ArchUnit.

**Не импортируется:** Spring, Tika, CSV, Guava, RE2J, SLF4J/Logback,
`platform-etl`, application, adapters, bootstrap.
