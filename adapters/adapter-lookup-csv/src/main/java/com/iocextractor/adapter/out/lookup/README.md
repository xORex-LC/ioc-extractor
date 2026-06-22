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
| `CsvArtifactLookupRepository.java` | Artifact-aware lookup для masks + ip_list + hashes; `maxId(artifactName)` даёт per-artifact id baseline |

## Заметки

`CsvMaskLookupRepository` оставлен как узкий legacy adapter. Основной wiring
использует `CsvArtifactLookupRepository`: domain/URL/non-bare-IP IOC проверяются
по `mask`, bare IP — по `ip`, file IOC — по `hash_md5`/`hash_sha1`/
`hash_sha256`. Отсутствующий файл = пустое хранилище. Для `id.start: auto`
репозиторий возвращает max id по имени артефакта, сохраняя общий `maxId()` как
fallback для старого контракта.
