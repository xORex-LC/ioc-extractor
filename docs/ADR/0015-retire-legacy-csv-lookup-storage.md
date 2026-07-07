# 0015 — Вывод legacy CSV lookup/storage-режима

## Статус

**Принято, реализуется фазами.** Решение фиксирует вывод из эксплуатации
runtime lookup/storage через CSV после перехода dataframe truth на SQLite/JDBC.
Рабочий план и найденные риски ведутся в
[worknote/csv-lookup-retirement.md](../worknote/csv-lookup-retirement.md).

## Контекст

Ранние решения держали CSV-файлы не только как выходные артефакты, но и как
lookup/storage reference: pipeline мог отбрасывать индикаторы через
`LookupRepository.contains(...)`, а `maxId(...)` продолжал последовательность id
из существующих CSV. После ING-4 и ADR 0012 целевой контур изменился:
canonical business data живут в SQLite/JDBC, а `dataframe/*_generated.csv` —
это generated projection/export.

В коде при этом остался legacy fallback:

- `adapter-lookup-csv` реализует `LookupRepository` поверх CSV;
- `AppConfig` выбирает CSV lookup/write branch для non-JDBC dataframe storage;
- `ioc.lookup.type/path/artifacts` выглядят как поддерживаемый runtime contract;
- docs/packaging всё ещё местами описывают hand-filled CSV как lookup reference.

Главный риск не сам лишний адаптер, а семантика `LookupRepository.contains`.
При JDBC-пути pipeline отбрасывает уже известный IOC до canonical repository.
Значит `JdbcCanonicalArtifactRepository` не получает повтор и не может обновить
`<artifact>_sources`, хотя именно repository владеет keep-first через
`row_key`/`ON CONFLICT(row_key) DO NOTHING` и provenance update.

## Решения

### 1. SQLite/JDBC dataframe storage — единственный production truth

Runtime CSV lookup/storage режим выводится из эксплуатации. CSV-файлы остаются
только projection/export surface, а не системой записи и не источником dedup.

Следствие: non-JDBC dataframe storage должен fail-fast до удаления старых
веток. Это закрывает окно, где CSV oneshot fallback остаётся достижимым, но уже
не имеет storage-level dedup после удаления `lookup.contains` из pipeline.

### 2. Storage-level dedup принадлежит canonical repository

`lookup.contains` больше не является application policy. Cross-run/storage dedup
делает canonical repository через durable identity:

- `row_key` — business identity;
- `ON CONFLICT(row_key) DO NOTHING` — keep-first public row;
- `<artifact>_sources` — provenance/occurrence accounting.

Pipeline-дедуп остаётся только within-batch оптимизацией по
`Indicator.dedupKey()`.

### 3. Batch-dedup остаётся осознанным контрактом

Внутрипакетные дубли не инкрементируют occurrences. Это не побочный эффект
cleanup, а явное решение: batch dedup продолжает защищать writer от повторов в
рамках одного extraction run. Если позже потребуется считать каждое появление в
одном source, это будет отдельным изменением семантики pipeline.

### 4. `LookupRepository` разделяется, а не переименовывается

`LookupRepository` смешивал две разные ответственности:

- existence check (`contains`) для storage dedup;
- id baseline (`maxId`) для `id.start: auto`.

`contains` удаляется вместе с storage-level lookup. `maxId` переезжает в узкий
порт, например `ArtifactIdBaseline.maxId(String artifactName)`, с JDBC-адаптером
без hard-coded artifact names. Артефакты без id-колонки не должны делать SQL
lookup baseline.

### 5. Id allocation не переносится в repository в этом cleanup

Текущий `IdGenerator` остаётся на первом cleanup-проходе. Возможные gaps при
duplicate conflicts считаются допустимыми для unique ascending id: БД не
увеличивает `MAX(id)` для конфликтной строки, межпрогонный baseline не ломается.

Gapless id или DB-owned id allocation — отдельное решение. Оно задевает старый
контракт `ascending|descending` и не должно смешиваться с retirement CSV lookup.

### 6. Seed CSV — отдельный decision gate

Hand-filled CSV больше не работают как runtime lookup в JDBC-режиме. Поэтому:

- если requirement умер, docs/packaging очищаются, а
  `JdbcLegacyArtifactImporter` удаляется как мёртвый migration helper;
- если requirement жив, нужен явный import path (`ioc import` или startup/
  migration task) в canonical DB, но не скрытый runtime CSV lookup.

## Следствия

- `adapter-lookup-csv` будет удалён из Maven reactor и dependency graph.
- `CsvIocSink` как прямой writer legacy non-JDBC режима будет удалён; модуль
  `adapter-sink-csv` остаётся, потому что владеет CSV projection, export slice
  writers, retention store и completed-slice catalog.
- `ioc.lookup.type/path/artifacts` удаляются из живого config. Для старого
  внешнего YAML нужен временный tombstone/fail-fast, особенно для
  `lookup.deduplicate`, который переезжает в новый batch-dedup config key.
- Документация и packaging перестают называть hand-filled CSV runtime lookup
  reference.
- ADR 0012 и ING-4 остаются источником модели "SQLite truth + generated CSV
  projection"; этот ADR только снимает оставшийся legacy режим.

## Отклонённые варианты

### Оставить CSV lookup как fallback

Отклонено: fallback поддерживает вторую storage-модель, которая уже не является
production truth, и скрывает дефект provenance в JDBC-пути. Дальнейшая поддержка
увеличивает тестовую матрицу без целевой пользы.

### Сразу перенести id allocation внутрь repository

Отклонено для текущего cleanup: технически это может уменьшить id gaps, но
затрагивает `ascending|descending`, explicit id и legacy import semantics.
Это отдельный дизайн, а не обязательное условие удаления CSV lookup.

### Вернуть storage dedup под новым именем

Отклонено: это сохранило бы главный дефект — повтор не дошёл бы до canonical
repository и provenance не обновился бы. Dedup storage-level должен быть
свойством durable write, а не pre-filter в pipeline.

## Открытые вопросы

1. Нужен ли операторский seed import ручных списков в canonical DB, или ручные
   CSV окончательно уходят из runtime story?
2. Нужен ли в будущем gapless id / DB-owned id allocation, или unique ascending
   id с gaps является достаточным контрактом?
3. Когда CFG-4 strict binding удалит временные tombstone-поля для старых
   `ioc.lookup.*` ключей?
