# Публикация локальных управляющих событий через Spring

[English version](../../libraries/events-spring.md) · [JDK-контракты](events.md) ·
[Каталог библиотек](README.md)

`ioc-events-spring` связывает `ControlEventPublisher` с переданным сервисом
`ApplicationEventPublisher`. Он один раз пытается опубликовать событие,
наблюдает результат и по явным правилам сдерживает обычные runtime-ошибки
публикации/telemetry. До ошибки доставка уже может быть частичной.

Сервис владеет контекстом, listeners, multicaster, error handler, executor,
logging provider, транзакциями и восстановлением. Адаптер не регистрирует beans,
не задаёт execution policy, не владеет ресурсами и не требует shutdown.

## Подключение и границы проверки

Нужны Java 21 и одинаковая версия core/adapter:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-events-spring</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

Самостоятельный POM требует `ioc-platform-events` ровно той же версии,
Spring Context 7.0.9 и SLF4J API 2.0.18. Boot, starter, logging provider
и auto-configuration не поставляются. Чтобы видеть fallback WARN, приложению
нужен провайдер. Отдельный consumer проверяет обычное разрешение зависимостей
и Boot 4.0.8 BOM приложения; BOM не добавляет Boot runtime.
Сам адаптер не проверяет версии зависимостей при выполнении.

Версия `0.3.0-rc.1` опубликована в Central и GitHub Packages. Отдельные cold
consumers прошли для обоих в [запуске 40162675262](https://github.com/xORex-LC/ioc-extractor/actions/runs/40162675262),
включая хеши/origins обоих артефактов. См.
[запись выпуска](../../../../.github/release-notes/ru/ioc-events-spring-0.3.0-rc.1.md)
и [settings репозиториев, EN](../../library-publication.md#consumption).
Другие Spring/Boot baselines и второй развёрнутый сервис не проверены.

Реализация совпадает с тегом `ioc-events-spring-v0.3.0-rc.1`;
реактор остаётся на `0.3.0-SNAPSHOT`. Текущие POM и публикация сохраняют общую
версию продукта/библиотек. Принятая [политика разработки](development.md)
предусматривает независимую версию семейства events после технической активации;
точное равенство версий core и адаптера сохраняется.
Публичный пакет сохраняет имя `com.iocextractor.adapter.out.events.spring`.

## Явное создание адаптера

Единственный публичный тип — `SpringControlEventPublisher` с двумя операциями:

| Операция | Контракт |
|---|---|
| `SpringControlEventPublisher(ApplicationEventPublisher, ControlEventObserver)` | Сохраняет delegate/observer без изменения их конфигурации; null приводит к `NullPointerException` |
| `void publish(ControlEvent)` | Отвергает null до побочных действий; один раз передаёт Spring ту же ссылку, не читая/копируя metadata или payload |

Constructor с внедрением logger для тестов имеет package-private доступ и
не является публичным API. Сервис выбирает observer;
`NoopControlEventObserver.INSTANCE` подходит, когда наблюдения намеренно
отбрасываются. `dispatching`/`dispatchFailed` этот адаптер не вызывает.

Сохраните `SpringEventsExample.java` в `src/main/java/` Java 21-потребителя
с зависимостью выше и запустите `main`. Пример регистрирует listener другого
сервиса и закрывает принадлежащий ему контекст.

```java
import com.iocextractor.adapter.out.events.spring.SpringControlEventPublisher;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.NoopControlEventObserver;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;

import java.time.Instant;

public final class SpringEventsExample {
    public record RefreshRequested(ControlEventMetadata metadata, String sourceId)
            implements ControlEvent { }

    public static final class RefreshListener {
        @EventListener
        public void refresh(RefreshRequested event) {
            System.out.println(event.sourceId());
        }
    }

    public static void main(String[] args) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RefreshListener.class);
            context.refresh();
            var publisher = new SpringControlEventPublisher(context, NoopControlEventObserver.INSTANCE);
            publisher.publish(new RefreshRequested(ControlEventMetadata.withoutCausation(
                    "refresh-17", "collector.refresh-requested", 1, Instant.EPOCH, "poll-6"), "feed-a"));
        }
    }
}
```

Ожидаемый stdout — `feed-a`. В этом стандартном синхронном контексте listener
работает до возврата вызова. Проверка конкретного event payload принадлежит
сервису: адаптер не проверяет sample record за него. IOC создаёт тот же
адаптер в composition root с logging observer. Вызов constructor сам по себе
не регистрирует publisher bean или listener.

## Точная граница ошибок

| Поведение delegate | Поведение адаптера |
|---|---|
| Нормальный возврат | Вызывает `observer.published(event)`, в том числе без подходящих listeners или после async hand-off |
| Обычный `RuntimeException` | Вызывает `observer.publishFailed(event, originalFailure)` и обычно возвращается; retry нет |
| Прямой `Error` | Пробрасывает исходный Error; callbacks публикации не вызываются |
| `UndeclaredThrowableException` с непосредственным undeclared throwable типа `Error` | Пробрасывает исходный Error, а не Spring wrapper; callbacks публикации не вызываются |
| Другой runtime wrapper | Сохраняет его идентичность как runtime-ошибку публикации; произвольные цепочки cause не обходятся |

Наблюдённая runtime-ошибка сдерживается, но не превращается в надёжный успех
или сигнал безопасного retry. Listeners могли уже зафиксировать эффекты.
Нормальный возврат тоже не доказывает, что какой-либо/каждый handler завершился
или записал durable state. Idempotency, транзакции, ledgers и сверка состояния
принадлежат сервису.

## Ошибка telemetry не меняет результат доставки

- Если `published` бросает runtime exception после нормального возврата delegate,
  выполняется одна best-effort WARN-попытка с этой ошибкой observer.
  `publishFailed` и повторная публикация не вызываются.
- Если `publishFailed` бросает runtime exception, основным throwable WARN остаётся
  исходная ошибка публикации. Отличающаяся ошибка observer добавляется как
  suppressed, если это разрешено. Self-suppression исключён; при выключенном
  suppression остаётся только исходное свидетельство ошибки.
- Runtime-сбой последнего fallback logger сдерживается без рекурсивного
  логирования или retry. Прямые Errors из observation и logging выходят наружу.

Callbacks observer выполняются в потоке вызывающего publisher. Сам delegate
может передать работу в другие потоки. Exception objects/text могут попасть
в logging backend, а suppressed evidence меняет исходный throwable; сервис
отвечает за безопасное содержимое и раскрытие. Запись ошибки через observer
или неисправный backend не гарантируется.

## Выполнение listeners определяет сервис

В проверенном стандартном синхронном
[Spring multicaster](https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/context/event/SimpleApplicationEventMulticaster.html)
неперехваченная ошибка listener останавливает multicast; следующие listeners
могут не выполниться. Если нужна изоляция/продолжение, настройте error handler
приложения. Когда он поглощает ошибку, внешний publisher видит только нормальный
возврат. Ошибки после асинхронной отправки тоже находятся за пределами внешнего
вызова. Это явная граница наблюдения, а не изоляция handlers.

Зависимости адаптера final, изменяемого состояния на публикацию нет. Для
concurrent callers всё равно нужны потокобезопасные delegate и observer.
Безопасность listeners, порядок, блокировки, отмена и завершение workers
определяются сервисом. Correlation из metadata не переносит MDC/tracing
автоматически.

## Источники, проверки и обновление

- [README модуля](../../../../adapters/adapter-events-spring/README.md),
  [исходники/package Javadoc](../../../../adapters/adapter-events-spring/src/main/java/com/iocextractor/adapter/out/events/spring/)
  и [ADR 0047, EN](../../../ADR/0047-explicit-spring-control-event-adapter.md)
  задают владение. [JDK-руководство](events.md) описывает метаданные и аргументы callbacks.
- [Unit и real-context тесты](../../../../adapters/adapter-events-spring/src/test/java/com/iocextractor/adapter/out/events/spring/)
  проверяют null, исходные exception identities, suppression, сбои observer/logger,
  частичную доставку annotated listeners, wrapped Error и конфигурацию сервиса.
  Команды: `make test-module MODULE=adapters/adapter-events-spring` и
  `make test-integration-module MODULE=adapters/adapter-events-spring`.
- [Отдельный Spring consumer](../../../../tools/fixtures/events-spring-consumer/README.md)
  компилирует этот Java-блок и JDK-пример на JAR, проверяет обе dependency graphs,
  исключённые/недоступные API, несовместимый Spring, routing без adapter/core
  и восемь точных основных хешей/origins.
- [Контракты публикации](../../../../tools/tests/library-publication-test.py)
  проверяют EN/RU Java/XML обоих руководств: `make library-publication-test`.
  Ссылки: `make docs`. [Координация IOC, EN](../../../dev/event-coordination.md)
  объясняет service-owned backstops и composition.

При изменении поведения обновляйте оба языка и независимые consumers.
Protected-публикация сначала core и неизменяемое восстановление пары описаны
в [процедуре публикации, EN](../../library-publication.md#events-api-and-version-ownership).
Перенос пакета, другой framework baseline, async completion всех handlers
и deployment второго сервиса этим руководством не проверены.
