# Logging-библиотека со Spring Boot ECS

[English version](../../libraries/logging-slf4j-boot.md) · [Основное руководство](logging-slf4j.md)

Рецепт принадлежит приложению-потребителю. Он проверен с Java 21,
Spring Boot 4.0.8, Logback 1.5.38 и SLF4J 2.0.18. Общему JAR по-прежнему
нужен только SLF4J API. Типы полей, срок жизни MDC, маскирование и ошибки
описаны в [основном руководстве](logging-slf4j.md).

Поддерживается **синхронный FileAppender с изменяемыми Logback LoggingEvent**.
Не включайте сюда AsyncAppender, сериализованный транспорт событий или
LoggingEventVO. Асинхронные snapshot-тесты библиотеки не подтверждают работу
этого encoder на таких путях. Другой сервис размещает небольшой encoder
в своём bootstrap и проверяет полную конфигурацию перед развёртыванием.

## Зависимости

Используйте dependency management Spring Boot своего сервиса и
`spring-boot-starter` с выбранным провайдером. Зафиксируйте выбранную
опубликованную версию logging. Текущие реактор и публикация используют общую
версию продукта/библиотек; независимые версии ожидают технической активации,
описанной в [правилах разработки](development.md). Зависимость `0.3.0-rc.1`
приведена в [основном руководстве](logging-slf4j.md#подключение-и-границы-проверки).
Рецепт прошёл отдельные cold consumers из Central и GitHub Packages;
полную backend-конфигурацию своего сервиса проверяет потребитель.

## Один файловый выход

Разместите настройки в `application.yml`. Оператор может выбрать файл
через переменную окружения; по умолчанию используется относительный путь
сервиса. Ротацию и срок хранения выбирают отдельно: минимальный рецепт
их не задаёт.

```yaml
spring:
  application:
    name: feeds-collector
logging:
  file:
    name: ${COLLECTOR_LOG_FILE:var/logs/feeds-collector.ecs.json}
  structured:
    ecs:
      service:
        name: ${spring.application.name}
        version: "example-1"
        environment: example
```

Поместите `logback-spring.xml` на classpath приложения и загружайте через Boot:
обычная конфигурация Logback не разрешает `springProperty`. Configuration
scanning с этими расширениями Boot не поддерживается. Root level и другие
выходы определяет приложение. [Справочник Boot, EN](https://docs.spring.io/spring-boot/4.0/reference/features/logging.html#features.logging.logback-extensions).

```xml
<configuration>
    <springProperty scope="context" name="COLLECTOR_LOG_FILE" source="logging.file.name"/>
    <springProperty scope="context" name="SERVICE_NAME" source="logging.structured.ecs.service.name"/>
    <appender name="ECS_FILE" class="ch.qos.logback.core.FileAppender">
        <file>${COLLECTOR_LOG_FILE}</file>
        <append>true</append>
        <encoder class="examples.logging.CollectorEcsEncoder">
            <format>ecs</format>
            <charset>UTF-8</charset>
            <eventDataset>${SERVICE_NAME}</eventDataset>
        </encoder>
    </appender>
    <logger name="collector" level="DEBUG"/>
    <root level="INFO">
        <appender-ref ref="ECS_FILE"/>
    </root>
</configuration>
```

Файл дополняется после перезапуска, DEBUG для namespace `collector` позволяет
выполнить пример пропуска из основного руководства. Добавьте нужные сервису
ротацию и хранение. У IOC daemon уже есть отдельная политика RollingFileAppender;
этот рецепт её не заменяет.

## Encoder приложения

Сохраните класс как `examples/logging/CollectorEcsEncoder.java` в сервисе.
Dataset из конфигурации попадает в один поток key/value вместе с динамическими
`event.*`, поэтому Boot формирует один вложенный объект `event`.
Простое `logging.structured.json.add.event.dataset` не заменяет эту проверенную
комбинацию. Пакет/имя класса можно поменять вместе с XML-ссылкой;
ни один из них не является типом logging-библиотеки.

```java
package examples.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

import java.util.ArrayList;

/** Adds one configured dataset to synchronous, mutable Logback records. */
public final class CollectorEcsEncoder extends StructuredLogEncoder {
    private static final String DATASET = "event.dataset";
    private String eventDataset;

    /** Sets the application-owned dataset; startup rejects a missing value. */
    public void setEventDataset(String eventDataset) {
        this.eventDataset = eventDataset;
    }

    /** Validates configuration before starting Boot's formatter. */
    @Override
    public void start() {
        if (eventDataset == null || eventDataset.isBlank()) {
            throw new IllegalStateException("eventDataset must not be blank");
        }
        super.start();
    }

    /**
     * Encodes one supported record and restores its original key/value pairs.
     * Unsupported snapshots and dataset overrides are rejected; an appender
     * can contain that exception, so inspect its status as well as output bytes.
     */
    @Override
    public byte[] encode(ILoggingEvent event) {
        if (!(event instanceof LoggingEvent mutableEvent)) {
            throw new IllegalArgumentException("This recipe requires a mutable LoggingEvent");
        }
        var original = mutableEvent.getKeyValuePairs();
        if (event.getMDCPropertyMap().containsKey(DATASET)
                || (original != null && original.stream().anyMatch(pair -> DATASET.equals(pair.key)))) {
            throw new IllegalArgumentException("event.dataset belongs to application configuration");
        }
        var enriched = original == null ? new ArrayList<KeyValuePair>() : new ArrayList<>(original);
        enriched.add(new KeyValuePair(DATASET, eventDataset));
        mutableEvent.setKeyValuePairs(enriched);
        try {
            return super.encode(mutableEvent);
        } finally {
            mutableEvent.setKeyValuePairs(original);
        }
    }
}
```

Политикой зарезервированных полей владеет сервис. Не задавайте `event.dataset`
из производителя или MDC. Не дублируйте `service.*`, `log.*`, `process.*`,
`ecs.*`, message и timestamp. Проверяйте конфликты скалярных/объектных путей
в каталоге. Эти проверки не могут перехватить произвольные raw SLF4J-записи
и сторонний MDC.

При Throwable не добавляйте явные `error.*`: Boot формирует весь объект ошибки.
Без Throwable `EcsFields.ERROR_TYPE` позволяет записать только тип.
[Структурированное логирование Boot, EN](https://docs.spring.io/spring-boot/4.0/reference/features/logging.html#features.logging.structured).

## Проверка подключения

Выполните collection, omission и failure из основного руководства через этот
файловый выход. Для collection должны получиться следующие значения;
PID, timestamp и thread — метаданные конкретного процесса.

| JSON pointer | Тип | Ожидаемое значение |
|---|---|---|
| `/service/name` | string | `feeds-collector` |
| `/service/version` | string | `example-1` |
| `/event/dataset` | string | `feeds-collector` |
| `/event/action` | string | `collect_complete` |
| `/event/outcome` | string | `success` |
| `/event/duration` | integer | `500` |
| `/collector/items` | integer | `12` |
| `/collector/retry` | boolean | `false` |
| `/collector/auth/token` | string | `[redacted]` |

Также проверьте отсутствие outcome/duration в omission, тип и stack trace
в failure, отсутствие stack trace у ошибки только с типом. JSON parser должен
отвергать дублирующиеся ключи. Для допустимого ввода требуются непустой файл
и отсутствие внутренних WARN/ERROR. Возврата из вызова appender недостаточно.

`LoggingBootRecipeIT` компилирует этот Java-блок и plain-примеры, загружает
точные YAML/XML через Boot и запускает отдельную JVM без IOC-классов.
Неподдерживаемые snapshots, dataset overrides и известные конфликты JSON/error
должны дать пустой файл и наблюдаемый appender error. Это проверка backend
в checkout, а не холодного репозитория, production deployment, collector
или Elasticsearch mappings. [ADR 0045, EN](../../../ADR/0045-reusable-slf4j-logging-contract.md)
разделяет эти обязанности.

Инструмент публикации также копирует те же блоки и нейтральный driver
в отдельный [Boot consumer](../../../../tools/fixtures/logging-boot-consumer/README.md).
Он компилируется на JAR, независимо разрешает backend с пустым cache и
проверяет пять допустимых записей. Шесть отрицательных сценариев принадлежат
bootstrap integration suite. Успех file-repository — отдельная проверка упаковки;
Central и GitHub прошли самостоятельные protected-проверки для `0.3.0-rc.1`
в [запуске 39455105300](https://github.com/xORex-LC/ioc-extractor/actions/runs/39455105300).

## Источники и обновление

Encoder примера — код приложения, а не девятый тип библиотеки.
`setEventDataset(String)`, `start()` и `encode(ILoggingEvent)` относятся
к рецепту. Appender, права на файл, ротацию и хранение определяет сервис.
Нейтральный [driver](../../../../bootstrap/ioc-app/src/test/resources/consumer/logging/BootRecipeMain.java)
и [integration test](../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingBootRecipeIT.java)
проверяют пять допустимых записей и шесть отказов. Команда:
`make test-one MODULE=bootstrap/ioc-app TEST=LoggingBootRecipeIT`.

[Запись выпуска](../../../../.github/release-notes/ru/ioc-platform-logging-slf4j-0.3.0-rc.1.md)
сохраняет исходную проверку репозиториев. Справочник Boot 4.0 обновляется
вместе с patch-версиями; проверенный baseline этого рецепта остаётся 4.0.8.
Обновляйте EN/RU вместе: Java/YAML/XML должны совпадать. После смены encoder,
backend, выходов или предположения о синхронных изменяемых событиях
подключение нужно проверить заново.
