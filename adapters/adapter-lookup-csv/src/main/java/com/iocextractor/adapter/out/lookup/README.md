# com.iocextractor.adapter.out.lookup

## Назначение

Выходной адаптер порта `LookupRepository`: проверки существования против текущего
«хранилища» (существующего CSV-артефакта) для дедупликации.

**Правило слоя:** реализует порт; шов под будущую замену на реальное хранилище
без изменения ядра.

## Структура

| Файл | Назначение |
|---|---|
| `CsvMaskLookupRepository.java` | Загружает колонку `mask` для O(1)-проверок |
| `CsvArtifactLookupRepository.java` | Artifact-aware lookup для masks + hashes; `maxId()` сохраняет совместимость с mask lookup |

## Заметки

`CsvMaskLookupRepository` оставлен как узкий legacy adapter. Основной wiring
использует `CsvArtifactLookupRepository`: network IOC проверяются по `mask`, file
IOC — по `hash_md5`/`hash_sha1`/`hash_sha256`. Отсутствующий файл = пустое
хранилище.
