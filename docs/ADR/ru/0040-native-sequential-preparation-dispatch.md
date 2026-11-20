> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0040-native-sequential-preparation-dispatch.md).

<a id="adr-0040-native-sequential-preparation-dispatch"></a>
# ADR 0040: Нативный последовательный dispatch подготовки

- Статус: принято
- Дата: 2026-10-10
- Scope: исполнение Camel preparation; уточняет dispatch mechanism из
  [ADR 0031](../0031-bounded-camel-preparation-runtime.md).

<a id="context"></a>
## Контекст

Допущенный selector уже вычисляет полное eligible subset, а runtime разрешает
required views каждой выбранной branch до вызова любого destination.
Routing локален и последователен. Multi-recipient execution добавлял один
dispatch exchange, учёт Recipient List, агрегацию replies и wrapper snapshots
вокруг уже выбранной работы. Документы и managed imports теперь читают
ограниченные private cursors, поэтому для изменения dispatch granularity
не нужно удерживать списки на весь документ.

Прямой вызов зарегистрированных processors не эквивалентен native execution:
существующие conformance tests требуют completion/failure callbacks view
UnitOfWork и отказа остановленных routes. Повторная реализация этих владельцев
во втором evaluator ослабила бы архитектуру или продублировала Camel lifecycle.

<a id="decision"></a>
## Решение

Сохранить один embedded Camel context, связанные endpoints, native consumers
и переиспользуемый ProducerTemplate. После полного selection и разрешения
required views отправлять каждую eligible branch в объявленном порядке через
этот template. Каждый вызов получает новый native exchange и UnitOfWork с тем
же immutable BranchInput. Добавлять typed replies в invocation-local list;
замораживать его на существующей границе PlanExecutionResult. Неожиданное
исключение останавливает исполнение. Ожидаемые filtered/unavailable replies
сохраняют объявленное значение и не останавливают остальные eligible branches.

Удалить сгенерированный dispatch endpoint, его compiled metadata, recipient
property, DispatchRequest и reply aggregation strategy. Единственная реализация
execution — native path; нет operator toggle, fallback engine, parallel fan-out
или общего batch UnitOfWork. Зарегистрированные operation/destination
processors никогда не вызываются напрямую application или custom evaluator.

Demand, short-circuit, FIRST/ALL/EXCLUSIVE, explicit view recovery, consumer
failure accounting, trace и MDC остаются у текущих владельцев. Каждое occurrence
по-прежнему вносит validation, настроенную preparation и provenance через
реальный cursor. Workspace reduction, ordering, identity, admission,
canonical atomicity, receipt recovery и projection не меняются.

<a id="evidence-and-limits"></a>
## Evidence и ограничения

Native prototype прошёл 55 существующих execution conformance tests и парные
Spring-проверки document/import output. Три чередующиеся whole-service пары по
100k occurrences прошли независимые проверки всех пяти canonical artifacts,
provenance, mutable projections и трёх immutable export profiles. Медиана
консервативного локального времени снизилась с 62.76 до 60.04 seconds; медиана
process CPU — с 54.46 до 52.20 seconds. Все парные time ratios улучшились,
но отдельные улучшения составили от 0.9% до 12.3%; это небольшая выгода
в конкретном scope, а не общее обещание throughput. Caller allocations для
повторяющихся документов снизились примерно на 9%; профиль различных domain
улучшился намного меньше.

Эти проверки всё ещё не проходят принятые local-time и five-second
writer-occupancy limits. Они не квалифицируют million-occurrence workload,
устойчивый mixed load, SMB или полную приёмку G6. Ни один target не ослабляется.
Неполная unique import series остаётся failure evidence после UTC clock
regression; её coordination follow-up — ING-16 в
[известных проблемах](../../KNOWN-ISSUES.md).

Production change удаляет неиспользуемые compiler/dispatch structures сверх
timing prototype. Финальные functional gates и проверка настоящего packaged
service квалифицируют эту очистку; timing ratios прототипа нельзя переименовывать
в ratios окончательной packaged implementation.

<a id="alternatives"></a>
## Альтернативы

- Прямое исполнение compiled processors: отклонено native lifecycle contracts.
- Shared-exchange batch processing: нельзя молча разделять completion, headers,
  properties или failure state между наблюдениями. Streaming Split EIP,
  сохраняющий изоляцию каждого observation и destination, всё равно требует
  этих execution boundaries и добавляет ещё одну обёртку. Новый cursor/batch
  API или спекулятивный runtime не допускаются без собственного доказанного
  выигрыша на полном сервисе.
- Сохранить Recipient List: корректно, но избыточно для уже выбранной локальной
  последовательной topology. Native endpoint calls сохраняют execution
  ownership framework с меньшим количеством механизмов адаптера.
