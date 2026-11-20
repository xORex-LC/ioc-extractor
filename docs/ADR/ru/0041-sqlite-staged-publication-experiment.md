> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0041-sqlite-staged-publication-experiment.md).

<a id="adr-0041-sqlite-staged-publication-experiment"></a>
# ADR 0041: Эксперимент staged publication в SQLite

- Статус: предложено; разрешает изолированный persistence experiment, а не активацию
- Дата: 2026-10-10
- Scope: ограничение физического владения canonical writer; SQLite остаётся
  единственным backend релиза 0.3.0

<a id="context"></a>
## Контекст

Ограниченные Java batches не освобождают SQLite writer внутри транзакции.
Исправленный canonical path всё ещё превышает лимит физического владения
в пять секунд. Обычное confirmation атомарно по artifact; managed import
атомарен для всех fan-out artifacts и canonical receipt. Разбиение любого
из них на видимые commits изменило бы поддерживаемую семантику recovery,
identity, provenance и lifecycle.

Текущее mutation kernel использует физические constraints, UPSERT и affected-row
counts. SQLite views с write triggers не могут прозрачно его заменить.
Эксперимент не допускает ни второй business mutation engine, ни миграцию
семейства БД.

<a id="proposed-protocol"></a>
## Предлагаемый протокол

<a id="ownership-and-storage"></a>
### Владение и хранение

Сохранить persistence в `adapter-store-jdbc` с существующими inward-facing
ports. Общее match/mutation kernel остаётся семантическим владельцем.
При интеграции эксперимента выделить его persistence surfaces; не реализовывать
KEEP_FIRST, registration order, import merging или slot policy повторно
в механизме публикации.

Логическая единица публикации — artifact confirmation или полная import delivery.
Её immutable identity включает существующие observation/delivery, contract,
workspace digest, catalog, schema и policy pins. Монотонно растущий attempt
fence запрещает брошенному preparer возобновить ownership. Retry использует
ту же logical identity и pins. Резервирование ID сохраняет правило non-reuse,
включая неуспешные attempts.

У prepared facts отдельная физическая version identity. Их logical address —
`(family, scope, key)`, где families охватывают canonical values/lifecycle,
aliases, provenance, origins, history, slots, revisions, generations и receipt
children. Эксперимент может использовать opaque payloads для проверки visibility;
production требует typed representations и selective indexes для существующих
queries. Opaque payloads не допущены как production canonical schema.

Каждая version указывает свои unit и attempt. Скрытые chunk transactions
сворачивают pending contributions и сохраняют immutable digest-qualified
упорядоченный chunk journal. Несколько accepted inputs могут обновлять один
pending address (например, один source summary); committed versions неизменяемы.
Подтверждённый chunk допускает точный retry без повторного применения, в том
числе после изменения этого address более поздними chunks. Изменённый retry,
пропуск или stale attempt отклоняются. Sealing разрешён только после завершения
объявленных chunks и contributions. Sealing замораживает attempt; publication
не может добавлять или менять её facts.

Итоговая транзакция выделяет publication sequence, сохраняет effective UTC
и полный receipt header и меняет SEALED на COMMITTED. Sequence выделяется
при publication, а не создании unit: не связанные control publications могут
обгонять pending promotion. Visibility выбирает новейшую COMMITTED version
для address. Её index использует отдельно выделяемый монотонный attempt stamp:
при выделении stamp dependency pins должны совпадать с текущими epochs;
publication атомарно проверяет и продвигает epoch каждого затронутого scope.
Поэтому две attempts одного address не могут публиковаться в обратном порядке
stamp: более старая должна перестроиться и получить новый stamp. Это позволяет
index-ordered lookup без сортировки всех исторических versions по joined
publication sequence. Pins будущего epoch должны отклоняться при создании,
а не только сравниваться при publication. Committed tombstone удаляет logical
fact и не должен показывать более старую version. Pending и cancelled versions
никогда не участвуют. Readers удерживают один принадлежащий им SQLite snapshot
через все families и artifacts.

<a id="conflict-and-time"></a>
### Конфликты и время

Логически сериализовать готовые promotions отдельно от physical writer admission.
Сохранить существующий service-ledger import head и ready-promotion admission
order; не заменять их глобальной сортировкой по observation registration.
Registration остаётся authority для mutable-field precedence по ADR 0030,
а не новым порядком winners KEEP_FIRST. Physical chunk/publication transactions
используют `JdbcWriterAdmission`; CONTROL, EXPIRY и EXPORT_SLOTS остаются
допустимыми между chunks. Интеграция должна оградить fence и durable logical
owner, и каждую attempt; одного process-local mutex недостаточно для допуска
restart recovery.

Preparation читает один committed base snapshot плюс собственный pending
overlay. Закрепить semantic epochs для каждого затронутого artifact и slot
namespace. Epochs продвигаются при изменении value, alias, provenance/origin,
renewal и expiry, включая metadata-only changes; одно обновление clock
high-water их не продвигает. Negative matching и slot reservations требуют
namespace pins наряду с positive row dependencies. Итоговая проверка
пропорциональна затронутым scopes, а не input rows. Этот консервативный первый
эксперимент может инвалидировать больше работы, чем будущий dependency vector;
эту стоимость нужно явно измерять. Facts не могут называть необъявленный scope.
Rebuild сохраняет business pins, но после пересчёта mutation plan может объявить
другой chunk/contribution manifest.

Получать safe UTC через существующие dataframe clock в итоговой publication
transaction. Это предлагаемая точка линеаризации confirmation. Отклонять plan,
если любой matched active lifecycle достиг закреплённого минимального deadline
(`deadline <= effective UTC`) или изменился dependency epoch. Перестраивать
из нового snapshot с теми же observation, registration rank и policy;
истёкший lifecycle получает существующий new-lifecycle outcome. Три неуспешных
attempts завершают publication attempt retryable failure, а не слишком большой
fallback transaction. Это ограничивает одну attempt, а не гарантирует
последующее продвижение очереди; sustained mixed-load qualification должна
доказать progress при допущенном intake.

**Решение владельца, 2026-10-10:** использовать свежий safe UTC при publication.
Это уточняет время first-owned-transaction из
[ADR 0020](../0020-canonical-record-expiration-lifecycle.md) для предлагаемого
staged protocol. Если preparation начинается до существующего deadline,
а publication происходит в этот момент или после него, перестроить относительно
publication time и создать новый lifecycle с новыми non-reusable internal IDs.
Никогда не восстанавливать и не продлевать истёкший lifecycle. При rebuild
сохранять observation identity, admission rank и policy pins; израсходованные
ID reservations остаются израсходованными. Владелец подтвердил это boundary
behavior, но production activation по-прежнему требует integration, fault,
migration и capacity gates этого предложенного ADR.

Новые и продлённые versions ссылаются на effective UTC публикующего header
и свою fixed validity duration. Непродлённые versions сохраняют прежний deadline
anchor. TTL остаётся свойством каждого canonical row lifecycle; общий
физический time anchor не превращает delivery в business identity.
До принятия этого представления интеграция должна квалифицировать indexed
active/nearest-deadline/expiry queries. N-row time correction при publication
не допускается. Physical probe хранит один absolute deadline в публикующем
header через существующую `RecordValidityPolicy`. Pending renewal versions
ссылаются на этот header; непродлённая version сохраняет прежний committed
anchor. Старые referenced deadlines также автоматически ограничивают
publication. Active lookup должен выбирать последнюю committed version
**до** проверки её deadline; предварительная фильтрация versions по activity
может воскресить старую version с большей validity. Nearest-deadline lookup
исключает superseded versions и tombstones. Expiry, first/last confirmation
timestamps, provenance timestamps и большие retained anchor sets всё ещё
требуют typed integration и resource qualification. Migration должна сохранять
записанные absolute deadlines и timestamps каждой legacy record. Нельзя
назначать всем старым rows один новый confirmation anchor или current-config
TTL. Представление legacy absolute values — обязательный typed-integration
gate; anchor-only physical probe не разрешает upgrade.

<a id="recovery-and-retention"></a>
### Восстановление и retention

До publication restart либо возобновляет digest-qualified chunks, либо берёт
новый fence и перестраивает работу. Старые attempts не могут append, publish,
cancel или clean up новую attempt. После publication recovery читает полный
canonical receipt без input/stage и не может применять ещё одну mutation.
Receipt mismatch или неполные evidence приводят к fail closed.

Cleanup удаляет cancelled attempts в bounded transactions. Committed versions,
time anchors и receipt evidence требуют явных retention rules: сохранять
последний logical fact/tombstone, facts, нужные active lifecycles и retained
receipts, и versions, нужные base незавершённой preparation. Закреплённый SQLite
reader сохраняет свой DB snapshot; application preparation leases и
ссылки deadline/receipt всё ещё требуют durable retention ownership.
WAL, versions и workspaces разделяют измеряемые disk/resource limits.
Cleanup или retention timeout никогда не должны молча менять matching
или recovery semantics.

<a id="promotion-gates"></a>
## Gates продвижения

1. Проверить physical primitive с закреплённым SQLite driver: chunk retry,
   stale fence, seal, rollback, dependency/time conflict, selective reads,
   tombstones, publication ordering и pinned snapshots. Это устанавливает
   только persistence behavior, а не эквивалентность canonical ports.
2. Переиспользовать реальное mutation kernel и квалифицировать все typed families
   за существующими ports. Readers, expiry, slots и receipts должны переключаться
   вместе. До этого gate нет production schema migration или configuration activation.
3. Выполнить полную crash/concurrency matrix и существующие adapter/TCK suites.
   Проверять identities, registration ranks, provenance, TTL, aliases, revisions,
   generations, slots и receipt-only recovery, а не только CSV equality.
4. Сравнить полный сервис на corrected и candidate SQLite при одинаковых
   workloads и limits. Включить read amplification, invalidation/rebuilds,
   logical delay, physical holds/waits, cleanup, WAL и диск. Все исходные limits
   обязательны; failed cells остаются evidence.
5. Принять этот ADR только после реализации и квалификации integration decisions
   выше. Для upgrade завершить intake/publications, отклонить несовместимые
   binaries и квалифицировать coordinated backup/restore rollback, readiness
   и cleanup. Изменения установленного сервиса требуют отдельного deployment action.

<a id="alternatives-and-consequences"></a>
## Альтернативы и последствия

- Видимые chunk commits: отклонены; ослабляют artifact/delivery atomicity.
- Больше SQLite connections или меньшие batches в одной transaction:
  не решают physical occupancy.
- Копии generation всей DB: добавляют copy/merge work размером с DB и без ещё
  одного протокола не сохраняют concurrent expiry/slot decisions.
- PostgreSQL: будущая запланированная владельцем линия, исключённая из релиза.
- Прозрачные writable views: несовместимы с существующими UPSERT и update counts;
  не являются готовой заменой kernel integration.

Versioned model добавляет read amplification, retention и recovery machinery.
Она оправдана только если измеренные occupancy и whole-service costs
удовлетворяют принятым limits без ослабления data authority. Неуспешный
эксперимент оставляет текущий path и failed capacity status явными; он не
разрешает ослаблять limits или преждевременно активировать production.

<a id="experiment-concluded-2026-10-13"></a>
## Эксперимент завершён, 2026-10-13

Владелец выбрал Native SQLite для production. Изолированная реализация и
временный experiment bundle удаляются по
[ADR 0042](../0042-native-sqlite-production-storage.md); предложенный протокол
не был активирован. Историческое обоснование выше не меняется. Сохранённые
production architecture и scoped measurement facts находятся в
[storage](../../dev/storage.md) и [native capacity](../../dev/native-capacity.md).
