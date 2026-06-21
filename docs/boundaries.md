# Защита архитектурных границ

Границы из [architecture.md](architecture.md) держатся не на доверии, а на
**автоматических проверках**. Цель — сделать нарушение правила (импорт фреймворка
в домен, обращение внутреннего слоя к внешнему, цикл зависимостей) failing-сборкой,
а не замечанием на ревью.

> Статус: **дорожная карта**. Прорабатывается на этапе архитектуры; внедряется
> вместе с многомодульностью ([modularization.md](modularization.md)).

## Уровни защиты (от мягкого к жёсткому)

### 1. Тесты архитектуры — ArchUnit
Декларативные правила на зависимости пакетов, выполняются как обычные тесты:

- `domain` не зависит на `application`, `adapter`, `bootstrap` и на фреймворки
  (`org.springframework..`, `org.apache.tika..`, `picocli..`, `com.google.re2j..`,
  `org.apache.commons..`).
- `application` зависит только на `domain` (+ свои порты), не на адаптеры.
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
- `dependencyConvergence` — единые версии транзитивных зависимостей.
- Запрет циклов на уровне реактора.

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

// 4) Порты — интерфейсы; driving в port.in, driven в port.out
classes().that().resideInAPackage("..application.port..").should().beInterfaces();

// 5) Без циклов между срезами
slices().matching("com.iocextractor.(*)..").should().beFreeOfCycles();
```

Правила оформляются как обычные тесты (`@AnalyzeClasses(packages = "com.iocextractor")`)
и **краснеют сборку** при нарушении — работают уже в одномодульном проекте.

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
<dependencyConvergence/>   <!-- единые версии транзитивов -->
<banCircularDependencies/>  <!-- циклы между модулями -->
```

## Порядок внедрения

1. Подключить ArchUnit и закрепить текущие правила слоёв (даёт защиту уже в
   одномодульном проекте).
2. Ввести parent-pom и enforcer (`banned-dependencies`, convergence).
3. По мере выноса модулей — переложить часть гарантий на границы реактора.
4. При необходимости — JPMS для жёсткой инкапсуляции.

## Definition of Done для границы

Граница считается защищённой, когда её нарушение приводит к **красной сборке**
(тест ArchUnit, ошибка компиляции модуля или enforcer), а не только к замечанию
ревьюера.
