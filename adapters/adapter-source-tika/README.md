# adapters/adapter-source-tika

## Назначение

Outbound source adapter implementing `SourceReader` with Apache Tika.

**Правило слоя:** contains document parsing details only; application sees the
`SourceReader` port.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/out/source/` | Tika-backed source reader; typed `SOURCE.READ_FAILED` / `SOURCE.UNSUPPORTED_FORMAT` boundary |
| `src/test/java/com/iocextractor/adapter/out/source/` | Контракты charset, diagnostics и извлечения текста из PDF/DOCX/XLSX |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-platform-diagnostics`,
`ioc-platform-observability`, Tika, SLF4J API.

**Не импортируется:** bootstrap and other adapters.

Версия Tika задаётся только parent `dependencyManagement`; текущая baseline —
`3.3.1`. Транзитивные POI/PDFBox остаются деталями этого адаптера и не
используются тестами или application-кодом напрямую.
