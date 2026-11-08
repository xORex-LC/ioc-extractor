---
title: "R030-LIB — Shared libraries"
version: "0.3.0"
goal_id: "R030-LIB"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-LIB — Общие библиотеки и Maven publication

## Результат и состояние

`verified`: шесть принятых RC библиотек опубликованы и имеют component-scoped
qualification. Точные tags, signed bundle/recovery/repository identities и
ограничения сохранены в [release evidence](../../../../.github/release-evidence/0.3.0.md#dependency-and-publication-evidence)
и [component release records](../../../../.github/release-notes/README.md).
Глобальное завершение не закрывает модульные QUAL/ARCH/TEST/DOC cells.

| Artifact (`io.github.xorex-lc`, `0.3.0-rc.1`) | Record |
|---|---|
| `ioc-platform-concurrency` | [Concurrency](../../../../.github/release-notes/ioc-platform-concurrency-0.3.0-rc.1.md) |
| `ioc-platform-diagnostics-api` | [Diagnostics](../../../../.github/release-notes/ioc-platform-diagnostics-api-0.3.0-rc.1.md) |
| `ioc-platform-logging-slf4j` | [Logging](../../../../.github/release-notes/ioc-platform-logging-slf4j-0.3.0-rc.1.md) |
| `ioc-config-spring-boot` | [Configuration](../../../../.github/release-notes/ioc-config-spring-boot-0.3.0-rc.1.md) |
| `ioc-platform-events` | [Events core](../../../../.github/release-notes/ioc-platform-events-0.3.0-rc.1.md) |
| `ioc-events-spring` | [Spring adapter](../../../../.github/release-notes/ioc-events-spring-0.3.0-rc.1.md) |

Текущие admission/API/dependency/consumer правила принадлежат
[library development](../../../guides/libraries/development.md),
[publication](../../../guides/library-publication.md) и
[каталогу](../../../guides/libraries/README.md).

## Критерии сохранения результата

Каждая publication unit имеет owner, подтверждённых consumers, cohesive generic
API и dependency budget; generic code не зависит от service core. POM не требует
reactor parent/placeholders, sources/Javadoc разрешаются, project-owned closure
включена в план. Protected workflow публикует исходный проверенный signed bundle
без overwrite; изолированные consumers подтверждают каждый заявленный repository.
Исторические publication failures и последующие recoveries различаются явно.

## Оставшиеся границы и принятые отсрочки

- Configuration `TOOL-8`: corrected local Central consumer не заменяет отдельную
  protected CI requalification; исходный failed consumer остаётся в record.
- `TOOL-6/9`: ADR 0048 принял independent library versions и exact events family,
  но Maven/CI пока используют shared source version. Автоматизированное API
  comparison и активация новой versioning scheme остаются отдельными задачами в
  [KNOWN-ISSUES](../../../KNOWN-ISSUES.md).
- Второй развёрнутый service — отдельная qualification; публикация и consumer
  fixture не доказывают его operational acceptance.
- ETL остаётся внутренним. Diagnostics/logging bridges, error root, correlation,
  sanitization, integration contracts, RabbitMQ и TCK/build reuse не получают
  publication admission автоматически по сходству кода или расположению platform/.
- Events core остаётся тонким publish-only control contract; очереди, subscriber
  SPI, wire schema, broker ownership и durable delivery в него не добавляются.

Новая library требует отдельного admission с реальными semantics/consumers,
минимальным versionable API и dependency closure; catch-all commons и umbrella JAR
не входят в цель. `R030-LIB` использует BASE/BUILD/SEC и scoped QUAL/ARCH/TEST,
а финальный кандидат проходит `R030-REL` по standing release policy.
