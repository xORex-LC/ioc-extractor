# Проверка конфигурации с библиотекой Spring Boot config

[English version](../../libraries/config-spring-boot.md) · [Каталог библиотек](README.md)

`ioc-config-spring-boot` сопоставляет объявленные имена свойств с record-моделью
сервиса. Она возвращает известные, неизвестные, неоднозначные и неподдерживаемые
имена, покрытие источников и схемы, а также наблюдения об устаревших и удалённых
именах. Например, коллектор может обнаружить `collector.request-timout`
и подсказать замену старого параметра конкретного подключения.

Библиотека читает имена, а не значения. Boot связывает и преобразует значения;
сервис проверяет их смысл и решает, отключает ли проблема одно подключение,
коллектор или приложение целиком. Загрузка файлов, приоритет источников,
импорты, профили, логирование и обновление конфигурации принадлежат сервису.
JAR не предоставляет starter, auto-configuration, runner контекста или YAML loader.

## Подключение и границы проверки

Используйте Java 21. Проверенные версии — Spring Boot 4.0.8 и Spring Core 7.0.9:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-config-spring-boot</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

Самостоятельный POM потребителя объявляет обязательные compile-зависимости
Boot и Core. Boot транзитивно подключает Spring Context и его зависимости.
IOC, diagnostics и logging provider не требуются. Управляйте зависимостями
в приложении и отдельно проверяйте смену базовых Boot/Spring. Отдельный
потребитель проверяет обычное разрешение зависимостей и Boot 4.0.8 BOM приложения.

Выпуск опубликован в Central и GitHub Packages. Стандартный Maven Central
не требует дополнительного репозитория. GitHub требует авторизованных settings;
см. [процедуру публикации, EN](../../library-publication.md#consumption).
Java-реализация, сверенная для этого руководства, совпадает с тегом
`ioc-config-spring-boot-v0.3.0-rc.1`; реактор остаётся на `0.3.0-SNAPSHOT`.

В квалификации есть сохранённое исключение: исходный GitHub consumer прошёл,
а исходный Central CI consumer завершился ошибкой, скрытой shell pipeline.
Исправленные локальные Central-проверки прошли на исходных подписанных байтах.
Исправления маршрутизации и pipefail включены через PR 19; защищённый повторный
CI-запуск остаётся открытым. [Постоянная запись выпуска](../../../../.github/release-notes/ru/ioc-config-spring-boot-0.3.0-rc.1.md)
сохраняет исходный отказ и идентичность восстановления. Эта работа над
документацией не закрывает отдельный CI follow-up.

## Публичные инструменты и типы результатов

Шесть основных публичных типов находятся в `com.iocextractor.config.boot`.

| Тип | Назначение |
|---|---|
| `PropertySchema` | Выбор одного канонического сегмента префикса и обычного record-корня |
| `PropertyInspector` | `inspect(schema, sources)` обходит подготовленные источники один раз, не читая значения |
| `PropertyInspection` | Неизменяемые объявления, покрытие источников и пробелы схемы; `complete()` описывает полноту проверки |
| `PropertyMigration` | Старый путь, вид DEPRECATED/REMOVED и необязательная замена |
| `MigrationInspector` | `inspect(inspection, migrations)` получает наблюдения миграции из того же снимка без повторного чтения источников |
| `MigrationInspection` | Исходный снимок, совпавшие объявления и исходное покрытие |

Одиннадцать вложенных публичных типов определяют данные для политики сервиса:

| Вложенный тип | Содержимое |
|---|---|
| `PropertyInspection.Status` | KNOWN, UNKNOWN, AMBIGUOUS, UNSUPPORTED |
| `PropertyInspection.SourceKind` | PROPERTIES, ENVIRONMENT, COMPOSITE, AGGREGATE, OTHER |
| `PropertyInspection.Coverage` | ENUMERATED, EXPANDED, EXCLUDED, INCOMPLETE |
| `PropertyInspection.SourceReference` | Неотрицательный порядковый номер обхода и фиксированный вид источника |
| `PropertyInspection.Declaration` | Исходное имя, упорядоченные канонические кандидаты, статус и безопасная ссылка на источник |
| `PropertyInspection.SourceCoverage` | Ссылка на источник и состояние покрытия |
| `PropertyInspection.SchemaGap` | Канонический путь и фиксированная причина неподдерживаемой формы |
| `PropertyMigration.Kind` | DEPRECATED или REMOVED; описательные метаданные сервиса |
| `MigrationInspection.Resolution` | RESOLVED, AMBIGUOUS или UNSUPPORTED |
| `MigrationInspection.Match` | Дескриптор, конкретный старый путь и необязательная замена с индексами |
| `MigrationInspection.Observation` | Исходное объявление, непустой список совпадений и результат разрешения |

## Проверка имён, миграция и binding значений

Поместите `ConfigurationInspectionExample.java` в `src/main/java/examples/configuration/`
Maven-проекта с зависимостью выше и `maven.compiler.release=21`. Пример проверяет
источник, который запрещает любое чтение значений, получает конкретную подсказку
миграции, затем связывает отдельно подготовленные значения через Boot.
Вторая проверка показывает неполное покрытие непрозрачного источника.

```java
package examples.configuration;

import com.iocextractor.config.boot.MigrationInspector;
import com.iocextractor.config.boot.PropertyInspection;
import com.iocextractor.config.boot.PropertyInspector;
import com.iocextractor.config.boot.PropertyMigration;
import com.iocextractor.config.boot.PropertySchema;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ConfigurationInspectionExample {
    public record Collector(Duration requestTimeout, List<Connection> connections) { }
    public record Connection(String name) { }

    public static void main(String[] args) {
        var schema = new PropertySchema("collector", Collector.class);
        var names = new EnumerablePropertySource<Object>("private-source-label", new Object()) {
            @Override public String[] getPropertyNames() {
                return new String[] {"collector.requestTimeout", "collector.request-timout",
                        "collector.connections[3].legacy-name", "another-service.secret"};
            }
            @Override public Object getProperty(String name) {
                throw new AssertionError("Name inspection must not read values");
            }
        };
        var inspection = PropertyInspector.inspect(schema, List.of(names));
        long unknown = inspection.declarations().stream()
                .filter(d -> d.status() == PropertyInspection.Status.UNKNOWN).count();
        var migrations = MigrationInspector.inspect(inspection, List.of(new PropertyMigration(
                "collector.connections[].legacy-name", PropertyMigration.Kind.REMOVED,
                Optional.of("collector.connections[].name"))));
        var observation = migrations.observations().getFirst();
        String replacement = observation.matches().getFirst().replacement().orElseThrow();

        var values = new MapPropertySource("explicit-candidate", Map.of(
                "collector.request-timeout", "5s", "collector.connections[0].name", "primary"));
        Collector bound = new Binder(ConfigurationPropertySources.from(values))
                .bind("collector", Bindable.of(Collector.class)).get();
        var opaque = new PropertySource<Object>("opaque-source", new Object()) {
            @Override public Object getProperty(String name) { return null; }
        };
        boolean opaqueComplete = PropertyInspector.inspect(schema, List.of(opaque)).complete();
        if (!inspection.complete() || inspection.declarations().size() != 3 || unknown != 2
                || observation.declaration().status() != PropertyInspection.Status.UNKNOWN
                || !replacement.equals("collector.connections[3].name")
                || !bound.requestTimeout().equals(Duration.ofSeconds(5)) || opaqueComplete
                || inspection.toString().contains("private-source-label")) {
            throw new IllegalStateException("Inspection, migration or binding differs");
        }
        System.out.printf("Names: %d; unknown: %d; replacement: %s%n",
                inspection.declarations().size(), unknown, replacement);
        System.out.println("Bound timeout: " + bound.requestTimeout()
                + "; opaque source complete: " + opaqueComplete);
    }
}
```

Ожидаемый вывод:

```text
Names: 3; unknown: 2; replacement: collector.connections[3].name
Bound timeout: PT5S; opaque source complete: false
```

Неизвестная опечатка не делает `complete()` ложным: все относящиеся к схеме
имена и пути удалось проверить. Сервис всё равно должен отреагировать на неё.
И наоборот, непрозрачный источник мешает полному покрытию даже без неизвестных
объявлений в результате.

## Поддерживаемые схемы и имена

`PropertySchema` требует один канонический сегмент Boot namespace, например
`collector`, и record-корень. Неподдерживаемые вложенные формы становятся
пробелами схемы, даже если вход не содержит относящихся к ним объявлений.

| Проверенная форма | Правила |
|---|---|
| Обычные вложенные records | ASCII lower camel case без aliases и последовательных заглавных букв |
| Скалярные типы | Primitive/enum/String/number/Boolean/Character; обычные типы времени, путей, URI/URL, адресов, locale, charset, UUID, даты и Class, перечисленные в реализации схемы |
| `List<T>` | Разрешимый record/scalar-тип элемента; числовые индексы для списков records; поддерживается целое скалярное присваивание списку scalar |
| `Map<K,V>` | Плоские scalar-значения, String/enum-ключи и динамический ключ из одного токена |

JavaBeans, sets, arrays, рекурсивные и неразрешимые типы, custom conversions,
aliases `@Name`, неподдерживаемые имена полей и вложенные карты дают неполное
покрытие. Ключи карт с точками или специальными символами вне проверенной
грамматики. Record как scalar и целое scalar-присваивание списку records
не поддерживаются. Распознавание имени не проверяет enum-ключ карты,
преобразование, непрерывность индексов или возможность связать конкретное значение.

Распознаются канонический `collector.request-timeout`, camel-вариант
`collector.requestTimeout` и слитный `collector.requesttimeout`. Для корректно
названного environment-источника распознаются `COLLECTOR_REQUESTTIMEOUT`
и `COLLECTOR_REQUEST_TIMEOUT`. Если написание подходит нескольким путям,
сохраняются все кандидаты со статусом AMBIGUOUS; победитель не выбирается.

## Выбор источников до проверки

Передавайте стабильную последовательность `PropertySource<?>` от высшего
приоритета к низшему. Дочерние источники composite сохраняют порядок и получают
свои номера. Имена внутри перечислимого источника сортируются; другие namespace
не включаются. Нижние, перекрытые и null-valued объявления сохраняются.
Если проверка имён и binding должны описывать одного кандидата, не меняйте
источники между этими операциями.

| Источник | Поведение проверки |
|---|---|
| Обычный перечислимый источник | PROPERTIES / ENUMERATED |
| `SystemEnvironmentPropertySource` с именем `systemEnvironment` или суффиксом `-systemEnvironment` | Поддерживаемый ENVIRONMENT / ENUMERATED |
| Другое имя environment-источника | OTHER / INCOMPLETE; найденные объявления UNSUPPORTED |
| Composite | COMPOSITE / EXPANDED; дочерние источники обходятся отдельно |
| Присоединённый Boot aggregate `configurationProperties` | AGGREGATE / EXCLUDED во избежание дублирования |
| Неперечислимый источник | OTHER / INCOMPLETE |

Ошибки перечисления передаются с исходной идентичностью исключения. Это
операционные ошибки, а не неизвестные имена; частичный результат не возвращается.
Снимок не сохраняет объекты источников и их произвольные имена, но сами имена
свойств и ключи карт могут содержать секреты. Выбирайте правила отображения
имён, результатов и исключений в сервисе.

## Отдельная оценка находок и покрытия

| Находка | Значение | Действие сервиса |
|---|---|---|
| KNOWN | Один поддерживаемый канонический путь | Связать и проверить значение |
| UNKNOWN | Нет поддерживаемого совпадения или относящегося к имени неподдерживаемого поддерева | Сообщить об опечатке или удалённом ключе по своей политике |
| AMBIGUOUS | Подходят несколько канонических путей | Потребовать однозначное написание |
| UNSUPPORTED, пробел схемы или неполный источник | Полный ответ получить нельзя | Устранить пробел или явно принять частичную проверку |

`complete()` истинен только без пробелов схемы, INCOMPLETE-источников и
UNSUPPORTED-объявлений. UNKNOWN/AMBIGUOUS не уменьшают покрытие. Известное имя
может содержать неправильную duration, отсутствующее обязательное значение
или семантический конфликт. Полнота и разрешённая миграция не разрешают запуск.

Объявления не показывают окончательное происхождение значений Binder. Источник
с высшим приоритетом, задающий список, заменяет его целиком вместе с элементами.
Частичный override `collector.connections[0].name` может удалить другие поля
этого элемента и все нижние элементы. Агрегация карт следует другим правилам.
Конфликты проверяйте на связанной модели; объявления сохраняйте для отдельной
политики допустимости входных имён.

## Описание старых имён без переписывания конфигурации

Используйте канонические старые пути `collector.connections[].legacy-timeout`
и необязательную замену `collector.connections[].request-timeout`.
`PropertyMigration.Kind.DEPRECATED` описывает окно совместимости сервиса;
REMOVED допускает имена, отсутствующие в текущей record-модели. Это метаданные.
Библиотека не регистрирует Binder alias, преобразование или сроки поддержки.

`MigrationInspector.inspect` использует готовый снимок, сохраняет покрытие
и не читает источники повторно. Удалённое имя остаётся UNKNOWN в исходном
снимке, даже если его миграция RESOLVED. Конкретные индексы копируются по
порядку в placeholders замены. Подсказка не доказывает, что старое значение
подходит новому полю или что целевой путь существует в схеме.

Дескрипторы должны соответствовать namespace снимка. Шаблоны допускают
канонические record-пути и `[]` для числовых индексов списков, без конкретных
индексов, map-entry wildcards и regex callbacks. Замена должна менять путь
в том же namespace и сохранять число placeholders. Перекрывающиеся или
эквивалентные старые пути и цепочки миграций отклоняются до сопоставления.
Пустой снимок не устанавливает namespace: правильный каталог выбирает сервис.
Несколько интерпретаций сохраняют AMBIGUOUS вместо выбора наугад.

Одновременное наличие старого и нового объявления не доказывает конфликт
связанных значений: одно может быть перекрыто или относиться к заменённому
списку. Разрешённая миграция при частичной проверке не доказывает отсутствие
старых имён в непрозрачных источниках.

## Binding, владение контекстом и ошибки

После политики имён передайте значения Boot и выполните semantic validation
сервиса. Конструкторы records должны позволять собрать операторские ошибки.
Для одного явного YAML используйте [рецепт ограниченного контекста](config-spring-boot-check.md).
Для проверки эффективной конфигурации подготовьте настоящие импорты,
профили и overrides Boot. Ограничение beans само по себе не ограничивает входы.

У инспекторов нет изменяемого состояния вызова. Стабильностью источников владеет
вызывающий код; менять их во время обхода нельзя. Списки и множества кандидатов
копируются; records результата — наблюдения, а не сертификат для созданных
вручную данных. Инспекторы не создают и не закрывают контексты, файлы, потоки
или транзакции. Рецепт закрывает короткоживущий контекст и возвращает отдельную
неизменяемую модель.

- Null вместо обязательных аргументов, метаданных и списков вызывает
  `NullPointerException`.
- Неправильный namespace, корень, шаблон или каталог миграций вызывает
  `IllegalArgumentException`; неподдерживаемые вложенные формы возвращают пробелы покрытия.
- Отрицательные номера источников и наблюдения миграции без совпадений отклоняются.
- Ошибки перечисления, Binder и семантики требуют отдельных правил сервиса.
  Произвольный текст parser/converter exception не является безопасным отчётом.

## Источники, тесты и поддержка

- [Справочник модуля](../../../../adapters/adapter-config-spring-boot/README.md)
  и [исходники/Javadoc пакета](../../../../adapters/adapter-config-spring-boot/src/main/java/com/iocextractor/config/boot/)
  определяют точные контракты. Внутренние `SchemaModel`, `NameMatcher`
  и `MigrationPath` остаются деталями реализации.
- [Тесты модуля](../../../../adapters/adapter-config-spring-boot/src/test/java/com/iocextractor/config/boot/)
  проверяют имена, схемы, неоднозначность, снимки, миграции, операционные
  ошибки, реальные преобразования Binder и замену списков. Команда:
  `make test-module MODULE=adapters/adapter-config-spring-boot`.
- [Отдельный потребитель](../../../../tools/fixtures/configuration-consumer/README.md)
  компилирует этот Java-пример и рецепт ограниченного контекста при обычном
  разрешении и BOM приложения, с отрицательными проверками зависимостей
  и маршрутов. Совпадение Java/XML/YAML в EN/RU проверяют
  [publication-контракты](../../../../tools/tests/library-publication-test.py):
  `make library-publication-test`. Ссылки проверяет `make docs`.
- [ADR 0046](../../../ADR/0046-reusable-spring-boot-configuration-inspection.md)
  определяет принадлежность механизмов; [проверка конфигурации](../../../dev/configuration-inspection.md)
  объясняет интеграцию IOC. [TOOL-8](../../../KNOWN-ISSUES.md)
  сохраняет открытое защищённое CI-восстановление Central consumer.

Продукт и библиотека используют общую линейку версий. Другой развёрнутый сервис,
другая версия Boot и общий механизм live reload остаются вне квалификации.
При изменении поведения обновляйте оба языка, примеры и рецепт.
