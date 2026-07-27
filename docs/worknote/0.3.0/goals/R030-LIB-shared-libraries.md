---
title: "R030-LIB — Shared libraries"
version: "0.3.0"
goal_id: "R030-LIB"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-LIB — Shared code и Maven publication

## Outcome

Shared code имеет явные scope, owner и consumers. Cross-service technical
capabilities доступны независимо собираемому сервису через Maven repository.
Как минимум одна reusable library реально опубликована и проверена standalone
consumer.

Execution mode: **candidate modules + global publication**.

Рабочий ledger:

- [shared-code inventory](../evidence/shared-code-inventory.md).

## Shared-code taxonomy

| Category | Назначение | Placement |
|---|---|---|
| Capability-local | Общая реализация внутри одной capability | Owning package |
| Service-shared | Service-specific contract для нескольких модулей сервиса | Самая внутренняя допустимая service boundary |
| Cross-service platform | Generic technical mechanism для независимых сервисов | Именованный `platform-*` artifact |
| Integration contract | Versioned business message/schema | Отдельный `*-contracts` artifact |
| Transport adapter | Реализация external integration family | `adapter-*` за port |
| Build/test reuse | Versions, policy, TCK, fixtures | Parent, BOM, TCK, `*-test-fixtures` |

Повторение строк не является основанием расширять scope кода.

## Admission criteria

Новый shared module требует:

1. category и cohesive responsibility;
2. owner;
3. два consumers либо существующий и подтверждённый ближайший consumer;
4. одинаковые semantics и reason to change;
5. отсутствие лучшего JDK/mature-library решения;
6. правильную inward dependency direction;
7. отсутствие service-specific configuration/internal types в generic API;
8. independent tests/evolution;
9. малый dependency budget;
10. минимальный versionable public API;
11. понятное поведение при будущем расхождении consumers.

Cross-service platform library не зависит от service core/application/
bootstrap/adapters. Integration contract не зависит от broker implementation.
Reusable transport adapter не владеет business schemas.

Admission record фиксирует:

- category, responsibility и owner;
- consumers;
- public API и excluded concepts;
- dependency/transitive closure;
- versioning/compatibility;
- tests и publication evidence.

## Naming и shape

Artifact именуется по capability:

- `platform-concurrency`;
- `platform-diagnostics-api`;
- `adapter-messaging-rabbitmq`;
- `feeds-ingestion-contracts`.

Catch-all artifact IDs `commons`, `common`, `shared`, `core-utils`, `misc`
запрещены без отдельного architecture decision.

Не создаётся umbrella `commons.jar`. `platform/` является source organization,
не dependency. BOM согласует версии independently consumed libraries.

`*-api`/`*-impl` разделяются только ради dependency isolation, нескольких
реализаций или отдельного stable consumer contract.

## Project candidates

Potential candidates:

- framework-free diagnostics model/result/sink;
- generic structured logging/MDC/value typing;
- diagnostics-to-logging bridge после decoupling;
- correlation/trace identifiers;
- sanitization;
- keyed concurrency;
- ETL primitives при подтверждённом втором consumer;
- messaging ports/envelope;
- RabbitMQ transport adapter;
- service-owned integration contracts;
- TCK/test fixtures;
- build configuration.

Перед publication текущие platform modules проходят genericity review.

Особое внимание:

- `platform-diagnostics` — IOC codes и exception roots;
- `platform-observability` — `ioc.*` fields и IOC actions;
- `platform-diagnostics-logging` — coupling обоих контуров;
- `platform-etl` — diagnostics/service assumptions;
- `platform-errors` и `com.iocextractor.common` — `IocExtractorException`
  считается service-specific до доказательства общей semantics.

## Event и messaging boundaries

- local control event — in-process latency hint;
- domain event — факт bounded context;
- integration message — versioned wire contract;
- broker adapter — RabbitMQ implementation;
- delivery pattern — outbox/inbox, idempotency, retry, dead letter.

`platform-events` остаётся framework-free publish-only local contract. Он не
получает queue, subscriber SPI, serialization, acknowledgement или durable
delivery.

Business schemas принадлежат integration-contract artifact, не messaging API и
не RabbitMQ adapter.

## Published library contract

Published library:

- создаёт JAR с stable coordinates;
- имеет defined public API;
- публикуется protected CI;
- имеет consumer-resolvable POM без reactor-local placeholders;
- публикует sources и Javadoc;
- включает project-owned dependency closure в publication plan;
- имеет compatibility и external-consumer tests.

Пример:

```xml
<dependency>
    <groupId>com.iocextractor</groupId>
    <artifactId>ioc-platform-example</artifactId>
    <version>0.3.0</version>
</dependency>
```

Local `mvn install` не является publication evidence.

Release workflow:

- строит artifact один раз;
- публикует проверенные bytes без rebuild;
- отделяет snapshot и release repositories;
- запрещает overwrite одинаковой release version;
- использует protected credentials/trigger;
- сохраняет checksums/identity.

0.3.0 MAY использовать lockstep product version. Independent versioning и BOM
вводятся при подтверждённом lifecycle/consumer need.

## Procedure

1. Классифицировать current/proposed shared code.
2. Создать admission records.
3. Выбрать publication unit и dependency closure.
4. Отделить generic mechanics от service contracts.
5. Принять ADR о repository, coordinates, ownership и compatibility.
6. Настроить flattening, sources и Javadoc.
7. Публиковать snapshots из CI.
8. Выполнить protected release publication.
9. Разрешить artifacts standalone consumer.
10. Обновить release process и notes.

## Definition of Ready

- category, owner, consumers и semantics определены;
- dependency budget/closure известен;
- public API предложен;
- service-specific concepts исключены;
- repository, credentials и version policy определены;
- standalone verification спроектирована.

## Definition of Done

- inventory/admission records завершены;
- catch-all artifact отсутствует;
- generic API не зависит от service core;
- минимум одна publication unit опубликована protected CI;
- sources/Javadoc/POM разрешаются;
- standalone consumer проходит contract test;
- artifact identity и no-overwrite подтверждены;
- docs/ADR/release process обновлены.

## Dependencies

Требует `R030-BASE`, hardening candidate modules по
`R030-QUAL`/`R030-ARCH`/`R030-TEST`, publication controls из
`R030-BUILD`/`R030-SEC` и завершает часть `R030-REL`.
