# Контракты локальных управляющих событий

[English version](../../libraries/events.md) · [Каталог библиотек](README.md)

`ioc-platform-events` предоставляет небольшой publish-only порт и метаданные
локальных управляющих событий. Они сообщают о факте или ускоряют работу,
которую сервис может восстановить из собственного состояния. Конкретные
классы событий, payload, listeners, адаптеры доставки и восстановление
принадлежат сервису.

Это JDK-контракт, а не broker или межпроцессный формат сообщений. В библиотеке
нет подписок, очереди, executor, подтверждений, транзакций, outbox,
сериализации, дедупликации или повторных попыток. Событие ускоряет работу;
источником истины остаются durable state и сверка состояния сервиса.

## Подключение и границы проверки

Нужна Java 21. Production-зависимостей кроме JDK нет:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-events</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

Версия `0.3.0-rc.1` доступна в Central и GitHub Packages. Для Central не нужен
дополнительный репозиторий; GitHub требует авторизованных settings.
См. [процедуру публикации, EN](../../library-publication.md#consumption).
Отдельные cold JDK consumers прошли для обоих репозиториев в
[запуске 40160627532](https://github.com/xORex-LC/ioc-extractor/actions/runs/40160627532).
[Постоянная запись выпуска](../../../../.github/release-notes/ru/ioc-platform-events-0.3.0-rc.1.md)
сохраняет исходный коммит, подписанные хеши и сведения проверки.

Проверенная реализация совпадает с тегом `ioc-platform-events-v0.3.0-rc.1`;
реактор остаётся на `0.3.0-SNAPSHOT`. Пакет сохраняет имя
`com.iocextractor.platform.events`. Версии продукта/библиотек общие, а
`eventVersion` описывает payload сервиса и не является Maven-версией.
Для Spring используйте [явный адаптер](events-spring.md) той же версии.
Второй развёрнутый сервис и wire-протокол этой проверкой не покрыты.

## Публичные инструменты

Семь публичных типов находятся в `com.iocextractor.platform.events`.

| Тип | Назначение |
|---|---|
| `ControlEvent` | Событие сервиса с `ControlEventMetadata metadata()`; проверка payload остаётся локальной |
| `ControlEventMetadata` | Неизменяемый record из шести компонентов; constructor и `withoutCausation(...)` проверяют переданные метаданные; `causation()` возвращает `Optional<String>` вместо nullable accessor |
| `ControlEventPublisher` | Функциональный `void publish(ControlEvent)`; реализация определяет доставку, порядок, ошибки и потокобезопасность |
| `ControlEventObserver` | Четыре telemetry-callbacks публикации/dispatch; это не подписки и не бизнес-обработчики |
| `NoopControlEventPublisher` | Stateless `INSTANCE`: отвергает null event, иначе отбрасывает ссылку |
| `NoopControlEventObserver` | Stateless `INSTANCE`: проверяет аргументы callbacks и отбрасывает наблюдения |
| `RecordingControlEventPublisher` | Публичный пустой constructor, `publish(ControlEvent)` и `List<ControlEvent> events()` для небольших тестов/fixtures |

## Определение события и проверка publisher

Сохраните `LocalEventsExample.java` в `src/main/java/` потребителя,
задайте `maven.compiler.release=21` и добавьте зависимость выше.
Запустите `main`. Полный пример использует событие другого коллектора
и записывающий test double. В production сервис передаёт адаптер доставки
через тот же порт.

```java
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.ControlEventPublisher;
import com.iocextractor.platform.events.RecordingControlEventPublisher;

import java.time.Instant;
import java.util.Objects;

public final class LocalEventsExample {
    public record RefreshRequested(ControlEventMetadata metadata, String sourceId)
            implements ControlEvent {
        public RefreshRequested {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(sourceId, "sourceId");
        }
    }

    public static void main(String[] args) {
        var recording = new RecordingControlEventPublisher();
        announce(recording);
        System.out.println(recording.events().getFirst().metadata().eventType());
    }

    private static void announce(ControlEventPublisher publisher) {
        var metadata = ControlEventMetadata.withoutCausation(
                "refresh-17", "collector.refresh-requested", 1,
                Instant.parse("2026-10-24T12:00:00Z"), "poll-6");
        publisher.publish(new RefreshRequested(metadata, "feed-a"));
    }
}
```

Ожидаемый stdout: `collector.refresh-requested`. Записана одна ссылка на событие;
обработчики не вызывались. `sourceId` и проверки payload принадлежат сервису.
Интерфейс требует ненулевые метаданные, но publisher не обязан их читать.
No-ops и recorder проверяют только ссылку event и не подтверждают корректность
метаданных или payload пользовательской реализации.

## Явные метаданные

| Компонент record | Смысл и проверка |
|---|---|
| `String eventId` | Обязательный nonblank ID производителя; уникальность и дедупликация не проверяются |
| `String eventType` | Обязательное nonblank имя сервиса; реестр и схема именования не навязываются |
| `int eventVersion` | Положительная версия payload; без schema negotiation или проверки совместимости |
| `Instant occurredAt` | Обязательное время факта; сравнения с текущими часами нет |
| `String correlationId` | Обязательная nonblank связь работ; без автоматической установки MDC/tracing |
| `String causationId` | Null при отсутствии непосредственной причины, иначе nonblank; `causationId()` остаётся nullable |

Текст сохраняется точно, включая пробелы по краям. Null или `String.isBlank()`
в обязательном тексте приводит к `IllegalArgumentException`, как и
неположительная версия или blank non-null causation. Null timestamp приводит
к `NullPointerException`. `withoutCausation(String, String, int, Instant, String)`
задаёт последний компонент как null; `causation()` возвращает empty или исходный ID.
Обычное record equality учитывает все компоненты. Библиотека не создаёт,
не заменяет и не распространяет IDs/timestamps.

## Смысл публикации и наблюдения

`publish(null)` нарушает контракт; предоставленные реализации выбрасывают
`NullPointerException`. Пользовательский publisher соблюдает порт сам:
интерфейс не добавляет wrapper или движок проверок. Обычный возврат не доказывает
вызов обработчика или durable completion. Ошибка возможна после частичной
доставки, поэтому retry/idempotency определяются состоянием сервиса,
а не результатом вызова или ID события.

| Callback `ControlEventObserver` | Наблюдение |
|---|---|
| `published(ControlEvent)` | Внутренний вызов публикации вернулся нормально, в том числе без handlers или до завершения async-работы |
| `publishFailed(ControlEvent, RuntimeException)` | Вызов публикации завершился ошибкой; некоторые handlers могли уже выполнить действия |
| `dispatching(ControlEvent, String)` | Попытка вызова одного обработчика сервиса с заданным сервисом именем |
| `dispatchFailed(ControlEvent, String, RuntimeException)` | Один обработчик завершился ошибкой; предыдущие эффекты не отменяются |

Callbacks — telemetry, а не бизнес-работа. Их вызывающий код управляет ошибками.
Spring-адаптер вызывает только callbacks публикации; dispatcher сервиса сам
выбирает наблюдение отдельных попыток. No-op callbacks отвергают null events
и failures через `NullPointerException`, null/blank handler names — через
`IllegalArgumentException`; nonblank имена не обрезаются. No-op publisher
и observer не имеют изменяемого состояния и могут использоваться из разных
потоков. Они не читают метаданные, не доставляют работу и не уведомляют
другого observer.

## Границы recorder

Каждый вызов сохраняет исходную ссылку event, включая повторы. `events()`
возвращает неизменяемый поверхностный снимок: последующие публикации его
не увеличивают, но изменяемый payload остаётся общим. Операции clear/reset
нет: создавайте новый recorder для каждого теста, а не используйте как журнал.

Concurrent publication и snapshots безопасны. Последовательные вызовы
сохраняют порядок; для пересекающихся вызовов порядок по времени входа
не обещан. Copy-on-write копирует список на каждой публикации; предела
ёмкости и persistence нет. Для долгоживущей production-истории это не подходит.
Recorder не создаёт workers, не открывает ресурсы и не требует close.

## Источники, проверки и обновление

- [README модуля](../../../../platform/platform-events/README.md) и
  [исходники/package Javadoc](../../../../platform/platform-events/src/main/java/com/iocextractor/platform/events/)
  задают точные контракты. Конкретные события, handlers и policies остаются в сервисе.
- [Тесты модуля](../../../../platform/platform-events/src/test/java/com/iocextractor/platform/events/)
  проверяют границы метаданных, no-ops, повторы, immutable snapshots и
  concurrent recording с ограниченным завершением workers.
  Команда: `make test-module MODULE=platform/platform-events`.
- [Отдельный JDK consumer](../../../../tools/fixtures/events-consumer/README.md)
  компилирует этот Java-блок на JAR с пустым cache, проверяет отсутствие
  сторонних зависимостей, четыре хеша/origins и routing negatives.
- [Контракты публикации](../../../../tools/tests/library-publication-test.py)
  проверяют EN/RU Java/XML и точное извлечение: `make library-publication-test`.
  Ссылки: `make docs`. [Координация IOC, EN](../../../dev/event-coordination.md)
  описывает durable backstops и wiring; [ADR 0047, EN](../../../ADR/0047-explicit-spring-control-event-adapter.md)
  фиксирует разделение артефактов.

При изменении поддерживаемого поведения обновляйте оба языка, Javadoc и
независимые consumers. Владение API/версией описано в
[процедуре публикации, EN](../../library-publication.md#events-api-and-version-ownership).
Выпущенные coordinate/tag неизменяемы. Другой deployment, внешний broker,
wire-формат или завершение всех handlers требуют отдельной проверки.
