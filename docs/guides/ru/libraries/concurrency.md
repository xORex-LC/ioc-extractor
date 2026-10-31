# Выполнение задач по ключу с библиотекой concurrency

[English version](../../libraries/concurrency.md) · [Каталог библиотек](README.md)

`ioc-platform-concurrency` координирует задачи с общим ключом внутри одной JVM.
Например, коллектор может последовательно обновлять один endpoint, пока другие
endpoint обрабатываются параллельно. Все вызывающие участки, которые должны
исключать друг друга, используют один экземпляр координатора.

Выберите один из двух инструментов:

| Задача | Инструмент | Где выполняется работа |
|---|---|---|
| Выполнить короткую операцию и вернуть результат, исключив одновременные вызовы с тем же ключом | `SynchronousKeyedExecutionGuard` | В вызывающем потоке |
| Передавать задачи в порядке поступления, ограничив ожидающую очередь каждого ключа | `BoundedKeyedSerialExecutor` | В выделенном асинхронном пуле |

Guard гарантирует взаимное исключение без обещания FIFO. Executor сохраняет
порядок приёма задач внутри ключа. Одинаковый ключ не объединяет и не удаляет
повторные задачи. Распределённые блокировки, сохранение задач, повторы и
восстановление после сбоя процесса остаются задачами сервиса.

## Подключение и версия

Опубликованная версия доступна из Maven Central и GitHub Packages:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-concurrency</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

Нужен JDK 21 или новее. Проверенный базовый вариант — Java 21. Сторонних
production-зависимостей нет; библиотеке не нужны Spring beans, logging provider
или родительский POM реактора. Для Central не требуется отдельное объявление
репозитория. GitHub Packages требует авторизованных Maven settings; подключение
описано в [руководстве публикации, EN](../../library-publication.md#consumption).

Реактор остаётся на `0.3.0-SNAPSHOT`. Production Java-код, сверенный при подготовке
этого руководства, совпадает с тегом `ioc-platform-concurrency-v0.3.0-rc.1`.
[Запись выпуска](../../../../.github/release-notes/ru/ioc-platform-concurrency-0.3.0-rc.1.md)
содержит неизменяемую исходную идентичность и сведения о публикации. Сейчас
библиотека и продукт используют общую линейку версий.

## Предоставляемые инструменты

Все двенадцать публичных типов находятся в пакете
`com.iocextractor.platform.concurrent`.

| Тип | Назначение |
|---|---|
| `WorkKey` | Точная непустая строка, определяющая очередь или блокировку |
| `KeyedExecutionGuard` | Контракт синхронного `execute(key, Supplier<T>)` и общая сводка состояния |
| `SynchronousKeyedExecutionGuard` | Несправедливая блокировка по ключу с повторным входом и удалением неиспользуемого состояния |
| `KeyedExecutionGuardSnapshot` | Приблизительные счётчики `activeKeys`, `executing` и `waiting` |
| `KeyedSerialExecutor` | Контракт `submit`, `snapshot`, `shutdown`, `awaitTermination` и `close` |
| `BoundedKeyedSerialExecutor` | FIFO-очереди по ключам с исключительным владением рабочим пулом |
| `WorkAdmission` | Ключ, статус и глубина ожидающей очереди на момент решения; `accepted()` проверяет статус |
| `WorkAdmissionStatus` | `ACCEPTED` или `REJECTED` |
| `KeyedSerialExecutorObserver` | Синхронные уведомления о завершении, ошибке задачи, отказе в приёме и отказе рабочего пула |
| `NoopKeyedSerialExecutorObserver` | Молчаливый observer, используемый конструктором executor с двумя аргументами |
| `KeyedSerialExecutorSnapshot` | Неизменяемый список состояний очередей |
| `KeyedWorkSnapshot` | Ключ одной очереди, `queuedDepth`, `running` и `oldestAge` |

По возможности выбирайте ключи из конечного каталога сервиса. `WorkKey.of`
сохраняет текст без изменений: `"endpoint-a"` и `" endpoint-a "` — разные ключи.
Добавляйте пространство имён, если разные операции не должны попадать в одну
очередь. Admission и snapshots содержат ключи; выбирайте идентификаторы,
которые безопасно показывать в телеметрии.

## Синхронное выполнение

Сохраните пример в `KeyedGuardExample.java`. Guard защищает всю операцию
чтения, увеличения и записи номера для одного endpoint, в том числе от других
вызывающих потоков с тем же ключом. Concurrent map позволяет безопасно менять
и значения других ключей.

```java
import com.iocextractor.platform.concurrent.SynchronousKeyedExecutionGuard;
import com.iocextractor.platform.concurrent.WorkKey;

import java.util.concurrent.ConcurrentHashMap;

public final class KeyedGuardExample {
    public static void main(String[] args) {
        var guard = new SynchronousKeyedExecutionGuard();
        var revisions = new ConcurrentHashMap<WorkKey, Integer>();
        var key = WorkKey.of("collector:endpoint-a");

        int revision = guard.execute(key, () -> {
            int next = revisions.getOrDefault(key, 0) + 1;
            revisions.put(key, next);
            return next;
        });

        if (revision != 1 || guard.snapshot().activeKeys() != 0) {
            throw new IllegalStateException("Revision or idle cleanup differs");
        }
        System.out.println("Guarded revision: " + revision);
    }
}
```

Ожидаемый вывод: `Guarded revision: 1`.

`execute` возвращает результат supplier, включая null, и передаёт его
`RuntimeException` или `Error` вызывающему коду. Guard освобождает ключ при любом
из этих исходов. Если освобождение тоже завершилось ошибкой, она добавляется
к исходной ошибке работы как suppressed exception.

Поддерживается вложенный вызов с тем же ключом в том же потоке. Ожидание
блокировки не прерывается и не гарантирует справедливого порядка: API не
обещает FIFO, ограничение времени захвата или отмену. Не ожидайте внутри
защищённой работы другой поток, которому нужен тот же ключ. Если операция
захватывает несколько разных ключей, все вызывающие участки должны использовать
один порядок захвата, иначе возможен deadlock. Guard не создаёт потоки и не
требует закрытия.

## Асинхронное выполнение

Сохраните пример в `KeyedExecutorExample.java`. Две принятые операции одного
endpoint выполняются в порядке передачи. Перед проверкой результата пример
явно ожидает завершения executor.

```java
import com.iocextractor.platform.concurrent.BoundedKeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkKey;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class KeyedExecutorExample {
    public static void main(String[] args) throws InterruptedException {
        var workers = Executors.newFixedThreadPool(2);
        var executor = new BoundedKeyedSerialExecutor(workers, 1);
        var operations = new CopyOnWriteArrayList<String>();
        var key = WorkKey.of("collector:endpoint-a");

        try {
            var fetch = executor.submit(key, () -> operations.add("fetch"));
            var publish = executor.submit(key, () -> operations.add("publish"));
            if (!fetch.accepted() || !publish.accepted()) {
                throw new IllegalStateException("Work was rejected; schedule recovery");
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(Duration.ofSeconds(5))) {
                    throw new IllegalStateException("Drain timed out; recover pending work");
                }
            } finally {
                // Emergency cleanup only after normal draining did not finish.
                if (!workers.isTerminated()) {
                    workers.shutdownNow();
                    if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Worker pool did not terminate");
                    }
                }
            }
        }

        if (!operations.equals(List.of("fetch", "publish"))) {
            throw new IllegalStateException("Per-key operation order differs");
        }
        System.out.println("Executed: " + String.join(", ", operations));
    }
}
```

Ожидаемый вывод: `Executed: fetch, publish`.

Лимит `1` разрешает одну переданную рабочему пулу задачу и одну ожидающую
**на каждый ключ**. Если первая задача завершилась до передачи второй, для
второй создаётся новая очередь. Нулевой лимит разрешает одну незавершённую
задачу каждого ключа. Общего ограничения числа ключей или очереди рабочего
пула нет. Разные ключи могут обрабатываться параллельно при наличии свободных
рабочих потоков. Одновременные вызовы упорядочиваются по приёму; этот порядок
может отличаться от времени начала вызовов.

Конструктор с двумя аргументами использует молчаливый observer и системные
часы UTC. Другие конструкторы принимают `KeyedSerialExecutorObserver` и явный
`Clock` для телеметрии. Каждый экземпляр координирует только принятые им задачи.

### Владение рабочим пулом и завершение

Передавайте выделенный асинхронный `ExecutorService`, чей `execute` либо
передаёт задачу на выполнение, либо выбрасывает `RejectedExecutionException`.
Стандартный фиксированный пул из примера подходит. Caller-runs, прямое
выполнение, молчаливое отбрасывание и discard-oldest не поддерживаются.
Библиотека не может надёжно распознать произвольный executor, нарушающий это
предусловие.

Keyed executor получает исключительное владение жизненным циклом рабочего
пула. При штатной работе не делите пул с другим кодом, не перенастраивайте
и не останавливайте его отдельно. Остановите производителей задач, вызовите
`shutdown()` или `close()`, затем `awaitTermination(timeout)` из потока вне
этого пула. Оба метода закрытия начинают завершение без ожидания.
`awaitTermination` сам не начинает закрытие и не отменяет задачи по таймауту.

Пример сохраняет ссылку на пул только для аварийной очистки, если штатное
завершение не удалось или было прервано. Принудительная остановка может потерять
принятые задачи и не гарантирует остановки кода, игнорирующего прерывание.
Сервис должен определить ограничения времени своих операций, правила работы
с прерыванием и восстановление из сохранённого состояния.

## Отказы и ошибки

Проверяйте результат каждого `submit`. Приём подтверждает помещение задачи
в память, а не успешное выполнение или сохранность. Сбой процесса может
потерять всю очередь.

| Исход | Поведение executor | Ответственность сервиса |
|---|---|---|
| Очередь ожидания ключа заполнена или приём закрыт | Возвращает `REJECTED`; вызывает `observer.rejected` | Сохранить работу и запланировать последующую сверку; не создавать немедленный цикл повторов |
| Первая передача рабочему пулу выбросила `RejectedExecutionException` | Возвращает `REJECTED`; вызывает `dispatchRejected`, без `rejected`; удаляет очередь ключа | Учесть число отброшенных задач и восстановить работу из сохранённого состояния |
| Рабочий пул отказал при передаче следующей задачи | Отбрасывает оставшиеся задачи этой очереди; уведомляет через `dispatchRejected` | Статус ранее принятой задачи не обновляется; нужна последующая сверка |
| Задача вернулась штатно | Вызывает `completed`, затем продвигает очередь | Определить, завершилась ли сама бизнес-операция успешно |
| Задача выбросила `RuntimeException` | Вызывает `failed` с исходной ошибкой, затем продвигает очередь | Сохранять состояние бизнес-ошибки и повторов в обработке ошибок самой задачи |
| Задача выбросила `Error` | Ошибка выходит из рабочего runnable; продвижение очереди выполняется в `finally`; `failed` не вызывается | Применить правила обработки фатальных ошибок сервиса |

Методы observer выполняются синхронно в вызывающем или рабочем потоке вне
блокировки состояния очередей. Несколько ключей и вызывающих потоков могут
обращаться к observer одновременно. Реализуйте быстрые потокобезопасные
callbacks. Их runtime exceptions подавляются; `Error` передаётся дальше.
Используйте observer для телеметрии; восстановление бизнес-операций должно
работать даже при ошибке callback. Встроенного логгирования ошибок нет.

## Чтение snapshots

Snapshots executor неизменяемы и отсортированы по тексту ключа. `queuedDepth`
не включает переданную рабочему пулу задачу. `running=true` означает факт
передачи; задача ещё может ждать внутри очереди пула. `oldestAge` отсчитывается
от передачи самой старой незавершённой задачи и включает время ожидания.
Используются переданные настенные часы: изменение времени влияет на значение,
а отрицательный возраст приводится к нулю.

Snapshot guard считает ключи с выполняющимися или ожидающими вызовами.
Вложенное выполнение одного ключа учитывается в `executing` один раз и не
попадает в ожидающих callers. Общие счётчики приблизительны при одновременном
входе и выходе. Когда все вызывающие участки покинули ключ, его состояние
удаляется.

Эти представления нужны для health и диагностики. Не принимайте по snapshot
решение о возможности приёма, завершении операции или свободной блокировке.
`WorkAdmission.queuedDepth` также отражает только момент решения о приёме.

## Некорректные входные данные

- `WorkKey` отклоняет null, пустую и состоящую из пробелов строку через
  `IllegalArgumentException`; принятый текст не обрезается и не нормализуется.
- Null вместо ключа, работы, рабочего пула, observer или часов вызывает
  `NullPointerException` на соответствующей границе API.
- Отрицательный лимит очереди или таймаут завершения вызывает
  `IllegalArgumentException`; null вместо таймаута — `NullPointerException`.
  Ожидание может выбросить `InterruptedException`. Используйте обычные
  ограниченные интервалы, представимые в миллисекундах; переполнение при
  преобразовании может вызвать `ArithmeticException`.
- Records для admission и snapshots отклоняют отрицательные счётчики. Списки
  snapshot копируются; null-элементы отклоняются. Отрицательный
  `KeyedWorkSnapshot.oldestAge` приводится к нулю.

## Источники и проверки

- [Справочник модуля](../../../../platform/platform-concurrency/README.md) и
  [исходники Javadoc пакета](../../../../platform/platform-concurrency/src/main/java/com/iocextractor/platform/concurrent/).
- [Реализация executor](../../../../platform/platform-concurrency/src/main/java/com/iocextractor/platform/concurrent/BoundedKeyedSerialExecutor.java)
  и [реализация guard](../../../../platform/platform-concurrency/src/main/java/com/iocextractor/platform/concurrent/SynchronousKeyedExecutionGuard.java).
- [Тесты executor](../../../../platform/platform-concurrency/src/test/java/com/iocextractor/platform/concurrent/BoundedKeyedSerialExecutorTest.java),
  [тесты guard](../../../../platform/platform-concurrency/src/test/java/com/iocextractor/platform/concurrent/SynchronousKeyedExecutionGuardTest.java)
  и [тесты ключа](../../../../platform/platform-concurrency/src/test/java/com/iocextractor/platform/concurrent/WorkKeyTest.java)
  проверяют порядок, исключение, повторный вход, приём, телеметрию, ошибки и очистку.
- [Отдельный потребитель](../../../../tools/fixtures/library-consumer/README.md)
  компилирует и выполняет оба примера выше на разрешённом JAR. Дополнительно
  [publication-контракты](../../../../tools/tests/library-publication-test.py)
  требуют одинаковые Java-блоки и Maven-зависимость в обеих языковых версиях.
- [ADR 0028](../../../ADR/0028-pilot-library-publication.md) и
  [ADR 0029](../../../ADR/0029-release-branch-library-publication.md) определяют
  публикацию и совместимость. [Координация событий IOC](../../../dev/event-coordination.md)
  объясняет принадлежащее сервису восстановление из сохранённого состояния.

`make test-module MODULE=platform/platform-concurrency` запускает тесты
библиотеки; `make library-publication-test` — проверку извлечения примеров
и совпадения языковых версий. Для отдельного потребителя упакованного JAR
используйте команды bundle/consumer из
[руководства публикации, EN](../../library-publication.md#local-packaging-qualification).
Эти проверки не подтверждают работу второго развёрнутого сервиса или другой
базовой версии JDK. При изменении описанного поведения обновляйте оба языка
и их примеры.
