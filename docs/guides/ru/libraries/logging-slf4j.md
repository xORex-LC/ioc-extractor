# Типизированные логи, MDC scopes и ECS-поля

[English version](../../libraries/logging-slf4j.md) · [Каталог библиотек](README.md)

`ioc-platform-logging-slf4j` даёт типизированные скалярные поля, восстановление
MDC после операции и небольшой общий словарь ECS. Нужны Java 21 и SLF4J API.
Каждый сервис определяет свой каталог полей, имена операций, допустимость
раскрытия данных и logging provider.

## Подключение и границы проверки

Версия `0.3.0-rc.1` опубликована в
[Maven Central](https://repo.maven.apache.org/maven2/io/github/xorex-lc/ioc-platform-logging-slf4j/0.3.0-rc.1/)
и GitHub Packages. Отдельные
plain Java и Boot consumers прошли для каждого репозитория в
[запуске 39455105300](https://github.com/xORex-LC/ioc-extractor/actions/runs/39455105300).
Для Central не нужен дополнительный репозиторий; GitHub требует авторизованных
settings. [Процедура публикации, EN](../../library-publication.md#consumption)
описывает доступ и проверку происхождения артефактов.

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-logging-slf4j</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

Единственная main-зависимость — `org.slf4j:slf4j-api`, проверенная на версии
2.0.18. Провайдер выбирает приложение. Тесты используют Logback 1.5.38;
это не означает совместимость с любым провайдером. Процесс без провайдера
не подтверждает доставку логов и работу MDC. Starter в JAR не входит.

Проверенная Java-реализация совпадает с тегом
`ioc-platform-logging-slf4j-v0.3.0-rc.1`; версия реактора остаётся
`0.3.0-SNAPSHOT`. Текущие реактор и публикация ещё используют общую версию;
независимые версии приняты, но технический переход не завершён. Порядок
развития API и выпуска описан в [правилах разработки](development.md).
[Постоянная запись выпуска](../../../../.github/release-notes/ru/ioc-platform-logging-slf4j-0.3.0-rc.1.md)
сохраняет исходный коммит, подписанные файлы и проверки обоих репозиториев.

Три полных примера ниже компилируются прямо из руководства и выполняются
[LoggingLibraryExampleTest](../../../../platform/platform-logging-slf4j/src/test/java/com/iocextractor/platform/logging/LoggingLibraryExampleTest.java).
[Справочник модуля](../../../../platform/platform-logging-slf4j/README.md) задаёт
владение, публичный Javadoc — контракты членов API. Проверка упаковки и публикации
отделена от этих примеров внутри checkout.
Отдельные consumers также выполняют их на JAR вне checkout с пустым Maven cache.
Провайдер и classifiers не могут скрыть отсутствие main-зависимости SLF4J API.
Это проверка API и упаковки, а не второго развёрнутого сервиса.

## Публичные инструменты

Основной пакет — `com.iocextractor.platform.logging`. Библиотека предоставляет
восемь публичных типов; `LogValueNormalizer` и хранение полей остаются внутренними.

| Тип | Применение и публичные операции |
|---|---|
| `LogFieldSpec` | Неизменяемое объявление `(String key, LogValueType valueType)`; record equality учитывает оба компонента |
| `LogValueType` | Представления STRING, LONG и BOOLEAN |
| `LogEvents` | `trace/debug/info/warn/error(Logger)` создают новый `LogEvent` выбранного уровня |
| `LogEvent` | `field(LogFieldSpec, Object)`, `action(String)`, `outcome(EventOutcome)`, `durationNanos(long)`, `message(String)` формируют запись; `log()` / `log(Throwable)` отправляют её провайдеру |
| `MdcScope` | `open()`, `put(LogFieldSpec, String)`, `hide(LogFieldSpec)`, `close()` восстанавливают затронутые ключи MDC; реализует `AutoCloseable` |
| `ecs.EcsFields` | Пять общих скалярных дескрипторов из таблицы ниже |
| `ecs.EventOutcome` | SUCCESS, FAILURE, UNKNOWN; `value()` возвращает строку в нижнем регистре |
| `redaction.LogRedaction` | `fullMask()` возвращает константу; `urlComponents(String)` преобразует одну выбранную строку URL |

## Объявление полей и запись результата операции

Сохраните каждый пример в `src/main/java/examples/` под именем его публичного
класса. Приложение подключает провайдер и получает logger, например через
`LoggerFactory.getLogger("collector.jobs")`. Вызовите
`CollectionExample.run(logger)`; остальные примеры используют такой же
`run(Logger)`. Для примера с пропуском нужен включённый DEBUG.
Standalone consumer содержит вызывающий код и независимые проверки результата.

```java
package examples;

import com.iocextractor.platform.logging.LogEvents;
import com.iocextractor.platform.logging.LogFieldSpec;
import com.iocextractor.platform.logging.LogValueType;
import com.iocextractor.platform.logging.MdcScope;
import com.iocextractor.platform.logging.ecs.EventOutcome;
import com.iocextractor.platform.logging.redaction.LogRedaction;
import org.slf4j.Logger;

public final class CollectionExample {
    private static final LogFieldSpec TASK = new LogFieldSpec("collector.task.id", LogValueType.STRING);
    private static final LogFieldSpec ITEMS = new LogFieldSpec("collector.items", LogValueType.LONG);
    private static final LogFieldSpec RETRY = new LogFieldSpec("collector.retry", LogValueType.BOOLEAN);
    private static final LogFieldSpec TOKEN = new LogFieldSpec("collector.auth.token", LogValueType.STRING);

    public static void run(Logger logger) {
        try (var scope = MdcScope.open()) {
            scope.put(TASK, "task-42");
            LogEvents.info(logger).action("collect_complete").outcome(EventOutcome.SUCCESS)
                    .durationNanos(500).field(ITEMS, 12).field(RETRY, false)
                    .field(TOKEN, LogRedaction.fullMask())
                    .message("collection completed").log();
        }
    }
}
```

Запись содержит контекст задачи, числовой счётчик, boolean и ровно `[redacted]`
в выбранном STRING-поле токена. После выхода прежний контекст восстанавливается.
Сначала получите scope, затем заполняйте его внутри тела: цепочка вызовов
в инициализаторе ресурса может завершиться ошибкой до того, как Java получит
ресурс для последующего закрытия.

Ключи чувствительны к регистру. Каждый сегмент пути соответствует
`[A-Za-z_][A-Za-z0-9_-]*`; пробелы, пустые сегменты и неверные символы приводят
к отказу, а не к нормализации. Сервис проверяет уникальность каталога и
конфликты родительских путей, например `collector` и `collector.task.id`.

| Тип | Допустимые входы | Передаваемое значение |
|---|---|---|
| STRING | CharSequence, Enum, Path, UUID | Снимок через toString() |
| LONG | Byte, Short, Integer, Long | Long |
| BOOLEAN | Boolean | Boolean |

Массивы, коллекции, maps, дробные числа, произвольные объекты и строки
с числами/boolean отвергаются. Enum преобразуется через `toString()`,
а helper outcome явно использует значение в нижнем регистре.

Равенство `LogFieldSpec` учитывает ключ и тип, но внутри события поле
определяется ключом. Эквивалентное объявление заменяет значение. Другой тип
для того же ключа запрещён даже после первого или повторного null.
Неудачный вызов сохраняет прежние значение и объявление. Побеждает последнее
успешное обновление; порядок JSON-полей не является контрактом.
Глобального реестра полей нет.

## Пропуск поля и временное скрытие MDC

```java
package examples;

import com.iocextractor.platform.logging.LogEvents;
import com.iocextractor.platform.logging.LogFieldSpec;
import com.iocextractor.platform.logging.LogValueType;
import com.iocextractor.platform.logging.MdcScope;
import org.slf4j.Logger;

public final class OmissionExample {
    private static final LogFieldSpec TOKEN = new LogFieldSpec("collector.auth.token", LogValueType.STRING);

    public static void run(Logger logger) {
        try (var scope = MdcScope.open()) {
            scope.hide(TOKEN);
            LogEvents.debug(logger).action("collect_skipped")
                    .field(TOKEN, null).message("collection skipped").log();
        }
    }
}
```

Null убирает локальное значение из записи, но обычно оставляет одноимённый
ambient MDC видимым. Scope из примера временно удаляет и эту копию,
а после записи восстанавливает её. Лучше вообще не помещать секреты в MDC.
Маска и hide относятся только к выбранному ключу: message, Throwable и другие
копии данных остаются исходными. Строковую маску нельзя передать в LONG/BOOLEAN.

Запись о пропуске не содержит outcome и duration. `unknown` нужен, когда исход
применим, но неизвестен. `durationNanos` принимает измеренное число наносекунд
от нуля до `Long.MAX_VALUE`; измерение и переполнение контролирует вызывающий
код. Не подставляйте ноль вместо отсутствующего измерения.

MDC scope принадлежит одному потоку; вложенные scopes закрываются в LIFO-порядке.
В worker восстанавливайте контекст из явных метаданных команды, внутри самого
worker. Нет автоматического переноса через executor или сеть. Изменения
незатронутых ключей сохраняются; бизнес-состояние не должно зависеть от MDC.

`open()` не читает и не меняет MDC. `put` требует STRING-дескриптор и String;
null временно скрывает ключ. `hide` принимает дескриптор любого типа.
Соблюдение потока и порядка закрытия — обязанность вызывающего кода;
проверки этого во время выполнения нет. Успешное повторное закрытие безвредно,
а последующие записи через закрытый scope запрещены. Ошибки провайдера выходят
наружу; полное восстановление при ошибке самого провайдера не гарантируется.

## Ошибка и явное преобразование URL

```java
package examples;

import com.iocextractor.platform.logging.LogEvents;
import com.iocextractor.platform.logging.LogFieldSpec;
import com.iocextractor.platform.logging.LogValueType;
import com.iocextractor.platform.logging.ecs.EventOutcome;
import com.iocextractor.platform.logging.redaction.LogRedaction;
import org.slf4j.Logger;

public final class FailureExample {
    private static final LogFieldSpec URL = new LogFieldSpec("collector.request.url", LogValueType.STRING);

    public static void run(Logger logger) {
        var failure = new IllegalStateException("synthetic connection failure");
        LogEvents.error(logger).action("collect_fetch").outcome(EventOutcome.FAILURE)
                .field(URL, LogRedaction.urlComponents("https://u:p@example.test/p?q=secret#f"))
                .message("collection failed").log(failure);
    }
}
```

Получается `https://<redacted>@example.test/p?<redacted>#f`. Это лексическое
преобразование, а не URI parser или нормализатор. User-info распознаётся только
после `://`, до последнего `@` перед `/`, `?` или `#`. Затем скрывается содержимое
после первого `?` до следующего `#` или конца строки. User-info без схемы,
пути и fragments могут оставаться видимыми; `?` внутри fragment тоже обрабатывается.
Для null возвращается null. Если сохранённые части нельзя раскрывать, выбирайте
полную маску. `fullMask()` не принимает и не преобразует исходный секрет.

Throwable передаётся без изменений и может содержать чувствительный текст.
Решение о допустимости такой записи принимает сервис.

## ECS и владение encoder

Общие поля используют смыслы ECS 8.11, а не полную модель ECS.
`file.path` означает полный путь с именем; преобразование Path само по себе
не делает путь абсолютным. Разрешение пути и раскрытие данных принадлежат
производителю. Исходные определения:
[event](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/event.yml),
[file](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/file.yml),
[error](https://raw.githubusercontent.com/elastic/ecs/v8.11.0/schemas/error.yml).

| Константа `EcsFields` | Ключ / представление | Обязанность производителя |
|---|---|---|
| `EVENT_ACTION` | `event.action` / STRING | Выбрать операцию; `.action(...)` отвергает blank без обрезки пробелов |
| `EVENT_OUTCOME` | `event.outcome` / STRING | `.outcome(...)` пишет success/failure/unknown в нижнем регистре; неприменимый исход пропускают |
| `EVENT_DURATION` | `event.duration` / LONG | Передать измеренные наносекунды; `.durationNanos(...)` запрещает отрицательные значения |
| `ERROR_TYPE` | `error.type` / STRING | Указать тип ошибки; при Throwable отдать `error.*` проверенному Boot encoder |
| `FILE_PATH` | `file.path` / STRING | Подготовить и раскрыть полный путь; Path conversion не делает его абсолютным |

Общий `.field(...)` проверяет только представление, поэтому позволяет обойти
смысловые ограничения helpers. Raw SLF4J и сторонний MDC тоже обходят этот
профиль. Сервис проверяет каталог, производителей и конечные байты, а не
использует facade как полный ECS validator. Массивы type/category в v1 не входят.

Приложение владеет сериализацией, `@timestamp`, message, `ecs.*`, `service.*`,
`process.*`, `log.*` и встроенными метаданными. Не объявляйте их повторно
в событиях или MDC. Пользовательские поля имеют namespace сервиса — например,
`collector.*` или `ioc.*`. ID задачи не является distributed trace/span ID.

В проверенном Boot 4.0.8 рецепте `event.dataset` принадлежит конфигурации:
событие и MDC не могут его переопределять. При Throwable encoder формирует
весь `error.*`, поэтому пример не добавляет `error.type`. Без Throwable можно
использовать `EcsFields.ERROR_TYPE` для ошибки только с типом.
Appender способен отбросить конфликтующую запись, не бросив исключение
производителю: проверяйте реальные байты и encoder status.

IOC encoder и его конфигурация остаются кодом приложения. Отдельный
[Boot ECS-рецепт](logging-slf4j-boot.md) содержит исполняемые Java/YAML/XML
для синхронного пути с изменяемыми событиями, границы dataset/error и
неподдерживаемые пути. Регрессии IOC-производителей остаются в сервисных тестах.
[ADR 0045, EN](../../../ADR/0045-reusable-slf4j-logging-contract.md)
фиксирует эту границу.

## Ошибки и жизненный цикл

Null в обязательных аргументах приводит к `NullPointerException`. Неверные ключи,
представления, конфликтующие объявления, blank action и отрицательная duration
приводят к `IllegalArgumentException`. Запись через закрытый scope приводит
к `IllegalStateException`. Поля и helpers проверяются до фильтрации уровня.
У выключенного уровня пропускаются подготовка MDC и отправка провайдеру.
У включённого уровня скрываются только совпавшие ключи MDC для ненулевых полей,
включая LONG/BOOLEAN; восстановление предпринимается и после ошибки.
Конфликты родительских путей, например `collector` и `collector.items`, остаются
ответственностью сервиса. Скрытие точного совпавшего ключа не удаляет дочерние
ключи, не подавляет весь объект и не задаёт глобальную политику зарезервированных
полей. Проверяйте пустой/отсутствующий MDC и null локального поля отдельно от
ненулевого локального переопределения.

Сообщения о несовпадении типов не включают переданное значение, но некоторые
ошибки содержат ключ, который сам может быть чувствительным. Пользовательский
`toString()` и провайдер могут бросить собственные исключения. Библиотека
не является безопасным renderer произвольных ошибок. Повторных попыток и
резервного logger нет; ошибки провайдера выходят наружу.

Builder изменяемый и не потокобезопасный, принадлежит одному потоку и
построению одной записи. Повторный `log()` означает повторную попытку отправки.
Пустой message по умолчанию и null в необязательном cause допустимы.
Успешный возврат не подтверждает запись в durable storage или JSON-кодирование.
Различайте построение/валидацию поля, вызов провайдера, принятие appender/encoder
и сохранённые/прочитанные получателем байты. Log record не является transaction
receipt и не доказывает commit операции.
Внутреннее подключение IOC описано в [observability, EN](../../../dev/observability.md).

## Источники, проверки и обновление

- [Исходники и package Javadoc](../../../../platform/platform-logging-slf4j/src/main/java/com/iocextractor/platform/logging/)
  задают точные контракты членов API; [README модуля](../../../../platform/platform-logging-slf4j/README.md)
  описывает пакеты и зависимости.
- [Тесты модуля](../../../../platform/platform-logging-slf4j/src/test/java/com/iocextractor/platform/logging/)
  проверяют типы и снимки значений, disabled levels, ошибки провайдера,
  восстановление MDC, worker-контекст и маски.
  Команда: `make test-module MODULE=platform/platform-logging-slf4j`.
- [Plain consumer](../../../../tools/fixtures/logging-consumer/README.md) и
  [Boot consumer](../../../../tools/fixtures/logging-boot-consumer/README.md)
  компилируют реальные примеры на JAR с разными пустыми caches; Boot дополнительно
  загружает рецепт и проверяет выходные байты и статусы.
- [Контракты публикации](../../../../tools/tests/library-publication-test.py)
  проверяют EN/RU Java/XML/YAML и извлечение: `make library-publication-test`.
  Ссылки проверяются через `make docs`. Независимые drivers и проверки JSON
  сохранены отдельно от компилируемого текста.

При изменении публичного поведения обновляйте оба языка и исполняемые примеры.
Контракты членов API остаются в Javadoc, интеграция IOC — в capability-документе,
идентичность выпуска — в постоянной записи. Другой провайдер, Boot baseline,
асинхронный encoder, второй развёрнутый сервис, collector/Elasticsearch и
нагрузка требуют отдельной проверки.
