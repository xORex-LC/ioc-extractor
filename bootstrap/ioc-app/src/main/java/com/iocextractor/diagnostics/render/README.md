# src/main/java/com/iocextractor/diagnostics/render

## Назначение

Пакет содержит порт рендеринга диагностик и минимальный renderer поверх
`DiagnosticCode.defaultMessageTemplate()`.

**Правило слоя:** renderer превращает diagnostic data в текст. Он не пишет в
логи/файлы и не решает control-flow.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `DiagnosticRenderer.java` | Порт рендеринга |
| `TemplateDiagnosticRenderer.java` | Простая подстановка `{key}` из context |

## Зависимости

**Зависит от:** `diagnostics` core.

**Не импортируется:** Spring `MessageSource`; `MessageCatalog` и i18n
откладываются до отдельного шага.
