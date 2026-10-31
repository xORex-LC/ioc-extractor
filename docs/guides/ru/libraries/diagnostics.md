# Результаты и диагностики с библиотекой diagnostics

[English version](../../libraries/diagnostics.md) · [Каталог библиотек](README.md)

`ioc-platform-diagnostics-api` подходит, когда операция должна вернуть значение
вместе со структурированными проблемами, принять решение об остановке или
сохранить небольшую выборку диагностик с точным подсчётом всех событий.
Например, коллектор может сообщать о некорректных строках ленты, не связывая
обработку с логгером или Spring.

Библиотека предоставляет значения, политики, накопители и синхронную доставку.
Коды, сообщения, маскирование, исключения, точку проверки перед транзакцией
и восстановление определяет сервис. Встроенных каталогов, logging provider,
Spring starter, хранилища аудита и формата обмена нет.

## Подключение и версия

Проверенный базовый вариант — Java 21. Сторонних production-зависимостей нет,
а POM потребителя не требует родительского POM реактора:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-diagnostics-api</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

Выпуск опубликован в Maven Central и GitHub Packages. Стандартный Central
не требует дополнительных репозиториев или credentials. Для GitHub Packages
нужны авторизованные Maven settings; подключение описано в
[процедуре публикации, EN](../../library-publication.md#consumption).
Реактор остаётся на `0.3.0-SNAPSHOT`. Java-реализация, сверенная при подготовке
руководства, совпадает с `ioc-platform-diagnostics-api-v0.3.0-rc.1`.
[Запись выпуска](../../../../.github/release-notes/ru/ioc-platform-diagnostics-api-0.3.0-rc.1.md)
сохраняет исходный коммит, подписанные хеши и исходные проверки репозиториев.

## Предоставляемые инструменты

Восемнадцать публичных типов распределены по трём пакетам под
`com.iocextractor.platform.diagnostics`:

| Пакет / тип | Назначение |
|---|---|
| `DiagnosticCode` | Принадлежащие сервису стабильный ID, severity по умолчанию и impact |
| `DiagnosticSeverity` | Уровни TRACE, DEBUG, INFO, WARN, ERROR, FATAL; `isErrorOrWorse()` определяет ERROR/FATAL |
| `DiagnosticImpact` | Принадлежность к ELEMENT, RUN или OPERATION независимо от severity |
| `DiagnosticFactory` | Создание builders с явно переданными часами |
| `DiagnosticBuilder` | Установка severity, контекста и причины; `build()` фиксирует время события |
| `Diagnostic` | Событие с кодом, фактической severity, контекстом, необязательной причиной и временем |
| `result.Result<T>` | Допускающее null значение, скопированные диагностики, преобразование и проверки severity |
| `result.Notification` | Неограниченный накопитель задачи: `add`, `addAll`, `diagnostics`, `toResult` и проверки severity |
| `result.FailurePolicy` | Чистая стратегия `evaluate(Notification)`; встроенные fail-fast и collect-and-continue |
| `result.FailureDecision` | Продолжение или остановка с диагностикой-причиной |
| `result.BoundedDiagnosticCollector` | Локальная выборка этапа с точными счётчиками |
| `result.BoundedNotification` | Бюджет подробностей запуска, объединение, callback доставки и маркер для отображения |
| `result.DiagnosticBatch` | Неизменяемая выборка реальных событий и точная сводка без маркера отображения |
| `result.DiagnosticSummary` | Точные total, suppressed, счётчики severity и арифметика с проверкой переполнения |
| `sink.DiagnosticSink` | Порт синхронного `emit(Diagnostic)` |
| `sink.NoopDiagnosticSink` | Отбрасывание непустого входа |
| `sink.CollectingDiagnosticSink` | Неограниченный список для тестов или сборки отчёта |
| `sink.ResilientDiagnosticSink` | Изоляция runtime failures основного получателя и fallback с callback сервиса |

## Определение кодов, создание результата и проверка политики

Поместите три Java-примера этой страницы в `src/main/java/` одного Maven-проекта
с зависимостью выше и `maven.compiler.release=21`. Последующие примеры используют
enum кодов и часы из первого. Каждый main-класс можно запустить из IDE или
через отдельного потребителя, описанного ниже.

Сохраните первый пример в `DiagnosticResultExample.java`:

```java
import com.iocextractor.platform.diagnostics.DiagnosticCode;
import com.iocextractor.platform.diagnostics.DiagnosticFactory;
import com.iocextractor.platform.diagnostics.DiagnosticImpact;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import com.iocextractor.platform.diagnostics.result.FailurePolicy;
import com.iocextractor.platform.diagnostics.result.Notification;
import com.iocextractor.platform.diagnostics.result.Result;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

public final class DiagnosticResultExample {
    enum ImportCode implements DiagnosticCode {
        BAD_ROW, SUPPRESSED;

        @Override public String id() { return "import/" + name().toLowerCase(Locale.ROOT); }
        @Override public DiagnosticSeverity defaultSeverity() { return DiagnosticSeverity.WARN; }
        @Override public DiagnosticImpact impact() { return DiagnosticImpact.ELEMENT; }
    }

    static DiagnosticFactory factory() {
        return new DiagnosticFactory(
                Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC));
    }

    public static void main(String[] args) {
        var factory = factory();
        var warning = factory.create(ImportCode.BAD_ROW).with("row", 7).build();
        Result<String> result = Result.success("accepted")
                .withDiagnostic(warning).map(value -> value.toUpperCase(Locale.ROOT));
        var notification = new Notification().addAll(result.diagnostics());
        if (FailurePolicy.failFast().evaluate(notification).shouldStop()) {
            throw new IllegalStateException("A warning must allow continuation");
        }

        notification.add(factory.create(ImportCode.BAD_ROW)
                .severity(DiagnosticSeverity.ERROR).with("row", 8).build());
        var failFast = FailurePolicy.failFast().evaluate(notification);
        var collect = FailurePolicy.collectAndContinue().evaluate(notification);
        if (!"ACCEPTED".equals(result.value()) || !failFast.shouldStop() || collect.shouldStop()) {
            throw new IllegalStateException("Result or policy behavior differs");
        }
        System.out.println("Value: " + result.value() + "; fail-fast: stop; collect-and-continue: continue");
    }
}
```

Ожидаемый вывод: `Value: ACCEPTED; fail-fast: stop; collect-and-continue: continue`.
В production используйте часы сервиса, например `Clock.systemUTC()`.
`Diagnostic.builder(code, clock)` позволяет начать построение напрямую.
Builder принимает `severity`, `with`, `context` и `cause`; если причины нет,
не вызывайте `cause`. Повторная запись того же ключа контекста заменяет значение.

### Коды, severity и принадлежность

ID кода должен быть непустым, не состоять из пробелов и быть уникальным
в каталоге сервиса. Формат `GROUP.CODE`
не обязателен. Метаданные, равенство и hash code кода должны оставаться
стабильными. Сравниваются сами объекты кодов по их равенству, а не строки ID.
Равенство диагностик включает код, контекст и **фактическую severity**;
время и причина не участвуют. Повторные равные события считаются отдельно.

| Severity | `failFast()` | `collectAndContinue()` |
|---|---|---|
| TRACE / DEBUG / INFO / WARN | Продолжение | Продолжение |
| ERROR | Остановка | Продолжение |
| FATAL | Остановка | Остановка |

Политика выбирает первую подходящую диагностику из переданного списка.
Её вычисление не отменяет работу и не выбрасывает исключение продукта.
Сервис обязан учесть `shouldStop()` перед записью; при остановке `diagnostic()`
содержит причину, при продолжении optional пуст. Собственная политика должна
возвращать непустое решение без I/O, изменения накопителя и выбрасывания ошибок.
Библиотека не контролирует чистоту такой реализации и не исправляет её нарушения.

Impact обозначает затронутую область. ELEMENT и RUN расходуют бюджет
подробностей; OPERATION не расходует. Сам impact не разрешает пропуск строки,
запись или повтор операции. Если собственной политике нужны все коды, передайте
ей полные наблюдения до сокращения выборки: потерянные причины не восстановить.

### Значения и snapshots

`Result.success(null)` допустим и сам по себе не означает ошибку. `map` требует
непустой mapper, пропускает его для null-значения, сохраняет порядок диагностик
и передаёт ошибки mapper без изменений. `withDiagnostic` возвращает новый
результат; `Notification` изменяет собственную коллекцию. `toResult(value)`
делает её снимок. `hasErrors` проверяет ERROR/FATAL, `hasFatal` — FATAL.
Ни результат, ни notification не выполняют доставку автоматически.

Карты контекста и списки диагностик копируются, но значения контекста, объекты
кодов, значение результата и Throwable остаются общими ссылками. Не меняйте
их после публикации. `Diagnostic.toString()` и просмотр batch могут раскрывать
сырой контекст; перед логированием или возвратом применяйте правила отображения
и маскирования сервиса.

## Ограничение подробностей этапа и объединение в запуск

Сохраните `DiagnosticBudgetExample.java` рядом с первым примером:

```java
import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import com.iocextractor.platform.diagnostics.result.BoundedDiagnosticCollector;
import com.iocextractor.platform.diagnostics.result.BoundedNotification;
import com.iocextractor.platform.diagnostics.result.FailurePolicy;
import com.iocextractor.platform.diagnostics.result.Notification;
import com.iocextractor.platform.diagnostics.sink.CollectingDiagnosticSink;

import java.util.List;
import java.util.Map;

public final class DiagnosticBudgetExample {
    public static void main(String[] args) {
        var factory = DiagnosticResultExample.factory();
        var code = DiagnosticResultExample.ImportCode.BAD_ROW;
        var markerCode = DiagnosticResultExample.ImportCode.SUPPRESSED;
        var stage = new BoundedDiagnosticCollector(1, markerCode);
        for (var severity : List.of(DiagnosticSeverity.WARN, DiagnosticSeverity.WARN,
                DiagnosticSeverity.ERROR, DiagnosticSeverity.FATAL)) {
            stage.add(factory.create(code).severity(severity).build());
        }
        var batch = stage.batch();
        var delivered = new CollectingDiagnosticSink();
        var run = new BoundedNotification(1, factory, markerCode);
        run.addAll(batch, delivered::emit);
        var summary = run.summary();
        var marker = run.diagnostics().getLast();
        var decision = FailurePolicy.collectAndContinue()
                .evaluate(new Notification().addAll(run.batch().retained()));

        if (batch.retained().size() != 3 || batch.summary().suppressed() != 1
                || summary.total() != 4 || summary.suppressed() != 3
                || delivered.diagnostics().size() != 3 || !decision.shouldStop()
                || !run.batch().retained().stream().map(Diagnostic::severity).toList()
                    .equals(List.of(DiagnosticSeverity.FATAL))
                || marker.code() != markerCode || marker.severity() != DiagnosticSeverity.ERROR
                || !marker.context().equals(Map.of("limit", 1, "suppressedCount", 3L,
                    "suppressedBySeverity", Map.of("WARN", 2L, "ERROR", 1L)))) {
            throw new IllegalStateException("Bounded counts, detail or marker differs");
        }
        System.out.printf("Observed: %d; retained: %d; suppressed: %d; delivered: %d%n",
                summary.total(), run.batch().retained().size(), summary.suppressed(),
                delivered.diagnostics().size());
    }
}
```

Ожидаемый вывод: `Observed: 4; retained: 1; suppressed: 3; delivered: 3`.

Для входа WARN A, WARN B, ERROR C, FATAL D и положительного бюджета N=1:

| Представление | Реальные сохранённые подробности | Всего наблюдалось | Подавлено |
|---|---|---:|---:|
| `batch()` этапа | A, C, D в порядке поступления | 4 | 1 |
| `batch()` запуска | D | 4 | 3 |
| `diagnostics()` для отображения | D, затем синтетический маркер уровня ERROR | 4 | 3 |

Этап сохраняет первые N учитываемых событий, а при необходимости — первое
событие, требующее остановки, и первый FATAL: до N+2 событий. Запуск сохраняет
не более N учитываемых событий: первый ERROR/FATAL может заменить самую раннюю
позицию с минимальной severity. Оба накопителя сохраняют **каждый OPERATION**
вне бюджета. Поэтому память всё ещё может расти из-за OPERATION, больших
значений контекста, неограниченного `Notification` или `CollectingDiagnosticSink`.

Замена сохраняет позицию вытесненного элемента в списке. При бюджете 2 вход
WARN A, ERROR B, FATAL C оставляет `[C, B]`. Fail-fast выберет C, хотя B поступил
раньше. Сокращение сохраняет сигналы встроенных политик остановки, а не
хронологию выборки или самую раннюю исходную причину ошибки.

Одинаковые бюджеты этапа и запуска сохраняют выборку прямого накопления.
Меньший бюджет этапа может удалить подробность, которую больший бюджет запуска
сохранил бы: этап с бюджетом 1 для A, B, C оставляет A и C, и запуск с бюджетом
3 уже не восстановит B. Счётчики остаются точными; выборка и suppressed могут
отличаться от прямого накопления. Доставка идёт в порядке сохранённых элементов
входного batch.

`offer` возвращает, сохранено ли новое событие в данный момент. `add` не
возвращает этот Boolean-результат. `addAll(batch, delivery)` вызывает доставку
для каждого вновь сохранённого события, включая замены. Позднейшая замена не
отменяет предыдущую доставку: бюджет подробностей не ограничивает общее число
вызовов доставки. `addAll(batch)` объединяет без доставки.

### Batch, счётчики и зарезервированный маркер

Объединяйте снимки `batch()`, содержащие реальные события и точные счётчики.
`diagnostics()` предназначен для отображения и добавляет необязательный
синтетический маркер. Не передавайте его обратно как событие. Ограниченные
накопители отклоняют свой маркер по равенству кода до подсчёта. Во всём дереве
объединения используйте **один стабильный код маркера**: сводка не доказывает
происхождение пропущенных событий, а код валидации внутри batch не хранится.

Маркер содержит integer `limit`, long `suppressedCount` и неизменяемую карту
`suppressedBySeverity` с именами severity и long-счётчиками. Его фактическая
severity — максимальная среди пропущенных событий. Чтение snapshot ничего
не доставляет и не меняет счётчики; время маркера может меняться между чтениями.

`DiagnosticBatch.of(retained, summary, markerCode)` копирует и проверяет
подробности: размер равен total минус suppressed, представленные severity
не превышают наблюдавшиеся счётчики, наблюдавшийся ERROR/FATAL требует
сохранённого сигнала остановки, а наблюдавшийся FATAL — сохранённого FATAL.
Зарезервированный маркер исключается. Равенство batch использует подробности
и summary без конфигурации маркера.

`DiagnosticSummary.plus` складывает независимые счётчики; `since` вычисляет
монотонную разницу с предыдущим снимком; `plusDiagnostics` добавляет реальные
события, не меняя suppressed. `count` возвращает ноль для отсутствующей severity;
`hasErrors()` проверяет все наблюдавшиеся ERROR/FATAL, включая пропущенные подробности.
Значения карты дают в сумме total; явные нулевые записи допустимы и участвуют
в равенстве record. `suppressedBySeverity()` у batch исключает нулевые записи.
Арифметика проверяет переполнение long; синтетические маркеры не учитываются.

## Изоляция ошибок при доставке диагностик

Сохраните `DiagnosticDeliveryExample.java` рядом с первым примером:

```java
import com.iocextractor.platform.diagnostics.sink.DiagnosticSink;
import com.iocextractor.platform.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.platform.diagnostics.sink.ResilientDiagnosticSink;

import java.util.ArrayList;

public final class DiagnosticDeliveryExample {
    public static void main(String[] args) {
        var warning = DiagnosticResultExample.factory()
                .create(DiagnosticResultExample.ImportCode.BAD_ROW).with("row", 7).build();
        var unavailable = new IllegalStateException("report unavailable");
        var failures = new ArrayList<RuntimeException>();
        DiagnosticSink sink = new ResilientDiagnosticSink(
                diagnostic -> { throw unavailable; },
                (diagnostic, failure) -> {
                    if (diagnostic != warning) {
                        throw new AssertionError("Fallback received a different occurrence");
                    }
                    failures.add(failure);
                });
        sink.emit(warning);
        NoopDiagnosticSink.INSTANCE.emit(warning);
        if (failures.size() != 1 || failures.getFirst() != unavailable) {
            throw new IllegalStateException("Delivery failure was not reported once");
        }
        System.out.println("Delivery failures: " + failures.size());
    }
}
```

Ожидаемый вывод: `Delivery failures: 1`.

Доставка выполняется синхронно в вызывающем потоке. Resilient wrapper вызывает
основного получателя один раз; при runtime failure вызывает fallback один раз
с исходной диагностикой и исключением. Runtime failures fallback подавляются
без рекурсии или повтора. `Error` выходит из любого callback.
Рабочего потока, таймаута, очереди и гарантии сохранной доставки нет. Ресурсы,
задержки, потокобезопасность и раскрытие данных определяет сервис.

### Частичные изменения при объединении

Незащищённый callback доставки может выбросить ошибку после того, как событие
уже посчитано и сохранено. Следующие сохранённые события и пропущенные счётчики
batch ещё не добавлены. Переполнение тоже сохраняет более ранние добавления.
Отката и идентичности повторной операции нет: повтор всего batch может ещё раз
посчитать или доставить принятый префикс. Оборачивайте потенциально ошибочную
доставку в `ResilientDiagnosticSink`, когда она не должна прерывать обработку.

## Владение потоками и некорректные данные

Builders, notifications, ограниченные накопители и collecting sinks изменяемы,
принадлежат одной задаче и не потокобезопасны. Передавайте неизменяемые batch
между задачами только при стабильных кодах, значениях контекста и причинах.
У factory нет изменяемого состояния, но общие часы должны быть потокобезопасны.
Потокобезопасность sink зависит от callbacks. Ни один из этих объектов не
владеет потоком или транзакцией.

- Null вместо обязательных аргументов, метаданных, ключей/значений контекста
  или элементов списка вызывает `NullPointerException`. Значение результата
  может быть null; отсутствие причины задаётся пропуском вызова, а не `cause(null)`.
- Пустые или пробельные ID кодов, неположительный бюджет, свой маркер среди реальных событий,
  несогласованные счётчики, некорректные разницы и скрытый сигнал остановки
  вызывают `IllegalArgumentException`.
- Переполнение счётчика вызывает `ArithmeticException`. Массовая операция
  может принять более ранние элементы перед ошибкой. Валидация проверяет
  арифметику и сохранённые сигналы, а не достоверность пропущенных наблюдений.

## Интеграция IOC, источники и поддержка

IOC добавляет категории `IocDiagnosticCode`, шаблоны сообщений, каталоги,
исключения, отображение и логирование в локальных модулях. У общего API нет
категории IOC. Для общего кода IOC renderer может использовать буквальный ID.
Импорты модели из `com.iocextractor.diagnostics` перенесены в
`com.iocextractor.platform.diagnostics`; алиасов старых пакетов нет.
Старый `Notification.throwIfRejected` заменяется явным вычислением политики
и исполнением решения в сервисе; маркер и fallback передаются явно.

- [Справочник модуля](../../../../platform/platform-diagnostics-api/README.md),
  [API значений](../../../../platform/platform-diagnostics-api/src/main/java/com/iocextractor/platform/diagnostics/),
  [результаты и накопление](../../../../platform/platform-diagnostics-api/src/main/java/com/iocextractor/platform/diagnostics/result/)
  и [доставка](../../../../platform/platform-diagnostics-api/src/main/java/com/iocextractor/platform/diagnostics/sink/)
  содержат реализацию и Javadoc публичных типов и пакетов.
- [Тесты модуля](../../../../platform/platform-diagnostics-api/src/test/java/com/iocextractor/platform/diagnostics/)
  проверяют идентичность, поверхностное копирование, политики, счётчики,
  замены, ошибки объединения, маркеры и доставку.
  [Отдельный потребитель](../../../../tools/fixtures/diagnostics-consumer/README.md)
  компилирует и выполняет все три примера выше на разрешённом JAR.
- [Publication-контракты](../../../../tools/tests/library-publication-test.py)
  проверяют извлечение примеров и совпадение Java/XML в EN/RU. Из корня
  репозитория запускайте `make test-module MODULE=platform/platform-diagnostics-api`,
  `make library-publication-test` и `make docs`.
- Для отдельного потребителя упакованного JAR используйте bundle/consumer из
  [руководства публикации, EN](../../library-publication.md#local-packaging-qualification).
  [ADR 0044](../../../ADR/0044-reusable-diagnostics-contract.md) определяет
  архитектуру и совместимость; [observability](../../../dev/observability.md)
  объясняет интеграцию IOC.

Продукт и библиотека сохраняют общую линейку версий. В 0.x изменение публичного
API или документированного поведения требует новой minor-версии и заметок
о миграции; patch сохраняет контракт. Реальный второй сервис, произвольные
политики по пропущенным подробностям и совместимость формата обмена или
хранения остаются вне проверки. При изменении поведения обновляйте оба языка
и примеры.
