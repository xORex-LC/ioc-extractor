# src/main/java/com/iocextractor/diagnostics/result

## Назначение

Пакет содержит минимальные result/notification/policy типы для накопления
диагностик без преждевременного падения pipeline.

**Правило слоя:** политика ошибок чисто вычисляет решение. Исключение
выбрасывается только на границе оркестрации через `Notification`.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `Result.java` | Значение + diagnostics |
| `Notification.java` | Mutable per-run/per-item collector |
| `BoundedNotification.java` | ELEMENT/RUN budget + unbounded OPERATION occurrences + suppression summary |
| `BoundedDiagnosticCollector.java` | Ограниченная stage-local выборка в порядке поступления + первые ERROR/FATAL |
| `DiagnosticBatch.java` | Immutable выборка и точные counts, включая подавленные детали |
| `DiagnosticSummary.java` | Immutable counts, stage delta и объединение outcome batches |
| `FailureDecision.java` | Результат оценки политики |
| `FailurePolicy.java` | Strategy fail-fast / collect-and-continue |

## Зависимости

**Зависит от:** `diagnostics` core.

**Не импортируется:** renderer/sink/logging; result-типы не доставляют и не
форматируют диагностики. `BoundedNotification` может передавать новые retained
occurrences через callback владельца доставки; ошибки callback не скрываются.
