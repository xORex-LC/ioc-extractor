> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0033-bounded-semantic-reuse-during-import-staging.md).

<a id="adr-0033-bounded-semantic-reuse-during-import-staging"></a>
# ADR 0033: Ограниченное переиспользование семантики при import staging

- Статус: принято
- Дата: 2026-10-03
- Заменяет: admission policy без eviction и row-only import scope из
  [ADR 0032](../0032-invocation-owned-ioc-semantic-reuse.md)

<a id="context"></a>
## Контекст

Массовая обработка URL-to-host исчерпывает first-admission caches различными
исходными URL. Более поздние соседние повторы и горячие производные host
под новым source затем постоянно дают miss. Row-local import scopes не могут
переиспользовать успешные вычисления между CSV participants. Ни одно из этих
ограничений не оправдывает пропуск mapping или staging rows.

<a id="decision"></a>
## Решение

Сохранить semantic state, принадлежащий вызову и одному потоку, с тем же полным
classification key и identity закреплённой policy. Разделить существующий общий
budget 256 entries / 1 MiB учтённого объёма поровну между classification и
успешным host derivation. Каждая partition вытесняет least-recently-used entries
при admission. Исходная и производная classification разделяют classification
partition: часто используемые производные host остаются горячими, холодные
исходные URL могут заменяться. При явно нечётных limits остаток получает
classification. Ограничение 64 KiB на entry сохраняется; entry, превышающий
любой из двух ceilings, обходит cache без вытеснения entries. Не кешируются
failure, row, diagnostic, clock, position или canonical key.

Application staging открывает одну processed preparation session после
recognition и проверки contract pin. Через тот же preparer она отображает
каждую row и выполняет source-authority checks, затем закрывает session до
sealing workspace. Ошибки read, mapping и workspace append также её закрывают.
Каждая restaging attempt открывает новую session. As-is imports обходят
processing sessions; receipt-only recovery и reuse уже sealed stage никогда
не входят в staging preparation.

Выбранный router preparer предоставляет ограниченную semantic session.
Остальные preparers используют stateless forwarding adaptation, сохраняющую
и preparation, и source-authority behavior. Прямая single-row preparation
остаётся поддерживаемой и владеет row-local scope. Advisory validation
сохраняет существующий row-local path.

<a id="consequences"></a>
## Последствия

Общие admission ceilings не увеличиваются. Eviction добавляет ограниченные
metadata и insertion work на unique streams, поэтому repeated, unique и
host-collapse workloads необходимо измерять независимо. Charge — консервативный
учёт, а не точный JVM heap ceiling. Каждая concurrent attempt владеет
собственным allowance.

Import participants, COALESCE, canonical receipts, резервирование ID, failure
policy и recovery authority не меняются. Нет external cache, новой dependency,
operator configuration или storage migration. Caches остаются оптимизацией:
misses и eviction всегда возвращаются к тому же semantic computation.
