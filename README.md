# ioc-extractor

Mini ETL for **Indicators of Compromise (IOC)**: parse a messy source document,
**refang** defanged IOCs (`hxxp[:]//`, `[.]`, `[:]`), detect / normalize /
classify them, attribute a `source` from section headers, de-duplicate, and emit
reputation-list CSV artifacts (network masks + file hashes).

Built on **Clean Hexagonal (ports & adapters) + Onion** in Java 21 / Spring Boot.

## Quick start

```bash
mvn -q -DskipTests package
java -jar target/ioc-extractor-0.1.0-SNAPSHOT.jar extract --source source/ioc-source.htm
```

- Конфигурация: `src/main/resources/application.yml` (переопределение через `./configs/application.yml`).
- Запуск: `ioc extract --source <file> [--dry-run]`.

## Документация

Принципы, архитектура и конвенции — в [docs/](docs/):

- [docs/architecture.md](docs/architecture.md) — Clean Hexagonal + Onion, слои, правило зависимостей;
- [docs/principles.md](docs/principles.md) — инженерные принципы;
- [docs/modularization.md](docs/modularization.md) — дорожная карта многомодульности;
- [docs/cross-cutting.md](docs/cross-cutting.md) — сквозные подсистемы за портами;
- [docs/boundaries.md](docs/boundaries.md) — защита архитектурных границ;
- [docs/conventions.md](docs/conventions.md) — Javadoc и конвенции (в т.ч. README в каждом каталоге).

## Stack

Spring Boot · picocli · Apache Tika · RE2/J (regex, JDK fallback) ·
Apache Commons CSV · JUnit 5 / AssertJ.
