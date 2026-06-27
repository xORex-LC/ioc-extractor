# src/main/java/com/iocextractor/diagnostics

## Назначение

Пакет содержит framework-free ядро диагностики обработки данных: value object
`Diagnostic`, стабильные коды, severity/category, result/notification/policy
контракты и порты renderer/sink.

**Правило слоя:** пакет не импортирует Spring, SLF4J/Logback, adapter или
bootstrap. Producer-код может создавать диагностики, но не должен форматировать
сообщения или решать, куда они будут доставлены.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `Diagnostic.java` | Неизменяемые диагностические данные |
| `DiagnosticBuilder.java` | Builder с управляемым `Clock` |
| `DiagnosticFactory.java` | Фабрика builders с инжектируемым `Clock` |
| `DiagnosticCode.java` | Контракт стабильного кода диагностики |
| `DiagnosticException.java` | Исключение, переносящее fatal diagnostic |
| `DiagnosticSeverity.java` | Уровни severity |
| `DiagnosticCategory.java` | Категории диагностики, включая formation-only `EXPORT` |
| `codes/` | Стартовые enum-каталоги кодов |
| `catalog/` | Агрегация и проверяемые entries каталога |
| `result/` | `Result`, `Notification`, `FailurePolicy` |
| `render/` | Рендеринг диагностик в текст |
| `sink/` | Порты и базовые sinks |

## Зависимости

**Зависит от:** JDK и `common` для `IocExtractorException`.

**Не импортируется:** adapter/bootstrap реализациями напрямую в обход портов
там, где нужна доставка диагностик; logging bridge будет отдельным модулем.

## Заметки

`LoggingDiagnosticSink`, ECS/MDC и привязка к фоновой observability-подсистеме
не живут здесь и реализуются отдельным этапом.
