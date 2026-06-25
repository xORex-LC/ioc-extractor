# com.iocextractor.application.port.in

## Назначение

Driving-порты (входные): единственная точка входа прикладного ядра. На них
зависят входные адаптеры (CLI сегодня, REST завтра), не на сервис напрямую.

**Правило слоя:** только интерфейсы и DTO команды/результата; без зависимостей на
адаптеры и инфраструктуру.

## Структура

| Файл | Назначение |
|---|---|
| `ExtractIocsUseCase.java` | Порт сценария: `extract(ExtractionCommand)` |
| `ExtractionCommand.java` | Входной запрос: путь к источнику, флаг `dryRun` |
| `ExtractionResult.java` | Сводка прогона: извлечено / оставлено / по артефактам |
| `ingest/` | Driving-порты whole-file daemon ingest |
