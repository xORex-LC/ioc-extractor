> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0043-configurable-csv-output-format.md).

<a id="adr-0043-configurable-csv-record-endings-and-independent-quoting"></a>
# ADR 0043: Настраиваемые окончания CSV-записей и независимое quoting

- Статус: принято
- Дата: 2026-10-14
- Scope: изменяемые CSV-проекции, immutable export format и совместимость manifest.

<a id="context"></a>
## Контекст

CSV output фиксирует CRLF и заключает в кавычки каждое ненулевое поле.
Потребителям также нужны LF и независимые политики для column names и data.
Строгое требование отсутствия кавычек нельзя реализовать молчаливым escaping
delimiters или выдачей повреждённых records. Изменения формата должны
инвалидировать export pre-gate и оставаться явными в сохранённых recovery evidence.

<a id="decision"></a>
## Решение

Добавить `record-separator`, `header-quoting` и `value-quoting` в `ioc.sink.csv`.
Использовать закрытые LF/CRLF и ALL/MINIMAL/NONE policies. Сохранить defaults
CRLF/ALL/ALL и точный существующий v1 output. Оба CSV writers используют один
adapter formatter. Mutable projections сохраняют charset replacement для
ALL/ALL; MINIMAL или NONE требуют lossless encoding, чтобы replacement не мог
внести CSV syntax без кавычек. Immutable slices во всех режимах продолжают
использовать strict encoding.

ALL заключает в кавычки non-null fields, MINIMAL — когда нужно, а NONE отклоняет
cells, требующие quoting или escaping. Null marker без кавычек остаётся
зарезервированным для отсутствующего значения. MINIMAL заключает в кавычки
literal text, равный этому marker; NONE отклоняет такие данные вместо молчаливой
потери различия. Null markers, содержащие CSV syntax, недопустимы в конфигурации.

Сохранить legacy manifests и fingerprints, когда layout не меняется.
Non-legacy layouts используют manifest v2 со всеми тремя явными policies и
включают их в plan fingerprint. Строго декодировать обе версии; никогда не
выводить layout старого slice из текущей конфигурации. Это расширяет output
wire ADR 0012 без изменения canonical или import authority.

<a id="consequences"></a>
## Последствия

Не нужны DB migration или изменение input dialect. До включения v2 операторы
должны проверить поддержку consumer. Failed formatting сохраняет установленную
projection и никогда не публикует неполный slice, но не может отменить уже
завершённую canonical transaction. LF описывает record separators, а не
нормализацию cell content. CSV quoting не обеспечивает защиту от spreadsheet formulas.

Конфигурация, ambiguity rules, tests и recovery behavior описаны в
[формате CSV output](../../dev/csv-output-format.md).
