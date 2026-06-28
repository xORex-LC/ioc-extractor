# Защита архитектурных границ

Границы из [architecture.md](architecture.md) держатся не на доверии, а на
**автоматических проверках**. Цель — сделать нарушение правила (импорт фреймворка
в домен, обращение внутреннего слоя к внешнему, цикл зависимостей) failing-сборкой,
а не замечанием на ревью.

> Статус: **реализовано**. Базовые границы защищены Maven reactor, Maven
> Enforcer и ArchUnit. Расширенные проверки вроде Spring Modulith/canvas
> отложены как технический долг.

## Уровни защиты (от мягкого к жёсткому)

### 1. Тесты архитектуры — ArchUnit
Декларативные правила на зависимости пакетов, выполняются как обычные тесты:

- `domain` не зависит на `application`, `adapter`, `bootstrap` и на фреймворки
  (`org.springframework..`, `org.apache.tika..`, `picocli..`, `com.google.re2j..`,
  `org.apache.commons..`).
- `platform-etl` не зависит на IOC domain/application/adapters/bootstrap и
  остаётся framework-free.
- `application` зависит на `domain` и platform contracts, не на адаптеры/Spring.
- JDBC/`java.sql`/Hikari разрешены только adapters/bootstrap; Jackson manifest
  mapping — только `adapter-manifest-json-jackson`/bootstrap. Streaming slice
  writer отдельно защищён от JDBC и materialized canonical repository model.
- Внутри `ioc-domain` capability-пакеты держат DAG (`model/refang/extract/
  feature/classify/attribute`) через module-local ArchUnit.
- Onion/hexagonal-правило слоёв: доступ только «внутрь».
- Отсутствие циклов между пакетами.
- Конвенции именования/размещения (порты в `port/**`, адаптеры в `adapter/**`).

### 2. Границы модулей — Maven-реактор
После нарезки на модули направление зависимостей фиксируется в `pom.xml`: класс
из недоступного модуля просто не на classpath. Это самая сильная и дешёвая
гарантия для правила «domain framework-free». См. [modularization.md](modularization.md).

### 3. maven-enforcer-plugin
- `banned-dependencies` — запрет нежелательных зависимостей в конкретных модулях
  (напр. Spring в `ioc-domain`).
- `dependencyConvergence` — отложено до отдельного build-hygiene шага.
- Циклы между Maven-модулями невозможны при успешной reactor-сборке.

### 4. JPMS `module-info.java` (опционально)
Сильная инкапсуляция: модуль экспортирует только порты и публичный API, прячет
внутренние пакеты адаптеров. Рассматриваем после стабилизации структуры.

### 5. Видимость и конвенции кода
- Package-private по умолчанию; публичное — осознанно (порты, value objects).
- Реализации адаптеров не «торчат» наружу мимо порта.
- Внедрение через конструктор; никакого доступа к composition root из ядра.

## Конкретные правила ArchUnit

```java
// 1) Слои: зависимости только внутрь
layeredArchitecture().consideringOnlyDependenciesInLayers()
    .layer("domain").definedBy("..domain..")
    .layer("application").definedBy("..application..")
    .layer("adapter").definedBy("..adapter..")
    .layer("bootstrap").definedBy("..bootstrap..")
    .whereLayer("bootstrap").mayNotBeAccessedByAnyLayer()
    .whereLayer("adapter").mayOnlyBeAccessedByLayers("bootstrap")
    .whereLayer("application").mayOnlyBeAccessedByLayers("adapter", "bootstrap")
    .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "adapter", "bootstrap");

// 2) Домен — без фреймворков и внешних библиотек
noClasses().that().resideInAPackage("..domain..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework..", "org.apache.tika..", "com.google.re2j..",
        "org.apache.commons..", "picocli..", "com.google.common..");

// 3) Application — только на domain (+ свои порты), не на адаптеры/Spring
noClasses().that().resideInAPackage("..application..")
    .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "org.springframework..");

// 3b) Storage/serialization mechanisms do not leak inward
noClasses().that().resideOutsideOfPackages("..adapter..", "..bootstrap..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "java.sql..", "javax.sql..", "org.springframework.jdbc..", "com.zaxxer.hikari..");
noClasses().that().resideOutsideOfPackages(
        "..adapter.out.manifest.json..", "..adapter.in.cli..", "..bootstrap..")
    .should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson..");

// 3a) Generic ETL kernel — без IOC/application/adapters/frameworks
noClasses().that().resideInAPackage("..platform.etl..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "..domain..", "..application..", "..adapter..", "..bootstrap..",
        "org.springframework..", "org.slf4j..", "ch.qos.logback..");

// 4) Порты — интерфейсы; driving в port.in, driven в port.out
classes().that().resideInAPackage("..application.port..").should().beInterfaces();

// 5) Без циклов между срезами
slices().matching("com.iocextractor.(*)..").should().beFreeOfCycles();

// 6) Ingest boundary: ingest concerns do not leak into domain or generic
//    extraction pipeline. Source metadata reaches the pipeline only through
//    explicit application contracts.
noClasses().that().resideInAPackage("..domain..")
    .should().dependOnClassesThat().resideInAnyPackage("..ingest..");
noClasses().that().resideInAPackage("..application.pipeline..")
    .should().dependOnClassesThat().resideInAnyPackage("..ingest..");
```

Правила оформляются как обычные тесты (`@AnalyzeClasses(packages = "com.iocextractor")`)
и **краснеют сборку** при нарушении. Часть правил живёт module-local, часть
остаётся в bootstrap/root architecture tests для cross-module/package checks.

## Конкретные правила Maven Enforcer

```xml
<!-- в pom модуля ioc-domain: запрет фреймворков/тяжёлых либ -->
<bannedDependencies>
  <excludes>
    <exclude>org.springframework*:*</exclude>
    <exclude>org.apache.tika:*</exclude>
    <exclude>info.picocli:*</exclude>
    <exclude>org.apache.commons:commons-csv</exclude>
  </excludes>
</bannedDependencies>
```

Сейчас реализованы `requireJavaVersion`, `requireMavenVersion`,
`banDuplicatePomDependencyVersions` и domain-local `bannedDependencies`.
`dependencyConvergence` оставлен как явный долг.

## Порядок внедрения

1. ArchUnit подключён и закрепляет layer/package rules.
2. Parent reactor и Enforcer введены на этапе 9.
3. Часть гарантий переложена на Maven module dependencies.
4. Отложено: `dependencyConvergence`, Spring Modulith/canvas, JPMS.

## Definition of Done для границы

Граница считается защищённой, когда её нарушение приводит к **красной сборке**
(тест ArchUnit, ошибка компиляции модуля или enforcer), а не только к замечанию
ревьюера.
