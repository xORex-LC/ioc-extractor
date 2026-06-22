# com.iocextractor.application.port.in.aggregation

## Назначение

Driving-порт stage 11 aggregation. Bootstrap scheduler или будущий operator CLI
вызывает `AggregatePartitionsUseCase`, не завися от реализации сервиса.

## Структура

| Файл | Назначение |
|---|---|
| `AggregatePartitionsUseCase.java` | Запуск aggregation |
| `AggregationCommand.java` | Команда aggregation; пустой фильтр = все артефакты |
| `AggregationResult.java` | Summary: источники, партиции, строки, новые stable ids |

## Границы

Пакет содержит только интерфейс и DTO. Spring scheduling, CSV и health остаются
снаружи.
