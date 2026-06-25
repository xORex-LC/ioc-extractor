# com.iocextractor.application.port.out

## Назначение

Driven-порты (выходные): контракты, которые прикладное ядро требует от
инфраструктуры. Реализуются адаптерами в `adapter/out/**`.

**Правило слоя:** только интерфейсы; узкие, сфокусированные (ISP). Реализации не
видны ядру — внедряются через composition root.

## Структура

| Файл | Назначение |
|---|---|
| `SourceReader.java` | Извлечение текста из документа любого формата |
| `IocSink.java` | Один выходной артефакт; фильтрует принимаемые типы |
| `LookupRepository.java` | Проверки существования (дедуп) против «хранилища» |
| `ingest/` | Driven-порты source lifecycle и ledger |
| `artifact/` | Driven-порты canonical storage, projection, identity resolver и run-ledger |
