> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0032-invocation-owned-ioc-semantic-reuse.md).

<a id="adr-0032-invocation-owned-ioc-semantic-reuse"></a>
# ADR 0032: Переиспользование IOC-семантики в области одного вызова

- Статус: принято
- Дата: 2026-10-03
- Связано: [ADR 0031](../0031-bounded-camel-preparation-runtime.md),
  [processing](../../dev/processing.md)

<a id="context"></a>
## Контекст

Выбранная обработка документов должна посещать каждое атрибутированное
occurrence до выбора final key. Повторные классификация и вывод host дороги,
но пропуск повторных наблюдений потерял бы source attribution, упорядоченные
позиции полей, mapper diagnostics и участников import receipt. MatchPolicy
принимает полный Indicator и не гарантирует зависимость только от value.

<a id="decision"></a>
## Решение

Application предоставляет отдельные порты DocumentProcessingPlan и
DocumentProcessingSession. Preparation stage владеет одной session, закрывает
её до возврата к failure checkpoint и также закрывает при исключении.
Существующие functional plans получают forwarding adaptation без cache.
Bootstrap связывает handle с чистым collaborator IndicatorProcessingSession
в ioc-processing; Camel не знает ключей cache и его ownership.

Classification entries используют полный Indicator (value, type, source label
и section) при фиксированном экземпляре policy. Исходная и производная
classification могут разделять entries только когда их classifier wrappers
ссылаются на тот же policy object. Операция с другой policy вычисляет результат,
не обращаясь к этому cache. Семантика policy должна оставаться стабильной
в течение вызова; никакой cache не переживает configuration/registry pin.

Host entries сохраняют успешные пары host value/type с ключом original
value/type. Их вычисляет существующий NetworkHostDeriver; каждое
переиспользование заново создаёт Indicator с текущим source. Ожидаемые ошибки
вывода и неожиданные исключения не кешируются. Positions, ordinal, preparers,
clock-based mapping, consumers, diagnostics, replies и prepared candidates
никогда не становятся semantic entries.

Оба cache имеют общие admission limits: 256 entries, 1 MiB учитываемого
retention и максимум 64 KiB на entry. Учёт включает key/result text по размеру
UTF-16, элементы списков и консервативные object allowances; общие references
учитываются повторно. Это admission budgets, а не гарантия точного размера
JVM heap. Исчерпание или слишком большие entries приводят к обычному
вычислению без eviction и изменения acceptance. Sessions привязаны к одному
потоку; нет global cache, ThreadLocal или новой cache dependency/operator setting.

Processed import владеет новой session внутри каждой logical row и закрывает
её до возврата assembly result. Переиспользование на всю delivery отложено:
для него потребовался бы явный staging-attempt handle через preparation и
source-authority validation. Receipt-only recovery не открывает processing
session. Каждая CSV row по-прежнему попадает в staging; COALESCE и его warnings
остаются ответственностью canonical import.

<a id="consequences"></a>
## Последствия

Retention кандидатов в двух существующих путях document reduction пропорционален
числу различных final keys. Semantic state ограничен admission на один вызов,
но input и diagnostics остаются пропорциональны input; concurrent sessions
умножают этот allowance. Legacy KEEP_FIRST preparation остаётся несгруппированной.

Калибровка с 8,000 observations, значениями длиной 2,000 characters и одним/четырьмя
callers заполняет size budget раньше entry budget; значения длиной 40,000 characters
обходят его. Повторяющийся fixture с 20 ключами использует 40 entries. Это
обосновывает консервативные начальные limits, а не утверждение о производительности
всего pipeline или customer resource budget. Такие утверждения принадлежат
regression tests и отдельным сравнениям приложения.
