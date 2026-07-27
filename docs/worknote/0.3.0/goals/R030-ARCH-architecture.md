---
title: "R030-ARCH — Architecture"
version: "0.3.0"
goal_id: "R030-ARCH"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-ARCH — Module boundaries и package organization

## Outcome

Существующие Maven-границы подтверждены либо точечно изменены с evidence.
Внутри каждого production module код организован по cohesive responsibilities,
а принятые dependency boundaries автоматически enforced.

Execution mode: **global rules + per-module package map**.

Авторитетные опубликованные документы:

- [ARCHITECTURE](../../../ARCHITECTURE.md);
- [MODULARIZATION](../../../MODULARIZATION.md);
- [BOUNDARIES](../../../BOUNDARIES.md);
- [CONVENTIONS](../../../CONVENTIONS.md).

## Maven baseline

Текущая карта принимается как исходно подходящая:

```text
platform/*                reusable technical subsystems
core/ioc-domain           pure IOC domain
core/ioc-application      use cases and ports
core/ioc-application-tck  reusable contract tests
adapters/*                one external integration family per module
bootstrap/ioc-app         composition root
```

0.3.0 не ставит целью заново разделить reactor. Новый Maven-модуль требует
самостоятельной границы:

- dependencies;
- ownership;
- external integration family;
- publication unit;
- independent lifecycle.

Package cleanup сам по себе не доказывает необходимость нового module.

## Dependency rules

- dependencies направлены inward;
- `ioc-domain` не зависит от platform/application/adapters/bootstrap;
- application зависит от domain и platform contracts, но не adapters;
- platform не зависит от service-specific core;
- adapter изолирует одну external integration family;
- bootstrap владеет composition/lifecycle, но не business rules;
- package/module cycles запрещены;
- visibility минимальна;
- internal packages не используются соседней capability;
- enforceable boundary получает ArchUnit/Enforcer rule.

```text
ioc-app ─▶ adapters ─▶ ioc-application ─▶ ioc-domain
   │           │               └────────▶ platform/*
   │           └────────────────────────▶ platform/*
   └────────────────────────────────────▶ platform/*
```

Future services зависят от published platform libraries, но не от core другого
сервиса.

## Внутримодульная package organization

Основной structural scope — организация Java types внутри существующих
Maven-модулей.

Role-aware rules:

- domain/application — business capabilities и use cases;
- platform — generic technical subsystem;
- adapters — integration family и direction;
- bootstrap — runtime capability и composition/lifecycle concern.

Не копируется механически дерево `api/application/domain/infrastructure`,
поскольку layers уже выражены module graph.

Связанные configuration, listener, scheduler, health и observer types находятся
рядом с owning capability. Не создаются горизонтальные dumping grounds
`service`, `util`, `helper`, `misc` либо пакет на каждый одиночный type.

Known initial case: flat package `com.iocextractor.bootstrap` смешивает
configuration/preflight, storage, export, sync, coordination, observability,
health и lifecycle concerns. Target map принимается после dependency/visibility
analysis.

## Package-review checklist

- capability находится по package tree без repository-wide search;
- root package не смешивает независимые concerns;
- package move не расширяет visibility без основания;
- package-private collaboration сохраняется;
- production и test packages перемещаются согласованно;
- FQCN в Spring metadata, Logback, reflection и serialization проверены;
- существенный package имеет `package-info.java` или актуальную README-ссылку;
- large composition classes проверяются на cohesion, а не только переносятся.

## Procedure

1. Зафиксировать current module/package map.
2. Определить ownership, public/internal surface и dependency direction.
3. Найти flat/god packages, misplaced types и cycles.
4. Предложить target package map с rationale.
5. Добавить characterization/architecture tests.
6. Удалить proven dead code через `R030-RETIRE` до лишнего relocation.
7. Выполнять package moves behavior-preserving slices.
8. Разделять package move, API rename и algorithm change.
9. Обновить ArchUnit, README и architecture docs.
10. Сохранить evidence и matrix state.

## Non-goals

- repository-wide rename;
- одинаковая package tree для всех modules;
- JPMS ради modularity;
- преждевременное service extraction;
- module extraction только из-за размера package.

## Definition of Ready

- current/target package maps известны;
- проблема cohesion/ownership/navigation доказана;
- visibility, reflection/framework и API impact определены;
- related production/tests перечислены;
- behavior-preservation verification запланирована.

Изменение Maven boundary оформляется отдельным work item.

## Definition of Done

- каждый module имеет понятную responsibility и reviewable package map;
- согласованные flat/god packages декомпозированы;
- production/tests согласованы;
- cycles устранены либо имеют accepted disposition;
- new visibility обоснована;
- boundaries enforced;
- docs актуальны;
- supported behavior не изменён без отдельного decision.

## Dependencies

Требует `R030-BASE`; тесно связан с `R030-QUAL`, `R030-RETIRE`, `R030-TEST`,
`R030-LIB` и `R030-DOC`.
