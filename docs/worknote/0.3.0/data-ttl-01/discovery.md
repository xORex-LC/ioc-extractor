---
title: "DATA-TTL-01: интервью о жизненном цикле canonical records"
version: "0.3.0"
status: "Interview completed"
document_type: "Interview worknote"
source_of_truth: false
language: "ru"
---

# DATA-TTL-01: интервью о жизненном цикле canonical records

## 1. Назначение

Этот временный worknote ведёт product/architecture discovery для срочной
business-задачи TTL canonical artifact records. Задача отсутствует в принятом
scope 0.3.0; включение реализации в релиз оформлено отдельным scope decision по
[engineering-release.md](../engineering-release.md#11-управление-изменениями).

Worknote не является опубликованным контрактом. После принятия решения durable
семантика должна попасть в ADR и затронутые capability docs; release state — в
[status-matrix.md](../status-matrix.md).

Интервью I-01..I-20 завершено. Код, schema migrations и runtime configuration
пока не изменяются.

## 2. Исходный контекст

- Рабочая ветка: `feature/record-ttl`, создана пользователем от `d75e5ce`.
- Canonical truth хранится в SQLite dataframe tables; mutable CSV и immutable
  export slices являются производными представлениями.
- Запись идентифицируется `row_key`; keep-first сохраняет первый public row и
  ID, а повторные наблюдения обновляют `<artifact>_sources`.
- Технический provenance уже содержит `first_seen_at`, `last_seen_at` и
  `occurrences`; public `time_first_seen`/`time_last_seen` пока всегда `NULL`.
- Existing maintenance retention чистит рабочие файлы, а export retention —
  immutable slices. Ни один из этих механизмов не реализует TTL business rows.
- Daemon считает идентичный файл terminal duplicate по content-hash
  `SourceKey` и не запускает для него ETL повторно.

## 3. Правила интервью

1. Вопросы формулируются через требуемое поведение, UX и эксплуатационные
   сценарии, а не как просьба заказчику выбрать класс, таблицу или паттерн.
2. Предложения пользователя являются гипотезами для оценки, а не обязательными
   техническими решениями.
3. После каждого ответа фиксируются подтверждённые требования, рекомендации,
   альтернативы, риски и следующий открытый вопрос.
4. Реализация начинается только после согласования модели, scope boundaries и
   проверяемых invariants.

## 4. Interview ledger

### I-01 — владелец срока жизни и факт подтверждения

**Вопрос.** Кому принадлежит срок жизни при нескольких источниках, считается ли
повторная доставка идентичного документа подтверждением и как вести себя при
повторном появлении истёкшего IOC?

**Ответ заказчика.**

- Повторная доставка идентичного документа подтверждает актуальность данных.
- Если хотя бы один источник продолжает подтверждать запись, она остаётся
  активной.
- Повторное появление после истечения должно продолжать прежнюю запись, а не
  создавать новый жизненный цикл.
- Для первой версии нужен один конфигурируемый TTL, применяемый по умолчанию ко
  всем записям. Дизайн не должен блокировать будущую policy, учитывающую разные
  источники и другие факторы.
- TTL должен принадлежать конкретной canonical record. Источники могут оставлять
  metadata/provenance, но не должны становиться владельцами TTL или жёстко
  связывать lifecycle со своей моделью.
- Предложена гипотеза write coalescing: при частых подтверждениях продлевать
  срок физически только незадолго до expiry, например когда остался один час.

**Архитектурная оценка.**

Требование разделяется на два независимых контракта:

1. каждое принятое подтверждение **логически** обновляет актуальность record;
2. частоту физической записи `valid_until` можно оптимизировать, если это не
   меняет наблюдаемое поведение.

Фиксированное часовое renewal window нельзя принимать как correctness policy:
оно связывает сохранность записи с cadence источника, scheduler jitter и
downtime. При TTL `24h`, окне `1h` и поступлении каждые `6h` подтверждение может
попасть только на саму границу expiry и проиграть reaper-у. Такая оптимизация
допустима позже после измерений и с явно доказанным cadence contract.

В application language владелец lifecycle — storage-neutral canonical artifact
record, а не буквально SQLite row. Источник предоставляет observation evidence;
policy вычисляет следующий срок; JDBC adapter сохраняет результат. Это сохраняет
hexagonal dependency direction и не переносит storage/source coupling в core.

**Предварительная рекомендация.**

- V1: любое успешно принятое подтверждение продлевает row-level срок на один
  configured fixed TTL.
- Отдельный минимальный `RecordValidityPolicy`/Strategy скрывает способ вычисления
  срока; единственная V1-реализация использует fixed TTL. Factory/Decorator и
  иерархия policy пока не нужны.
- Provenance остаётся отдельной моделью доказательств и не владеет expiry.
- Identical daemon duplicate не должен повторять ETL, но обязан иметь путь
  подтверждения уже известных records, потенциально bulk по `SourceKey`.
- Новые библиотеки для V1 не требуются: достаточно `Clock`, `Instant`,
  `Duration` и SQLite transaction semantics.

**Риски, ещё не закрытые решением.**

- Если истёкшие rows физически удалить, terminal ingestion ledger не позволит
  тому же идентичному файлу восстановить их без отдельного lifecycle решения.
- `MAX(id)+1` может повторно выдать удалённый максимальный public ID после
  рестарта.
- Отдельные artifacts могут содержать представления одного исходного IOC; пока
  не решено, должны ли их lifecycle transitions быть независимыми или
  согласованными.
- Честная фиксация каждого `last_confirmed_at` сама требует записи и может
  обнулить ожидаемую выгоду renewal window.

**Статус:** product semantics подтверждена; физическая модель и write
optimization остаются открытыми.

### I-02 — внешнее значение истечения

**Вопрос.** Что именно должен увидеть пользователь и что система обязана
сохранить после expiry?

**Ответ заказчика.**

- Expired record безусловно исключается из mutable dataframe, новых export
  slices и любых будущих operational consumers, например формирования RPZ-зоны
  на DNS server.
- Предпочтение — не оставлять expired record в active canonical DB. Заказчик
  открыт для отдельного history/archive, если он оправдан архитектурно.
- Повторное появление после двух месяцев отсутствия является новой актуальной
  record, а не реактивацией прежней. Старый public ID и прежний `first_seen` не
  должны возвращаться в active lifecycle.
- Операторская возможность найти expired record и понять её судьбу полезна.
- Уже созданные immutable slices не переписываются; следующий complete slice и
  последующие consumers не должны содержать expired record.

**Архитектурная оценка.**

Требования не поддерживают бессрочный soft delete в active artifact table:
сохранённый `row_key`/public ID конфликтовал бы с новым lifecycle, а каждый
current read был бы обязан фильтровать растущую историю. В то же время безусловный
hard delete без durable следа не даёт требуемой operator visibility.

Предварительно подходящая модель разделяет два storage role:

```text
active canonical record --expiry--> historical lifecycle record
         |                                  |
         | public ID                        | independent history ID
         | current projections              | audit/query only
         +----------------------------------+ original ID as evidence only
```

Historical record не является неактивной разновидностью active aggregate и не
участвует в projection/export/identity matching. Повторное наблюдение создаёт
новую active record с новым lifecycle. Точный объём истории — полная public row,
identity/provenance snapshot или только lifecycle audit — ещё не определён.

Физический reaper и логическая видимость должны быть разделены. Даже при
периодической batch-очистке read-path обязан исключать `valid_until <= asOf`,
иначе export между deadline и следующим sweep выдаст уже просроченную запись.
Reaper отвечает за ограничение размера active tables, а не за саму семантику
expiry.

**Предварительная рекомендация по SQL-path.**

- Хранить индексируемый `valid_until` рядом с active canonical record; не
  вычислять expiry через aggregate join с provenance на каждом export.
- Выбирать expired rows ограниченными batches через range predicate по
  `valid_until`, без загрузки всей таблицы в application memory.
- В одной artifact-local transaction сохранять требуемый audit, удалять active
  row/provenance и ровно один раз продвигать `artifact_revision` для фактически
  изменившегося public content.
- Использовать существующий `ON DELETE CASCADE` для active provenance только
  после решения, какая его часть должна попасть в history.
- Не вводить partitioning, cache или новую СУБД без volume evidence; для
  текущего SQLite workload сначала нужны query plan и batch-size measurements.
- Не выбирать generic JSON archive или зеркальные per-artifact history tables,
  пока не согласован operator query contract.

**Риски, ещё не закрытые решением.**

- Физическое удаление максимального `id` делает текущий `MAX(id)+1` allocator
  способным переиспользовать старый public ID после рестарта.
- Старые immutable slices и внешние consumers могут продолжать содержать
  expired record; ID reuse способен смешать два разных lifecycle.
- Crash после DB expiry до mutable projection требует recoverable reprojection
  protocol.
- Terminal duplicate по source hash после удаления не может восстановить
  records без специального решения для повторного подтверждения.
- History требует собственной retention/privacy/capacity policy; бессрочный
  audit нельзя принять молча.

**Статус:** внешнее поведение подтверждено; форма history и ID contract открыты.

### I-03 — можно ли когда-либо переиспользовать public ID

**Вопрос.** Может ли public ID expired record быть выдан другой active record
или новому lifecycle того же IOC?

Старый immutable slice или downstream consumer может ещё содержать пару
`id=125 / example.com`, когда текущая система уже назначит `id=125` другой
record. Нужно определить, является ли ID лишь номером строки одного текущего
snapshot либо идентификатором, который не должен менять смысл во времени.

**Текущая рекомендация.** Public IDs не переиспользуются никогда, даже после
expiry. Новый lifecycle получает новый monotonically allocated ID выше durable
high-water mark. Historical storage использует собственный history ID, но может
сохранять прежний public ID как audit evidence. Последовательность может иметь
gaps; compact/gapless allocation не является целью.

**Ответ заказчика.** Запрет повторного использования public ID принят.

**Подтверждённый инвариант.** Однажды выданный public ID навсегда сохраняет своё
историческое значение и не назначается другой record или новому lifecycle того
же IOC. Allocation использует durable per-artifact high-water mark, не зависит
от `MAX(id)` active rows и допускает gaps. Возможные ограничения диапазона
конкретного downstream format должны проверяться отдельно и не ослабляют этот
инвариант молча.

**Статус:** подтверждено.

**Reopen note.** После подтверждения выявлен отдельный случай source-supplied
ID. Инвариант выше относится только к ID, которыми владеет IOC Extractor. ID,
пришедший от внешнего источника, принадлежит другому identity namespace и не
может быть безусловно приравнен к service-owned public ID. Детализация вынесена
в I-03A; исходное решение не отменено, а сужено до корректной области.

### I-03A — internal, source и delivery identity namespaces

**Сценарий заказчика.** Источник может передать собственный ID, соответствие
которого IOC необходимо сохранить в export slice или при доставке в DNS/firewall.
Через два месяца новый input может содержать многие прежние IOC с теми же
source IDs. Предложено хранить невидимый service record ID отдельно от ID,
полученного из источника и используемого downstream.

**Архитектурная оценка.** Направление верное, но одного поля
`record_id_source` в canonical row недостаточно:

- одна canonical record может наблюдаться несколькими источниками с разными
  external IDs;
- одинаковое external ID может существовать в разных источниках и не является
  глобально уникальным;
- один источник теоретически может повторно назначить ID другому IOC;
- merged delivery должен явно решить, какой из нескольких external IDs выводить.

Текущая schema совмещает две роли: `id` является SQLite primary key и, для
артефактов с configured `from: id`, public output column. `row_key` отдельно
задаёт canonical content identity. Source-aware delivery требует перестать
считать эти роли одной identity.

**Предварительная модель.**

| Identity | Владелец | Область уникальности | Выводится наружу |
|---|---|---|---|
| `record_lifecycle_id` | IOC Extractor | внутренний lifecycle | нет |
| `row_key` | canonical policy | active artifact/content epoch | нет |
| `SourceRecordReference(sourceNamespace, externalId)` | источник | namespace логического источника | только по explicit output policy |
| `delivery_id` | output profile/consumer contract | delivery namespace | да, если формат требует |

Source references являются нормализованной one-to-many relation к lifecycle,
а не одним nullable полем canonical row. Внешний ID хранится как opaque value:
он может быть нечисловым и не участвует во внутреннем allocation.

Service-owned `delivery_id` по-прежнему никогда не переиспользуется. Тот же
external ID может появиться в новом внутреннем lifecycle, если это допустимо
контрактом источника; audit различает lifecycle по internal ID. Это осознанное
повторение в чужом namespace, а не нарушение service allocator invariant.

**Предварительная delivery policy.**

- Output, привязанный к одному logical source, может воспроизводить его
  external ID без преобразования.
- Общий merged output не должен выбирать один source ID неявно. Без отдельного
  правила он использует service-owned delivery ID.
- Если consumer обязан видеть provenance, безопаснее передавать
  `source_namespace + external_id` отдельными полями либо формировать
  source-scoped profile. Raw `external_id` без namespace создаёт коллизии.
- Priority/fallback между источниками является отдельной business policy; её
  нельзя прятать в SQL `MIN/MAX` или порядке поступления.

**Ответ заказчика.** Рекомендация для merged/source-scoped output принята.

**Подтверждённый delivery contract.**

- Merged output содержит одну canonical строку с service-owned delivery ID.
- External IDs всех подтвердивших источников остаются provenance metadata и не
  конкурируют за колонку `id` общей строки.
- Точное воспроизведение source ID разрешено только явному source-scoped output
  либо отдельным namespaced полям, если их поддерживает consumer schema.
- Неявного выбора «первого», минимального или последнего source ID нет.

**Открытые риски.**

- Если один source ID переезжает на другой IOC, нужно выбрать fail-closed,
  versioned remap или authoritative replacement semantics.
- Если два источника дают одному IOC разные IDs, merged output не имеет
  естественного единственного source ID.
- Перенос current table PK с public `id` на скрытый internal ID затрагивает
  foreign keys, projection order, ID baseline и upgrade migration; это не
  следует делать до принятия output contract.
- Source namespace нельзя надёжно выводить из content-hash `SourceKey`, имени
  файла или свободного section label; потребуется стабильная logical source
  identity, если source IDs входят в scope.

**Остаётся выяснить реальную cardinality/authority external ID:**

1. может ли один IOC прийти от двух источников с разными IDs;
2. могут ли два источника использовать одинаковый ID для разных IOC;
3. может ли один источник назначить прежний ID другому IOC со временем;
4. использует ли downstream ID как ключ update/delete либо только как
   информационное поле.

**Статус:** output selection подтверждён; source remap/authority contract открыт;
I-04 приостановлен до его уточнения.

### I-03B — прежний external ID указывает на другой IOC

**Вопрос.** Источник сначала сообщает `external_id=17 → example.com`, а позднее
тот же logical source сообщает `external_id=17 → evil.example`. Является ли это
легитимным обновлением source record, исправлением ошибки или повреждением
входных данных?

**Текущая рекомендация.** По умолчанию считать такую смену identity conflict и
останавливать применение этого source reference с typed diagnostic. Нельзя
молча перенести ID между canonical records: downstream может использовать его
как update/delete key, а audit потеряет причинность.

Разрешённый remap должен быть отдельной opt-in capability конкретного logical
source с документированным mutable-ID contract. Тогда прежняя связь закрывается,
новая открывается атомарно, обе версии остаются в history, а affected outputs
перестраиваются как одно согласованное изменение.

Нужно также выяснить, встречаются ли реальные источники с mutable IDs и является
ли external ID для downstream ключом управления либо только отображаемой
metadata.

**Ответ заказчика.** Рекомендация принята.

**Подтверждённый инвариант.** Несовпадение существующей пары
`source_namespace + external_id` с новым IOC является identity conflict и по
умолчанию обрабатывается fail-closed с typed diagnostic. Молчаливый remap,
last-write-wins и перенос связи по порядку поступления запрещены.

Mutable-ID semantics допускается только как отдельная opt-in capability
конкретного logical source. Её будущий контракт обязан атомарно закрывать старую
версию связи, открывать новую, сохранять обе в history и инициировать
согласованное обновление affected outputs.

**Статус:** подтверждено; identity block I-03 закрыт для V1 discovery.

### I-04 — является ли отсутствие IOC отрицательным подтверждением

**Вопрос.** Если логический источник прислал новый успешно обработанный документ,
в котором ранее встречавшегося IOC больше нет, означает ли это немедленное
отрицательное подтверждение или только отсутствие нового положительного
наблюдения?

Нужно различать два source contract:

1. **snapshot:** документ объявляет полный актуальный набор источника; отсутствие
   IOC может отзывать прежнее подтверждение;
2. **advisory/delta:** документ содержит только новые или выборочные наблюдения;
   отсутствие ничего не говорит, и record живёт до обычного TTL.

Сбой чтения, пустой из-за parser/error документ или недоступность источника не
могут считаться отрицательным подтверждением: иначе техническая ошибка массово
удалит актуальные records.

**Текущая рекомендация.** V1 использует только positive-observation semantics:
успешно извлечённый IOC подтверждает record, отсутствие IOC не сокращает срок и
не удаляет record досрочно. Authoritative snapshot/revocation — отдельный будущий
source capability с logical source identity, completeness proof и atomic
reconciliation; его нельзя выводить из имени файла, section label или
content-hash `SourceKey`.

Нужно подтвердить, существуют ли уже сейчас входы, которые гарантированно
являются полными snapshots и должны отзывать отсутствующие IOC до истечения TTL.

**Ответ заказчика.** Немедленный отзыв по отсутствию запрещён. Все текущие
документы абсолютно неполные: новый документ приносит дополнительные feeds, но
не отменяет IOC из предыдущих документов. Актуальный output является
объединением всех положительно подтверждённых records, чей TTL ещё не истёк.

**Подтверждённый инвариант.**

- V1 поддерживает только positive observations.
- Наличие IOC в успешно обработанном input подтверждает/продлевает record.
- Отсутствие IOC, пустой документ, parser failure, недоступность source и новый
  partial input никогда не сокращают TTL и не инициируют revoke.
- Последовательные документы накапливают union активных records; каждая record
  независимо покидает union только после собственного expiry.
- Snapshot/revocation semantics не входят в V1 и потребуют отдельного явно
  объявленного source contract.

**SQL/clock следствие.** «Постепенное уменьшение TTL» является вычислением, а не
фоновым UPDATE каждой строки. Storage хранит абсолютный `valid_until`; оставшееся
время равно `valid_until - asOf`. Это устраняет периодическую write amplification
и позволяет использовать B-tree range index для expiry candidates.

**Статус:** подтверждено.

### I-05 — processing time, event time и точная граница expiry

**Вопрос.** Какое время считается подтверждением, если input поступил до
deadline, но был успешно обработан после него?

Пример:

```text
09:59  файл обнаружен; record истекает в 10:00
10:00  record логически expired
10:05  backlog разобран, ETL и canonical transaction успешны
```

Возможны две семантики:

1. **event/detection time:** подтверждение относится к 09:59 и может сохранить
   прежний lifecycle ретроспективно, хотя до 10:05 данные ещё не были проверены;
2. **successful commit time:** до durable success подтверждения не существует;
   в 10:05 начинается новый lifecycle.

**Текущая рекомендация для V1.** Использовать один authoritative processing
instant, полученный от injected `Clock` внутри успешной canonical transaction.
Input становится подтверждением только после parse, policy checkpoint и durable
write. Source timestamps и filesystem mtime не считаются доверенным business
временем.

Граничный контракт:

- record активна только при `asOf < valid_until`; равенство означает expiry;
- observation transaction при `confirmed_at < valid_until` продлевает текущий
  lifecycle;
- при `confirmed_at >= valid_until` создаётся новый lifecycle, даже если reaper
  ещё физически не перенёс старую row в history;
- один export snapshot использует единый `asOf` для всех artifacts, а не читает
  часы отдельно для каждой строки.

Такой контракт не делает состояние задним числом «не истекавшим», но backlog или
downtime может превратить пришедший вовремя input в новый lifecycle. Нужно
подтвердить, приемлемо ли это, или время успешного admission/claim должно
сохранять непрерывность после более поздней обработки.

**Ответ заказчика.** Рекомендация принята: подтверждением является успешная
canonical transaction после parsing и failure-policy checkpoint.

**Подтверждённый инвариант.**

- `confirmed_at` принадлежит durable canonical commit, а не detection, claim,
  source timestamp или filesystem metadata.
- Непроверенный backlog не продлевает TTL.
- Commit после `valid_until` создаёт новый lifecycle; ретроспективного восстановления
  непрерывности нет.
- Boundary остаётся half-open: `[confirmed_at, valid_until)`, equality означает
  expiry.
- Policy и tests используют injected `Clock`; row-by-row wall-clock reads не
  участвуют в одном logical decision.

**Статус:** подтверждено.

### I-06 — daemon downtime, startup barrier и oneshot enforcement

**Вопрос.** Когда процесс не запущен, absolute time продолжает идти, но никто не
может переписать mutable CSV, сформировать новый slice или доставить его наружу.
Какой catch-up contract нужен при следующем запуске/CLI invocation?

Сценарий:

```text
10:00  daemon остановлен
11:00  records логически истекли
15:00  daemon запущен; старый CSV и последний remote slice ещё содержат их
```

Технически невозможно обновить файлы и firewall во время полной остановки
сервиса. Можно гарантировать, что после возобновления работы никакая новая
операция не использует expired records и что derived outputs быстро сходятся к
canonical truth.

**Текущая рекомендация.**

- Expiry clock не ставится на паузу во время downtime.
- Daemon выполняет fail-closed expiry reconciliation после storage/schema
  recovery, но до intake, export/publish scheduling и readiness. Ошибка оставляет
  operational work закрытым.
- Reconciliation архивирует/deletes due lifecycles, продвигает artifact
  revisions и запускает recoverable mutable reprojection.
- Periodic expiry scheduler поддерживает состояние после startup; read-path
  predicate `valid_until > asOf` остаётся defense in depth, а не заменой mutation.
- Stateful oneshot `extract` и `export` выполняют expiry reconciliation до
  operation. Это особенно важно для export: текущий revision pre-gate иначе
  может не заметить изменение, вызванное только ходом времени.
- Read-only `health` не мутирует storage; он сообщает due backlog/degradation.
- Пока приложение выключено, существующие local/remote artifacts могут быть
  устаревшими. После запуска новый complete slice/reprojection исправляет их;
  прошлые immutable slices не переписываются.

**Ответ заказчика.** Все три условия приняты:

1. stale-копия снаружи во время полной остановки сервиса приемлема;
2. daemon должен работать fail-closed и не переходить к readiness/intake, если
   expiry reconciliation не завершился успешно;
3. stateful-команды `ioc export` и `ioc extract` могут сначала выполнять
   mutating reconciliation, а `health` должен оставаться read-only.

**Зафиксированный contract.** Absolute expiry продолжает идти во время
downtime. После storage/schema recovery daemon обязан закрыть due lifecycles и
восстановить производные состояния до открытия intake, export/publish и
readiness. Stateful oneshot-команды применяют тот же mutating precondition;
`health` только наблюдает due backlog и degradation. После возобновления работы
mutable projection и следующий complete slice сходятся к актуальному canonical
state, а уже созданные immutable slices не переписываются.

**Статус:** подтверждено.

### I-07 — конфигурационный UX и безопасное включение TTL

**Вопрос.** Должен ли TTL автоматически включиться после обновления или оператор
должен явно активировать новую lifecycle policy?

Это не только удобство конфигурации. В существующих инсталляциях уже накоплены
записи без expiry. Если новая версия молча получит classpath default вроде
`24h`, upgrade может начать отзывать данные без осознанного решения оператора и
без согласованного backfill правила для старых rows. Особенно опасно, если
внешний overlay не содержит нового ключа: формально конфигурация валидна, но
поведение становится разрушительным только из-за смены версии приложения.

Возможные контракты:

1. **Implicit enable:** TTL автоматически включён, например на `24h`.
   Минимальная настройка, но небезопасный upgrade contract.
2. **Explicit opt-in:** legacy/default mode остаётся `disabled`; оператор
   включает `fixed` и задаёт положительный TTL.
3. **Mandatory explicit choice:** после upgrade startup требует явно выбрать
   `disabled` или `fixed`, даже если ранее lifecycle config отсутствовал. Это
   исключает неосознанный выбор, но делает обновление несовместимым без
   одновременной правки конфигурации.

**Текущая рекомендация: вариант 2, explicit opt-in.**

- В V1 использовать один понятный режим, например
  `ioc.lifecycle.validity.mode: disabled|fixed`; для `fixed` обязательно
  положительное `ttl`. Значения `0`, отрицательная duration и неявное
  «бесконечно» должны отклоняться strict semantic validation.
- Classpath default сохраняет прежнее поведение (`disabled`), поэтому сам
  upgrade ничего не отзывает. Включение TTL становится отдельным наблюдаемым
  operator action с заранее выбранной migration/backfill policy.
- В V1 не добавлять overrides по artifact/source/IOC type. Это сохраняет один
  business contract и не связывает lifecycle policy с происхождением записи.
- Strict `ioc.*` preflight должен отклонять неизвестные поля и неполную
  комбинацию `mode=fixed` без `ttl`; effective activation должна попадать в
  value-free startup reporting и diagnostics.
- Production packaging template можно настроить иначе, чем classpath default,
  но только явно. Для новой установки разумно предложить `fixed` как
  рекомендуемый production preset; существующая установка не должна получить
  его скрытно при обновлении пакета.

Нужно определить:

1. приемлем ли explicit opt-in, или бизнес требует обязательный TTL на каждой
   установке сразу после обновления;
2. гарантированно ли deployment process может атомарно обновить приложение и
   внешний config, если выбрать более строгий вариант 3;
3. должен ли production template для **новой** установки сразу включать fixed
   TTL, сохраняя classpath default безопасно выключенным.

Решение здесь не определяет судьбу уже накопленных rows: timestamp/backfill при
первом включении — отдельный migration contract следующего вопроса.

**Ответ заказчика.** Explicit opt-in для существующей БД приемлем. Для новой
установки production template должен сразу включать TTL. Предложено трактовать
`ttl=0` как очистку всех существующих записей через их немедленное устаревание.
Возможность атомарно обновить приложение и внешний config требует анализа.

**Результат анализа deployment contract.** Текущий production flow не
гарантирует атомарную замену jar и operator config:

- `etc/application.yml` намеренно принадлежит оператору и сохраняется между
  релизами;
- изменённый packaged template записывается рядом как `application.yml.new`, но
  не объединяется с действующим файлом автоматически;
- `deploy-local` атомарно переключает immutable release и делает DB backup с
  rollback, но не включает внешний config в эту activation transaction;
- следовательно, mandatory explicit choice (вариант 3) сделал бы обычный
  upgrade неработоспособным до ручного merge;
- после ручного включения TTL rollback должен восстанавливать согласованную
  тройку application + config + DB. Автоматический rollback deployment сейчас
  не откатывает operator-owned config, потому что сам его не изменяет.

**Принятое направление.** Вариант 2 соответствует существующему deployment
contract: classpath default остаётся `disabled`; upgrade с прежним config
безопасно запускается без expiry; оператор затем явно включает `fixed` и
перезапускает сервис. Fresh-install production template явно задаёт `fixed` с
положительным TTL. Точное production default значение ещё предстоит утвердить.

**Возражение против `ttl=0`.** Нулевой fixed TTL нельзя безопасно использовать
как одноразовую очистку:

- как постоянная policy он немедленно истекает не только legacy rows, но и
  каждое новое подтверждение;
- special-case «ноль действует только при первом запуске» делает обычный
  declarative config скрытой stateful/destructive командой;
- опечатка `0` вместо duration приводит к массовому отзыву данных;
- из effective config невозможно понять, продолжает ли значение управлять
  runtime или его эффект уже был применён.

Поэтому `fixed.ttl` должен быть строго больше нуля. Требуемый business outcome
следует выразить отдельной, явно названной activation policy, например
`existing-records: expire`, и записать факт её применения в БД. Такая операция
должна закрывать lifecycle как expired, а не делать `TRUNCATE`: history,
diagnostics, revision/reprojection и monotonic ID contract сохраняются.

**Статус:** activation/default contract подтверждён; судьба legacy rows вынесена
в I-08.

### I-08 — одноразовая миграция записей из 0.2.0

**Вопрос.** Что именно должно произойти при первом включении `fixed` на БД, где
у canonical rows ещё нет lifecycle timestamps?

Да, это непосредственно тот случай, о котором спросил заказчик: записи были
созданы версией 0.2.0 и не имели TTL. При этом в текущем canonical storage уже
есть техническое `_created_at`, поэтому доступны три осмысленные migration
policy:

1. **`expire`** — немедленно закрыть все legacy lifecycles независимо от
   `_created_at`. Active dataframe станет пустым до поступления новых успешных
   observations.
2. **`grant-ttl`** — считать момент включения TTL первым условным подтверждением
   и дать всем legacy rows полный срок `activated_at + fixed.ttl`.
3. **`from-created-at`** — рассчитать `valid_until = _created_at + fixed.ttl`;
   старые rows истекут сразу, достаточно свежие доживут остаток срока.

**Текущая рекомендация с учётом ответа заказчика: explicit `expire`.** Это
реализует желаемую полную очистку, но не перегружает значением `0` постоянную
TTL policy. Выбор применяется ровно один раз, транзакционно маркируется в
lifecycle metadata и остаётся идемпотентным после crash/restart.

Есть два неочевидных следствия, которые необходимо подтвердить:

1. После activation active canonical storage и новые mutable/export slices
   могут стать полностью пустыми. Они наполнятся только новыми успешно
   обработанными источниками; старые документы не должны автоматически
   переигрываться из archive.
2. Старый ingestion/source ledger не должен блокировать новую lifecycle.
   Повторно пришедший после activation документ или IOC должен либо подтвердить
   ещё активную запись, либо создать новую lifecycle, если прежняя была закрыта;
   исторический public ID при этом не восстанавливается.

Отдельно нужно решить, допускается ли activation при работающей внешней
доставке: рекомендация — только через тот же startup reconciliation barrier до
readiness/publish, с DB+config backup как единым rollback point.

**Ответ заказчика.** Все условия приняты:

1. `ttl=0` заменяется явной одноразовой policy `existing-records: expire`;
2. временно полностью пустой active storage и новые slices приемлемы; archived
   source documents автоматически не переигрываются;
3. activation выполняется до readiness/intake/publish и требует заранее
   подготовленного rollback point.

Поведение upgrade/activation должно быть опубликовано языком оператора, а не
остаться только в ADR или implementation notes. Будущий operator contract обязан
явно описать как минимум:

- отличие fresh install от upgrade существующей установки;
- необходимость backup и согласованного application+config+DB rollback point;
- одноразовый и destructive по отношению к active set эффект
  `existing-records: expire`;
- допустимость пустого dataframe/export до новых успешных observations;
- отсутствие автоматического replay архивных документов;
- startup fail-closed и возможную stale external copy во время downtime.

**Зафиксированный migration contract.** Policy применяется идемпотентно через
startup reconciliation barrier. Она закрывает legacy lifecycles с audit trail,
продвигает revisions и инициирует recoverable reprojection/export convergence;
исторические public IDs не возвращаются. Старые ingestion/source ledgers не
должны препятствовать созданию новой lifecycle после нового accepted
observation.

**Статус:** подтверждено.

### I-09 — lifecycle timestamps и публичные `time_*` поля

**Вопрос.** Должно ли каждое подтверждение актуальности быть видно downstream
consumer в `time_last_seen`, или для V1 достаточно использовать время только
внутри lifecycle/TTL механизма?

Текущее состояние различает два слоя не полностью:

- public schemas `masks`, `ip_list` и `hashes` уже содержат
  `time_first_seen`/`time_last_seen`, но provider `const` всегда пишет `NULL`;
- `<artifact>_sources` хранит технические per-source `first_seen_at`,
  `last_seen_at` и `occurrences`;
- `<artifact>_last_seen` агрегирует максимум source observations;
- `_created_at` фиксирует первоначальную вставку canonical row, но не является
  полноценным lifecycle contract.

TTL требует собственных storage-neutral времён независимо от публичного CSV:

- `first_confirmed_at` — первый accepted observation текущей lifecycle;
- `last_confirmed_at` — последний accepted observation от любого источника;
- `valid_until` — абсолютная граница активности, вычисленная policy;
- history additionally фиксирует фактическое закрытие/reconciliation, не
  подменяя им business expiry deadline.

Все времена — UTC instants; граница остаётся half-open
`[first_confirmed_at, valid_until)`. После expiry новая lifecycle начинает
`first_confirmed_at` заново. Время из имени/метаданных source document не может
подменить эти значения: оно остаётся отдельным provenance evidence.

Возможны два честных публичных контракта:

1. **TTL-internal V1.** `time_*` в стандартных CSV пока остаются `NULL`; exact
   lifecycle times доступны operator/audit read side, а downstream получает
   только актуальный active membership.
2. **Public observation times.** `time_first_seen` получает начало текущей
   lifecycle, `time_last_seen` — каждый последний accepted observation. Тогда
   любое подтверждение меняет public content и в той же transaction обязано
   bump artifact revision; export scheduler может coalesce изменения, но не
   имеет права показывать заведомо старое `time_last_seen`.

**Текущая рекомендация: вариант 1 для TTL V1.** Он не связывает correctness
expiry с отдельной задачей enrichment `OUT-1` и не создаёт поток новых immutable
slices только из-за повторных observations. Внутренние timestamps при этом не
являются optional: они нужны для policy, history и operator visibility.

`valid_until` также не рекомендуется добавлять в существующие reputation-list
schemas: DNS/firewall consumers должны получать только active rows и не
реализовывать TTL повторно. Если конкретному downstream нужен expiry deadline,
это должен быть отдельный versioned export profile/contract, а не скрытое
изменение всех текущих CSV.

Нужно определить:

1. downstream consumers сейчас используют или ожидают реальные значения
   `time_first_seen`/`time_last_seen`, либо им достаточно состава active set;
2. если public `time_last_seen` обязателен, приемлемы ли revision/export/publish
   updates при каждом принятом подтверждении;
3. нужен ли какому-либо внешнему consumer точный `valid_until`, или он должен
   оставаться только operator/internal lifecycle metadata.

**Ответ заказчика.** `time_first_seen` и `time_last_seen` являются обязательными
business columns: dataframe используется конечными системами, которые ожидают
существующую схему и порядок полей. В TTL V1 их значения остаются `NULL`, как и
сейчас. Жёсткой связи с lifecycle timestamps пока не вводится.

Точный `valid_until` текущим конечным системам не нужен. Их contract — получить
актуальный набор и понять, что устаревшую запись следует удалить/забыть. В
будущем отдельная целевая система может потребовать самостоятельный TTL; дизайн
должен позволять передать deadline через подходящий для неё integration
contract, не меняя семантику всех существующих consumers.

**Зафиксированный contract.** В V1:

- порядок и наличие public `time_first_seen`/`time_last_seen` не меняются;
- оба business field остаются `NULL` во всех текущих dataframe/export schemas;
- внутренние `first_confirmed_at`, `last_confirmed_at` и `valid_until` не
  маппятся в эти колонки и не меняют public row bytes при renewal;
- observation-only renewal не bump-ит artifact revision ради `time_last_seen` и
  сам по себе не создаёт export/publish churn;
- `valid_until` остаётся internal/operator metadata;
- будущий mapping lifecycle metadata в business columns является отдельной
  policy/enrichment capability с явным revision contract;
- будущая передача deadline конкретному consumer выполняется отдельным
  versioned export/transport contract, а не добавлением поля во все текущие
  reputation-list schemas.

Архитектурная точка расширения должна находиться на mapping/export boundary:
TTL policy владеет lifecycle facts, а configured value provider целевого
артефакта решает, превращать ли их в business values. Так дальнейшее enrichment
не потребует менять сам алгоритм expiry.

**Статус:** подтверждено.

### I-10 — атомарность canonical expiry и внешняя сходимость

**Вопрос.** Какой consistency contract нужен между SQLite truth, отдельными
mutable dataframe files и доставляемым конечной системе export slice?

Единой ACID transaction через SQLite, файловую систему и DNS/firewall/SMB не
существует. Поэтому корректная модель должна разделить authoritative commit и
recoverable side effects:

```text
SQLite transaction
  history + active delete + artifact revision
                 |
                 v
        recoverable reprojection
                 |
                 v
     complete export + delivery retry
```

**Текущая рекомендация.**

- В canonical transaction с единым `asOf` атомарно сохраняются history,
  удаление active rows/provenance и revision bump для фактически изменившихся
  artifacts. Большие наборы могут обрабатываться bounded batches, но export
  нельзя открывать посередине незавершённого reconciliation cycle.
- Любой canonical read дополнительно применяет `valid_until > asOf`. Поэтому
  новый snapshot не включает логически expired row даже до её физического
  переноса reaper-ом.
- Успешный expiry commit нельзя откатывать только из-за ошибки CSV projection
  или remote delivery: это снова сделало бы устаревшую запись активной. Side
  effects переходят в degraded/retry/recovery state.
- Каждый mutable CSV можно устанавливать atomically через replace, но группу
  независимых файлов невозможно заменить одной filesystem transaction.
  Consumer, которому требуется согласованность нескольких artifacts, должен
  получать immutable complete slice с manifest/`_SUCCESS`, а не читать mutable
  directory во время обновления.
- Crash window после DB commit должен закрываться durable work marker или
  эквивалентным revision-based recovery. Точный выбор между расширением
  lifecycle ledger и projection reconciliation будет принят после определения
  consumer/SLA contract; in-memory event недостаточен.
- В healthy runtime ни один **новый** export snapshot, начатый после deadline,
  не должен содержать expired row. Уже доставленная внешняя копия исчезает
  только после следующего успешно применённого complete update; для этого нужен
  измеримый convergence bound.

Нужно определить:

1. Конечные системы читают отдельные mutable `dataframe/*_generated.csv`,
   immutable complete export slices или возможны оба варианта? Требуется ли
   одному consumer согласованная версия сразу нескольких файлов?
2. Если canonical expiry уже committed, а reprojection/export/publish упал,
   подтверждаем ли поведение: запись остаётся expired, сервис показывает
   degradation и повторяет side effect, но не возвращает запись в active set?
3. Какова допустимая задержка в исправно работающей системе между `valid_until`
   и успешным обновлением внешнего consumer: практически сразу, фиксированный
   верхний предел или очередной плановый export без отдельного SLA?

**Ответ заказчика.** Используются оба существующих способа получения данных:
mutable dataframe files и immutable export slices. Если canonical expiry уже
committed, ошибка последующей projection/export не возвращает запись в active
set. В исправно работающем сервисе реакция на deadline должна происходить
практически мгновенно.

Заказчик дополнительно уточнил scope: задача проектирует TTL и контроль качества
**на стороне ioc-extractor**. Управление конечными системами и протокол их
применения не входят в feature scope. Их следует учитывать только как boundary
constraints, не расширяя задачу до delivery orchestration.

**Зафиксированный service-local contract.**

- На logical read boundary задержка равна нулю: при `asOf >= valid_until` запись
  уже неактивна и не может попасть ни в новый canonical read, ни в начатый после
  deadline dataframe/export snapshot, даже если reaper ещё не удалил row.
- Healthy daemon должен быстро запустить физический reconciliation и обновление
  собственных derived artifacts; точный измеримый bound определяется в I-11.
- Ошибка post-commit side effect переводит собственное состояние сервиса в
  degraded/recoverable, но не отменяет expiry.
- Для mutable dataframe сохраняется существующая per-file atomic replacement
  semantics; TTL scope не вводит новую cross-file filesystem transaction.
- Complete immutable slice сохраняет существующую multi-artifact snapshot
  semantics. Что конечная система делает с файлом/slice после выдачи, находится
  за границей этой задачи.
- В V1 не проектируются команды удаления на DNS/firewall, acknowledgement
  protocol или самостоятельное управление downstream TTL.

**Статус:** подтверждено с уточнённой service-local границей.

### I-11 — near-deadline scheduling, массовое истечение и нагрузочный envelope

**Вопрос.** Какой объём и burst profile TTL-механизм обязан выдерживать, чтобы
«практически мгновенно» не превратилось в full-table polling или одну длинную
SQLite transaction?

Один fixed TTL естественно создаёт кластеры: все rows, подтверждённые одной
canonical transaction, получают близкий deadline и могут истечь одновременно.
Row-by-row timers плохо восстанавливаются после restart и масштабируются по
числу записей; частый `SELECT` без индекса и периодическое «уменьшение TTL» дают
лишний scan/write amplification.

**Текущая рекомендация.**

- Логическая актуальность остаётся точной через `valid_until > asOf`; она не
  зависит от скорости физической уборки.
- На каждой active artifact table нужен B-tree index `(valid_until, id)`: он
  обслуживает поиск ближайшего deadline, due-range и стабильный keyset batch.
- Primary daemon scheduler держит один ближайший deadline, полученный через
  indexed `MIN(valid_until)`, и пересчитывает его после commit/renewal/reaping.
  Новая более ранняя дата только reschedule/nudge-ит один timer — отдельных
  timers на rows нет.
- Редкий periodic reconcile остаётся correctness backstop для lost nudge,
  clock jump и restart; это не частый full-table scan.
- Due rows выбираются ограниченными batches по `(valid_until, id)`. Каждая batch
  transaction архивирует/deletes rows и bump-ит revisions; projection
  coalesces на завершение reconciliation cycle, а не запускается на каждый row.
- При burst expiry новые reads уже исключают весь due set. Физический reaping и
  regeneration могут продолжаться дольше reaction target, не возвращая
  устаревшие данные в canonical output.
- V1 не требует cache, partitioning, новой БД или дополнительной scheduling
  library. Нагрузочный envelope сначала закрепляется tests/bench evidence и
  SQLite query-plan assertions.

Предлагаемая конкретизация «практически мгновенно» внутри healthy service:

1. semantic exclusion — непосредственно на границе `valid_until`;
2. scheduler reaction — начало reconciliation не позднее чем через 5 секунд
   после deadline при отсутствии уже выполняющейся canonical operation;
3. completion time для массового burst измеряется и ограничивается объёмом
   данных, но expired rows всё это время не видны новым reads.

Нужно определить:

1. Каков ожидаемый и разумный предельный порядок active records: десятки тысяч,
   сотни тысяч или миллионы суммарно/на один artifact?
2. Какой крупнейший реалистичный burst может получить одинаковый deadline —
   размер одного документа, суточной загрузки или практически всей БД?
3. Приемлем ли предложенный service-local target: точное logical exclusion и
   запуск reconciliation в пределах 5 секунд, без обещания завершить
   многотысячную reprojection за те же 5 секунд?

**Ответ заказчика.** Ожидаемый рабочий объём — десятки тысяч active records,
скорее всего не более `100 000`, но размер будущих feeds заранее ограничить
нельзя. Одновременно может истечь весь active set. Точное logical exclusion и
запуск reconciliation в пределах пяти секунд приемлемы; завершение массовой
очистки не обязано укладываться в те же пять секунд.

**Зафиксированный performance contract.**

- `100 000` одновременно active/due records — обязательный V1 validation
  envelope, а не hard product limit.
- Correctness не должна зависеть от объёма: при превышении envelope допустимо
  увеличение времени физической уборки, но не возврат expired rows в reads и не
  unbounded memory growth.
- Worst-case acceptance scenario — все active records получают один deadline.
- Due selection и archive/delete используют keyset batches; ни full
  materialization в JVM, ни одна transaction на весь burst недопустимы.
- Индексированный deadline lookup должен иметь стоимость порядка поиска плюс
  размера due batch, а не полного active set на каждый scheduler tick.
- Reprojection запускается один раз на затронутый artifact после завершения
  reconciliation cycle, а не после каждой row/batch; revision при этом остаётся
  durable change truth для crash recovery.
- Пятисекундный target относится к началу service-local reconciliation в
  healthy idle daemon. Contention/overlap и полная duration цикла должны быть
  измеримы и видимы health/diagnostics, но не маскируют logical expiry.
- Batch size остаётся implementation tuning, подтверждаемым benchmark/query
  plan. Новый operator knob не вводится без evidence, что одного безопасного
  значения недостаточно.

**Статус:** подтверждено.

### I-12 — объём historical lifecycle и срок хранения

**Вопрос.** Что оператор должен суметь узнать об expired record и как долго
сервис обязан хранить этот след после удаления записи из active storage?

При worst-case burst в history одномоментно попадёт до `100 000` lifecycles, а
при повторном появлении/истечении тех же IOC история будет расти без верхней
границы. Не хранить ничего противоречит уже принятой operator visibility;
бессрочно копировать всю active schema и все observations создаёт второй
неограниченный dataframe.

Возможны три уровня audit detail:

1. **Только aggregate event:** сколько rows истекло и когда. Дёшево, но нельзя
   найти конкретный IOC/public ID и объяснить его судьбу.
2. **Lifecycle tombstone:** отдельный history ID, artifact/row identity,
   прежний public ID как evidence, snapshot business row,
   `first_confirmed_at`, `last_confirmed_at`, `valid_until`, фактический
   `closed_at` и typed close reason. Этого достаточно для поиска и объяснения.
3. **Полный observation archive:** tombstone плюс все per-source
   `first_seen_at`/`last_seen_at`/`occurrences`. Максимальная доказательность, но
   объём растёт по числу связей source×record.

**Текущая рекомендация: lifecycle tombstone с bounded provenance evidence.**

- Сохранять полный ordered business-row snapshot, identity/public ID,
  lifecycle timestamps и причину (`TTL_EXPIRED`, `LEGACY_ACTIVATION` и будущие
  typed reasons). History не участвует в row-key conflict, ID allocation,
  dataframe, export или renewal.
- Для provenance сохранить как минимум source references/count и последний
  факт подтверждения по источнику, достаточные для ответа «почему запись жила до
  этой даты». Исходный документ не дублировать: его archive lifecycle остаётся
  отдельной ответственностью.
- Конкретную SQL-форму — generic history + structured snapshot либо
  per-artifact history tables — выбирать после определения operator lookup
  patterns; opaque JSON без индексируемой identity недостаточен.
- Ввести отдельную configurable history retention. Её cleanup идёт низким
  приоритетом, bounded batches и никогда не bump-ит active artifact revisions.
- Удаление history не разрешает повторное использование public ID: high-water
  sequence живёт независимо.
- `existing-records: expire` создаёт те же tombstones с отдельным close reason,
  а не специальную необъяснимую массовую очистку.

Нужно определить:

1. Нужен ли оператору поиск конкретной expired записи по IOC/public ID/source,
   или достаточно агрегированных сведений о выполненной очистке?
2. Следует ли сохранять полную per-source observation history либо достаточно
   business-row snapshot и компактной provenance summary?
3. Какой срок хранения приемлем: фиксированный период, например 90 дней,
   конфигурируемый период с production default или бессрочное хранение до
   явной операторской очистки?

**Ответ заказчика.** Детальный поиск конкретных expired records в V1 излишен;
оператору достаточно агрегированных сведений по запросу. History всё же хранит
полный snapshot business row и компактную сводку источников. Retention
конфигурируется, production default — 30 дней.

**Зафиксированный history contract.**

- При expiry создаётся lifecycle tombstone с независимым history ID, прежними
  artifact/row/public identities, полным ordered business-row snapshot,
  lifecycle timestamps, typed close reason и compact provenance summary.
- Per-source observation rows целиком не копируются. Summary должна сохранять
  source count/references и сведения о последних подтверждениях, достаточные
  для будущего объяснения lifecycle без дублирования source document.
- В V1 отсутствуют CLI/API lookup конкретного IOC, public ID или source в
  history. Сохранённая detail — durable evidence и точка будущего расширения, а
  не обещание поискового UX в текущем scope.
- Operator read model агрегирует как минимум counts по artifact/close reason,
  объём history, время последнего expiry cycle, число закрытых/pruned lifecycle
  и oldest due age при backlog. Никаких per-IOC labels/log fields не требуется.
- Default history retention — `30d`; значение operator-configurable и должно
  быть положительным. Сокращение периода применяется bounded cleanup на
  следующем maintenance cycle, а не синхронным full delete при config binding.
- History cleanup не изменяет active revision/dataframe/export и не влияет на
  monotonic public-ID high-water.
- Retention и её destructive effect должны быть описаны в operator guide.

**Статус:** подтверждено.

### I-13 — изменение TTL policy после активации

**Вопрос.** Что должно произойти с уже сохранёнными absolute deadlines, если
оператор меняет `fixed.ttl` или возвращает mode в `disabled`?

Если каждый read пересчитывает expiry из **текущего** config, изменение `24h →
48h` задним числом перепишет смысл всех lifecycles без DB transaction/audit, а
`24h → 1h` может мгновенно отозвать весь active set. Это противоречит модели, в
которой `valid_until` является зафиксированным результатом accepted observation.

Для изменения duration есть два основных контракта:

1. **Prospective:** уже записанный `valid_until` не меняется; новый TTL
   применяется при следующем successful confirmation или создании новой
   lifecycle.
2. **Retroactive:** на startup пересчитать все active deadlines как
   `last_confirmed_at + new_ttl`, создавая массовое продление или expiry.

**Текущая рекомендация: prospective duration changes.**

- `RecordValidityPolicy` вычисляет абсолютный deadline в момент accepted
  observation; repository хранит результат, а reads не консультируются с
  текущей duration.
- И увеличение, и уменьшение TTL влияют только на последующие confirmations.
  Это исключает скрытую массовую data migration обычной правкой config.
- Startup reporting показывает, что effective policy изменилась, но не
  переписывает active rows. При необходимости retroactive пересчёта в будущем
  он оформляется отдельной named/audited migration policy.

`disabled` после activation сложнее. Если просто перестать применять
`valid_until > asOf`, ещё не удалённые expired rows могут снова появиться. Если
обнулить deadlines, это неявно превращает все active records в бессрочные. Если
прекратить только новые renewals, получится смешанное состояние с неожиданным
истечением старых rows.

**Текущая рекомендация: activation является one-way для данной БД.** После
persisted перехода `disabled → fixed` запуск с `mode=disabled` отклоняется
стабильной configuration/lifecycle diagnostic. Безопасный возврат требует
восстановить согласованные pre-activation config+DB либо в будущем реализовать
отдельную explicit deactivation migration; простое удаление YAML-ключа не
должно менять сохранённые lifecycle facts.

Нужно определить:

1. При изменении, например, `24h → 48h` или `24h → 12h` приемлемо ли сохранять
   прежние deadlines до следующего подтверждения каждой записи?
2. Приемлем ли one-way activation contract: после первого включения TTL нельзя
   просто вернуть `disabled` на той же БД?
3. Нужна ли уже в V1 отдельная аварийная операция массового продления/отключения
   deadlines, или достаточно backup/rollback и последующих confirmations?

**Ответ заказчика.** Prospective duration changes приемлемы: существующие
deadlines сохраняются до следующего подтверждения. Activation является one-way
для данной БД; простое возвращение `disabled` запрещено. Отдельная аварийная
bulk-операция в V1 не нужна — достаточно согласованного backup/rollback и новых
successful confirmations.

**Зафиксированный policy-transition contract.**

- Persisted `valid_until` является lifecycle fact и никогда не пересчитывается
  лениво из текущего config.
- Новая fixed duration применяется только при создании lifecycle или следующем
  accepted observation существующей active record.
- DB хранит факт activation/policy state. После `disabled → fixed` startup с
  `disabled` fail-fast отклоняется; отсутствие YAML-ключа не деактивирует TTL.
- Retroactive recompute, mass extension, suspension и deactivation отсутствуют
  в V1. Если когда-нибудь понадобятся, каждая станет отдельной named,
  audited/crash-recoverable migration, а не дополнительным смыслом duration.
- Единственный V1 rollback после activation восстанавливает согласованные
  pre-activation application/config/DB bytes. Откат только config или только
  jar не поддерживается.
- Operator guide обязан объяснить prospective effect изменения duration и
  one-way характер activation до её выполнения.

**Статус:** подтверждено.

### I-14 — health, diagnostics и ручное управление

**Вопрос.** Когда runtime TTL lag является только наблюдаемой деградацией, а
когда сервис должен fail closed и перестать принимать stateful work?

Health indicator сам не должен управлять процессом: он только читает state.
Admission barrier и lifecycle use case принимают решение независимо, иначе
HTTP-observability начинает владеть business correctness.

**Текущая рекомендация по состояниям.**

- `UP`: mode ещё легально `disabled` до activation либо `fixed` активен,
  scheduler/reconciliation укладываются в target, durable backlog отсутствует.
- `DEGRADED`: logical read predicate по-прежнему гарантирован, но oldest due
  backlog старше 5 секунд, reconciliation/reprojection выполняет retry либо
  последний cycle завершился recoverable failure. Intake может продолжаться,
  потому что новые observations способны продлевать lifecycles; expired rows
  всё равно не видны canonical reads.
- `DOWN`: lifecycle metadata/policy state невозможно прочитать или изменить,
  persisted policy не согласуется с config, startup barrier упал либо нельзя
  гарантировать `valid_until > asOf` на read path. Readiness, daemon intake и
  stateful `extract`/`export` остаются закрыты до recovery.

Projection/export failure после canonical expiry остаётся в соответствующем
существующем health contributor и не меняет lifecycle обратно. Общий actuator
уже агрегирует `DEGRADED` как HTTP 200, а `DOWN` как неготовность; новый TTL
contributor должен следовать этой taxonomy.

**Предлагаемый read-only health detail без IOC cardinality:**

- effective `mode`, activation state и configured TTL/history retention;
- `nextExpiryAt`, `dueRecords`, `oldestDueAgeSeconds`;
- cycle running/start/completion/duration, processed/remaining counts;
- last success/failure и retry state;
- history size и last-pruned count;
- pending affected artifacts/reprojection count, но без IOC values, row keys и
  source names.

**Diagnostics/logging contract.** Lifecycle получает stable typed codes как
first-class business area (рабочее имя `LIFECYCLE.*`): policy-state mismatch,
activation/reconciliation/history-cleanup failure и recovery. Started/completed
cycle logs содержат aggregate counts, duration и `asOf`; empty healthy cycles
идут в DEBUG, фактическая очистка — INFO, lag/retry — WARN, потеря safety
invariant — ERROR/FATAL. Per-record INFO/WARN logging запрещён.

**Ручное управление.** Для V1 рекомендуется не добавлять mutating CLI:
deadline scheduler, periodic reconcile, startup recovery и precondition у
stateful commands уже образуют automatic recovery paths. Оператор использует
существующий `ioc health`/actuator и documented restart/backup rollback.
Отдельный `reconcile-now` следует вводить только при подтверждённой
эксплуатационной необходимости; `expire-all`, `extend-all` и `disable-now`
явно вне scope.

Нужно определить:

1. Приемлемо ли оставлять intake открытым в `DEGRADED`, пока logical filtering
   доказуемо работает, и закрывать его только при `DOWN`/потере safety
   invariant?
2. Достаточен ли перечисленный aggregate health, без поиска/вывода отдельных
   IOC и sources?
3. Согласен ли заказчик не добавлять в V1 manual mutating/reconcile CLI и
   полагаться на automatic retry, startup recovery и backup/rollback?

**Ответ заказчика.** Intake не следует останавливать при `DEGRADED`, если
logical filtering продолжает гарантированно исключать expired records. Общей
TTL-статистики в health достаточно. Manual mutating/reconcile CLI в V1 не
включается.

**Зафиксированный operational contract.**

- Recoverable lag при сохранённом read invariant не блокирует новые inputs;
  automatic scheduler/retry продолжает convergence.
- `DOWN` и fail-closed admission наступают только когда сервис не может
  доказать корректность lifecycle state/read filtering либо выполнить
  обязательный startup barrier.
- Health остаётся read-only aggregate без IOC/source cardinality. Детальный
  history lookup и новый control endpoint не входят в V1.
- Lifecycle diagnostic codes и aggregate ECS events являются основной forensic
  поверхностью; штатные пустые cycles не создают INFO noise.
- Ручные `reconcile-now`, `expire-all`, `extend-all` и `disable-now` отсутствуют.
  Stateful commands всё равно выполняют обязательный automatic precondition,
  а operator recovery использует restart и согласованный backup/rollback.

**Статус:** подтверждено.

### I-15 — гонка confirmation×expiry и ненадёжные системные часы

**Вопрос.** Как упорядочить одновременное подтверждение и expiry и может ли
перевод системных часов назад сделать запись снова активной?

Первый пограничный сценарий:

```text
09:59:55  файл обнаружен и parsing начался
10:00:00  прежний valid_until
10:00:01  canonical transaction получает право на запись
```

По уже принятому I-05 время detection/parsing не подтверждает актуальность.
Следовательно, commit после boundary не продлевает старую lifecycle: она
закрывается, а observation создаёт новую lifecycle/public ID. Иначе длинный ETL
или ожидание DB lock позволяли бы задним числом оживлять данные.

Второй сценарий — expiry scheduler и canonical observation одновременно
работают с одним `row_key`. In-memory check-then-act недостаточен. Требуется один
линеаризуемый порядок на canonical write boundary:

- confirmation, атомарно принятый до deadline/closure, renews текущую lifecycle;
- closure, выигравший первым, архивирует прежнюю lifecycle, после чего
  confirmation создаёт новую;
- confirmation не теряется, history не реактивируется, а result не зависит от
  порядка доставки in-memory event;
- transaction использует один `asOf`, взятый после получения write ownership;
  conditional SQL/constraints являются authority, application locks — только
  contention optimization.

**Ответ заказчика по сценариям 1–2.** Оба контракта подтверждены:

- observation, committed после прежнего `valid_until`, считается новой записью,
  даже если файл был обнаружен до deadline;
- при одновременных expiry и confirmation подходит transaction-order contract
  без потери observation.

Третий сценарий — wall clock correction. Заказчик уточнил, будет ли механизм
зависеть от системных часов.

Да: для абсолютного TTL сервису нужен источник календарного UTC-времени.
Требование I-06 говорит, что TTL продолжает истекать во время остановки процесса
и после restart. Один процессный monotonic timer этого обеспечить не может:
после остановки он отсутствует, после запуска начинает новую шкалу и не позволяет
определить, истёк ли deadline за время downtime. SQLite `CURRENT_TIMESTAMP` не
устраняет зависимость — без отдельного time service база использует те же часы
операционной системы. Внешний time service в V1 добавил бы сетевую доступность,
latency и ещё одну распределённую failure boundary, не устраняя необходимости
локально переживать его недоступность.

Зависимость должна быть явной и контролируемой:

- application/domain lifecycle logic получает время через один injected `Clock`;
  scattered `Instant.now()`/`System.currentTimeMillis()` не являются частью
  контракта;
- все deadlines и подтверждения хранятся как UTC `Instant`, поэтому local
  timezone и DST на них не влияют;
- одна canonical transaction использует один `asOf`, полученный после захвата
  write ownership; это время становится durable confirmation только при
  успешном commit;
- monotonic elapsed clock (`System.nanoTime()` или абстракция над ним) управляет
  ожиданием scheduler-а, backoff и измерением duration, но не решает, активна ли
  business-запись после restart;
- синхронизация системного времени остаётся operator prerequisite, однако
  корректность не должна молча полагаться на идеальные часы: откат обнаруживается,
  а effective lifecycle time не уменьшается.

Forward jump может сразу сделать due весь active set — batch contract I-11 это
допускает. Backward jump опаснее: если снова сравнивать с меньшим `now`, ещё не
reaped row может выглядеть active.

**Текущая рекомендация по clock safety.**

- Сервис хранит durable high-water effective time и никогда не уменьшает
  lifecycle `asOf` после restart или clock correction.
- Небольшой backward step clamp-ится к high-water и отражается как
  `DEGRADED`; deadlines не сдвигаются назад и records не воскресают.
- Существенный/длительный clock rollback переводит lifecycle в `DOWN` и
  закрывает stateful work: лучше остановиться, чем формировать новые deadlines
  от недостоверного времени. Exact tolerance определяется implementation
  evidence и operator contract, а не скрытой константой.
- Monotonic elapsed clock управляет ожиданием scheduler-а, но не заменяет
  persisted UTC instants в business data.
- Уже закрытая lifecycle не реактивируется ни при каком восстановлении часов.

**Оценка.** Полностью отказаться от wall clock можно только изменив семантику:
приостанавливать TTL вместе с процессом либо требовать непрерывно доступный
внешний источник времени. Первое уже отвергнуто I-06, второе несоразмерно V1.
Поэтому рекомендуемый контракт — controlled wall-clock dependency с двумя
разными назначениями clock, durable high-water и fail-closed при недостоверном
времени.

**Ответ заказчика.** Системный UTC clock принят как изолированная зависимость.
Принята и failure policy: lifecycle time не идёт назад; небольшой clock rollback
даёт `DEGRADED` и clamp к durable high-water, существенный/длительный rollback —
`DOWN`, forward jump честно запускает expiry. Exact tolerance предстоит вывести
из implementation/operations evidence и сделать явной частью operator contract.

**Статус:** подтверждено.

### I-16 — безопасный cutover и граница rollback

**Вопрос.** Как впервые активировать TTL на существующей installation и до какого
момента pre-activation backup остаётся безопасной rollback point?

**Уточнение простыми словами.** Для уже работающей установки есть два способа:

- одновременно обновить программу и включить TTL одним запуском;
- сначала обновить программу, оставив TTL выключенным, убедиться, что новая
  версия нормально запускается, а затем отдельным явным действием сделать
  backup, включить TTL в config и ещё раз запустить service.

Рекомендуется второй способ. Эти шаги можно выполнить подряд в одном maintenance
window; ждать день или отдельный релиз не требуется. Дополнительный запуск нужен,
чтобы не смешивать две разные причины возможной ошибки: обычную несовместимость
новой версии и destructive activation, которая закрывает все legacy records.

Это сложнее обычного application rollback. После успешной activation новые
observations получают новые lifecycle/public IDs, а legacy active set уже
перемещён в history. Если через несколько часов просто вернуть pre-activation
DB, будут одновременно потеряны новые confirmations и восстановлены записи,
которые оператор намеренно отозвал.

Текущий deployment contract частично помогает, но не закрывает всю операцию:

- `deploy-local-root.sh` останавливает service и сохраняет обе SQLite DB как одну
  recovery point;
- при failed health gate он возвращает прежний application symlink и обе DB;
- operator-owned `etc/application.yml` автоматический rollback не возвращает;
- generated projections, перемещённые inputs и уже завершённые side effects не
  входят в DB rollback;
- старый binary может не понимать новую lifecycle configuration, поэтому
  rollback jar+DB при сохранённом `mode=fixed` не является согласованным.

**Текущая рекомендация: двухэтапный rollout для существующей установки.**

1. **Compatibility upgrade.** Сначала развернуть TTL-capable binary с прежним
   effective `mode=disabled`. Он проходит обычный health gate, но не меняет
   canonical lifecycles. Само обновление приложения остаётся обратимым.
2. **Explicit lifecycle cutover.** В отдельное maintenance window остановить
   intake/stateful commands, сохранить exact active config и согласованный
   snapshot **обеих** SQLite DB, затем включить `mode=fixed` и
   `existing-records: expire` и перезапустить тот же binary.
3. **Startup barrier.** До readiness/intake/export lifecycle activation
   транзакционно фиксирует policy state, закрывает legacy lifecycles и запускает
   idempotent reconciliation/reprojection. До завершения barrier никакой новый
   input не принимается.
4. **Cutover accepted.** Успешная readiness означает, что installation приняла
   новую one-way lifecycle policy. После этого background/stateful work уже
   может начаться, поэтому основной recovery путь — roll-forward и idempotent
   retry, а не возврат к старой semantics.

Failure contract предлагается разделить по границе видимости:

- failure до durable activation commit откатывает текущую transaction и оставляет
  service `DOWN`; повторный startup безопасен;
- failure после activation commit, но до readiness восстанавливается
  idempotent startup reconciliation; если rollout решено отменить, оператор
  возвращает **вместе** сохранённые config и обе DB;
- mutable CSV projections не являются rollback truth: после DB restore
  TTL-capable binary обязан принудительно привести их к восстановленной
  canonical DB до readiness, а не доверять оставшимся post-activation files;
- после успешной readiness pre-activation snapshot считается operationally
  stale, потому что intake/background work уже может начаться. Restore
  допускается только как явно аварийное disaster recovery в maintenance window
  с учётом возможных потерянных confirmations и ручной повторной подачей
  доверенных inputs;
- отдельный rollback только jar, config, canonical DB или service DB не
  поддерживается.

Сервис не способен доказать, что внешний backup действительно существует,
полон и проверен restore-тестом. Поэтому не рекомендуется добавлять скрытую
проверку каталога backups или ещё одну mutating CLI-команду. Явная destructive
policy является подтверждением намерения, а создание и проверка recovery point
остаются обязательным и подробно документированным operator precondition.

Нужно определить:

1. ~~Приемлемо ли для существующей установки включать TTL отдельным явным
   шагом?~~ **Да, принято.** Сначала новая версия успешно запускается с
   выключенным TTL, затем оператор делает backup, изменяет config и перезапускает
   service с TTL. Оба запуска могут идти подряд в одном maintenance window.
2. ~~Приемлемо ли считать успешную readiness границей обычного rollback?~~
   **Да, принято.** До неё pre-activation backup используется для обычного
   отката. После неё основной путь — исправление вперёд/retry, а restore старого
   backup является disaster recovery с возможной потерей новых confirmations и
   ручной повторной подачей inputs.
3. ~~Должно ли приложение проверять наличие пригодного backup?~~ **Нет,
   принято.** Перед включением TTL оператор обязан сохранить exact config и обе
   SQLite DB и заранее иметь проверенную процедуру restore. Это явная обязанность
   deployment/operator procedure; service не ищет backup-файл и не требует
   специальный confirmation token при startup.

**Ответ заказчика по пунктам 1–3.** Отдельный явный activation restart после
успешного compatibility startup принят. Успешная readiness принята как граница
обычного rollback; последующий restore pre-activation state является только
осознанным disaster recovery. Наличие и restore-пригодность согласованного
config+DB backup обеспечиваются operator procedure, а не application runtime.

**Статус:** подтверждено.

### I-17 — обязательные доказательства перед релизом

**Вопрос.** Какие проверки являются частью самой TTL feature, без которых её
нельзя считать готовой, а какие допустимо оставить как последующий hardening?

Обычного happy-path test и общего процента coverage здесь недостаточно. Feature
меняет canonical truth, выполняет destructive legacy activation и конкурирует с
ingestion. Ошибка может не проявиться исключением: service останется `UP`, но
покажет expired record, потеряет confirmation или повторно использует public ID.

**Текущая рекомендация: следующий correctness set является release-blocking.**

1. **Deterministic lifecycle semantics.** Tests с injected controllable `Clock`,
   без реальных ожиданий, покрывают boundary `valid_until == asOf`, renewal,
   prospective TTL change, expiry→new lifecycle/public ID, duplicate bulk
   confirmation и неизменность публичных `time_* == NULL`.
2. **Transaction/race contract.** На реальной SQLite проверяется порядок
   confirmation×expiry для одного `row_key`: observation не теряется, history
   не реактивируется, IDs не переиспользуются, failed transaction не считается
   confirmation. Concurrency test использует управляемые barriers/latches, а не
   вероятностный `sleep`.
3. **Migration and crash recovery.** Upgrade fixture формата 0.2.0 проходит
   `existing-records: expire`; fault injection на границах activation,
   history move, revision/reprojection и restart доказывает idempotence и
   отсутствие частично видимого active set.
4. **Every read/projection contract.** Canonical reads, mutable dataframe и
   новые immutable export slices используют один `asOf` и исключают expired
   rows, даже когда physical reaper ещё не завершён. Restore pre-activation DB
   заставляет mutable projections сойтись с восстановленной canonical truth до
   readiness.
5. **Both runtime modes and configuration UX.** Stateful oneshot и daemon
   проверяются отдельно; legacy upgrade остаётся `disabled`, fresh-install
   preset запускается с `fixed`, invalid/one-way policy transitions получают
   стабильные diagnostics, clock rollback даёт принятые `DEGRADED`/`DOWN`.
6. **Repository/application contracts.** Storage-neutral port behavior
   закрепляется reusable TCK там, где несколько implementation/call paths
   должны соблюдать одинаковые lifecycle invariants; SQLite-specific SQL и
   indexes проверяются adapter integration tests.

**Mass-expiry evidence для согласованного worst case.** Отдельный воспроизводимый
scenario создаёт 100 000 одновременно due active records и доказывает:

- logical reads перестают показывать их точно на deadline;
- healthy idle daemon начинает reconciliation не позднее принятых 5 секунд;
- processing идёт bounded batches без загрузки всего set в Java memory и без
  одной длинной write transaction;
- projection выполняется один раз на затронутый artifact/cycle, а не на row или
  SQL batch;
- backlog в итоге полностью дренируется, history/revision/health counters
  согласованы, новые confirmations могут продвигаться без starvation.

Фиксировать сейчас универсальное требование вроде «100 000 rows очищаются за
10 секунд» не рекомендуется: wall time на shared CI зависит от CPU, disk и
filesystem. Correctness и отсутствие unbounded behavior должны блокировать
каждый build. Wall-time/throughput следует измерить на описанной reference
environment перед релизом, сохранить baseline и только после измерения выбрать
реалистичный regression threshold.

**Не входят в V1 release gate:** multi-day soak, внешняя target-system delivery,
per-source/per-type TTL policies, public `valid_until`, manual expiry CLI и
retroactive mass TTL changes. Это не ослабляет canonical correctness scope.

**Ответ заказчика.** Все три положения приняты:

1. correctness/crash/race matrix является частью TTL feature, а не будущим
   hardening;
2. 100k simultaneous-expiry scenario обязателен как pre-release evidence;
3. total-drain/throughput threshold определяется после измерения на описанной
   reference environment; независимый 5-second start target уже обязателен.

**Зафиксированный release-evidence contract.** Обычный build блокируется
детерминированными correctness/integration/recovery tests. Нагрузочный scenario
обязан быть воспроизводимым и выполненным перед релизом, но произвольный
wall-time timeout нестабильного shared CI не выдаётся за SLA. Измеренный baseline,
hardware/filesystem profile и последующий regression threshold становятся частью
release evidence.

**Статус:** подтверждено.

### I-18 — итоговая component/schema decomposition и duplicate evidence

> **Historical note.** This section preserves the recommendation that existed
> before I-20. Its statements about expiry advancing `artifact_revision` and
> the `(_valid_until, id)` key are superseded by I-20 and the reviewed candidate
> in `architecture-project.md`: immutable export revision remains insert-driven,
> mutable projection uses a separate generation, and the uniform due index uses
> technical epoch time plus lifecycle identity.

**Вопрос.** Как встроить lifecycle в существующий canonical storage без второго
источника истины и сколько source evidence необходимо хранить ради duplicate
fast path?

#### Live-code findings

Текущая реализация задаёт несколько жёстких integration constraints:

- `JdbcCanonicalArtifactRepository` владеет одной transaction на artifact:
  public insert, provenance upsert и revision bump;
- `load()` и `JdbcSnapshotSliceReader` пока читают все rows без lifecycle
  predicate;
- `clock.instant()` сейчас вызывается отдельно на rows/revision, тогда как TTL
  требует один transaction/snapshot `asOf`;
- artifact tables создаются из config additive-only reconciler-ом, а internal
  columns уже отделены prefix `_` от public CSV schema;
- current `id.start:auto` после restart вычисляется как `MAX(id)+1` только по
  active table. После удаления максимального expired row это повторно выдаст
  прежний public ID и прямо нарушит I-03;
- CSV projection является производной filesystem-копией; daemon `ingest_run`
  закрывает ingest write→project crash window, но lifecycle является отдельным
  durable workflow и не должен перегружать чужой ledger;
- terminal daemon duplicate сейчас пропускает pipeline целиком, но ledger не
  хранит список canonical rows, которые необходимо подтвердить.

Следовательно, TTL нельзя безопасно добавить как отдельный `DELETE WHERE` job.
Canonical write, read, ID allocation, revision и projection recovery должны
измениться одним согласованным slice.

#### Рекомендуемое размещение компонентов

```text
core/ioc-application
  artifact/lifecycle
    RecordValidityPolicy -> FixedRecordValidityPolicy (V1)
    lifecycle values/results + reconciliation/activation services
  port/out/artifact
    lifecycle-aware canonical store
    durable public/internal ID allocator
    lifecycle status/projection-work ports

adapter-store-jdbc
  SQLite migrations + per-artifact lifecycle/history schema
  transactional confirm/close/archive/revision/projection-work SQL
  indexed bounded scans + aggregate status queries

adapter-sink-csv
  remains a projection adapter; no TTL policy or scheduler

bootstrap/ioc-app
  IocProperties validation, lifecycle admission/startup barrier,
  daemon deadline scheduler, read-only health and composition
```

`core/ioc-domain` не получает Spring/JDBC/CSV и пока не меняется: речь идёт о
canonical artifact lifecycle application layer, а не о новой IOC taxonomy.
`RecordValidityPolicy` остаётся малой Strategy; factory hierarchy, decorator chain
и rules engine в V1 не нужны.

Существующий `CanonicalArtifactRepository` должен эволюционировать в
lifecycle-aware atomic port либо быть заменён одним таким портом. Нельзя сначала
писать business row через старый repository, а потом отдельным вызовом добавлять
expiry: crash между вызовами создаст бессрочную или неподтверждённую запись.

#### Рекомендуемая физическая модель

Lifecycle fields размещаются непосредственно в каждой active artifact table:

```text
_lifecycle_id
_first_confirmed_at
_last_confirmed_at
_valid_until
```

Это лучше центральной polymorphic table с парой `artifact + row_id`:

- DB row и его lifecycle меняются одной transaction без слабой generic FK;
- каждый current read получает простой predicate `_valid_until > :asOf` без
  join;
- существующий table-per-artifact/config-driven schema pattern сохраняется;
- на каждом artifact создаётся range index `(_valid_until, id)`; scheduler
  опрашивает небольшой configured artifact catalog, а не создаёт per-row timer;
- TTL остаётся свойством конкретной canonical artifact record и не связывается
  с IOC type или source.

До activation lifecycle columns могут быть `NULL` только у legacy rows. После
persisted `ACTIVE` state repository/startup invariant запрещает active row с
неполным lifecycle. Fresh rows сразу создаются полностью.

Для каждого artifact reconciler создаёт зеркальные history tables:

- `<artifact>_history` хранит тот же ordered business snapshot плюс
  `_lifecycle_id`, прежний storage/public ID, `row_key`, confirmation/deadline,
  фактические `closed_at` и `close_reason`;
- `<artifact>_history_sources` хранит compact per-source summary:
  `source_key`, first/last observation и aggregate occurrences, но не raw input
  events;
- index `(closed_at, lifecycle_id)` поддерживает bounded 30-day retention;
- additive public schema change добавляет nullable column и в history; старый
  snapshot не переписывается, destructive drift по-прежнему fail-closed.

Так full typed snapshot сохраняется без JSON/BLOB codec, Jackson dependency в
JDBC adapter и потери column order/type semantics.

Stable format migration дополнительно создаёт:

- singleton lifecycle control/activation state с durable UTC high-water;
- resumable per-artifact activation progress;
- global monotonic internal lifecycle sequence;
- per-artifact durable public ID allocator state;
- per-artifact required/projected revision work state;
- source confirmation receipt metadata для duplicate fast path.

#### Transaction contracts

Одна lifecycle-aware artifact transaction получает write ownership, затем один
effective UTC `asOf` и для каждого observation выполняет ровно один вариант:

1. active row и `_valid_until > asOf` — renew timestamps/deadline и provenance;
   public bytes/revision не меняются;
2. row существует, но уже due — прежняя lifecycle архивируется/удаляется, затем
   observation создаёт новую lifecycle и новый public ID;
3. row отсутствует — создаётся новая lifecycle;
4. любой failure откатывает business row, provenance и lifecycle вместе.

Expiry reconciliation выбирает keyset-batch через `(_valid_until, id)`, копирует
business/provenance snapshot в history, удаляет active row и в той же transaction
двигает artifact revision и durable projection-work target. Каждая DB transaction
ограничена; после cycle projection выполняется один раз на affected artifact.
Crash после canonical commit лишь оставляет durable work pending и не возвращает
expired row в reads.

History retention удаляет только historical rows bounded batches и не меняет
public revision. 0.2.0 activation использует persisted `ACTIVATING` state и
resumable batches, пока intake/readiness закрыты; это избегает одной огромной
100k transaction. `ACTIVE` публикуется лишь после завершения canonical migration
и обязательной projection convergence.

#### ID, clock и read contracts

Public ID reservation переносится из process-local `MAX(id)+1` baseline в
durable per-artifact allocator. Range резервируется отдельным durable шагом до
canonical commit, поэтому failed range остаётся gap и никогда не возвращается.
Internal `_lifecycle_id` выдаётся отдельной global durable sequence. Внешний
source ID по I-03A остаётся будущей namespaced provenance relation и не
подменяет ни один allocator.

Lifecycle time authority использует injected system UTC `Clock`, durable
high-water и pure clock-safety policy. Каждый logical read/snapshot получает один
effective `asOf`; multi-artifact export передаёт его всем SELECT одной SQLite
snapshot. Mutable projection получает один `asOf` на artifact. Health читает
aggregate state и не продвигает clock/state.

Все canonical read paths используют `_valid_until > :asOf`, поэтому correctness
не зависит от скорости physical cleanup. `artifact_revision` двигается при
insert/remove/replacement, но не при renewal. Expiry transaction upsert-ит
durable projection target; CSV atomic replace подтверждает projected revision
после установки файла. Crash между file replace и ack приводит только к
безопасной повторной projection.

#### Startup and scheduling

Один idempotent lifecycle admission gate используется всеми stateful entry
points:

```text
schema/identity recovery
  -> lifecycle policy/clock validation
  -> resume activation if needed
  -> expire due rows
  -> converge pending mutable projections
  -> open daemon intake or execute stateful oneshot/export
```

Health остаётся read-only. Daemon scheduler использует nearest-deadline wake-up
и periodic backstop не реже принятого 5-second target; rejections/failures
оставляют durable work для retry. In-memory event может ускорить reprojection/
export nudge, но не является correctness authority.

#### Duplicate fast path: обнаруженное противоречие

Чтобы identical daemon duplicate подтверждал records без ETL, недостаточно
terminal source ledger или текущих `<artifact>_sources`. После expiry active row
удалён, после 30-day history retention исчезает и snapshot. Для создания новой
lifecycle без parsing требуется durable **source confirmation receipt**:

- complete source key + processing-policy fingerprint;
- prepared per-artifact business row templates без service-owned IDs;
- row keys/source provenance и completion marker.

Fast path безопасен только при complete receipt и совпадении fingerprint текущих
parser/refang/classification/mapping/identity/failure policies. При fingerprint
drift старый prepared result нельзя молча переиграть: source должен пройти
актуальный ETL/checkpoint заново.

Если обещание «duplicate никогда не запускает ETL» действует бессрочно, receipts
должны жить столько же, сколько terminal duplicate ledger. Это дублирует
prepared rows каждого когда-либо принятого source и создаёт unbounded storage
growth, несмотря на 30-day lifecycle history retention.

Технически безопасная рекомендация — считать no-ETL fast path bounded
optimization: хранить complete receipts ограниченное время; при отсутствующем/
устаревшем receipt новый доставленный файл снова проходит обычный ETL и затем
подтверждает или создаёт records. Correctness сохраняется, меняется только цена
редкого старого duplicate.

**Ответ заказчика по duplicate receipt.** Оба предложения приняты:

1. no-ETL duplicate path является bounded optimization и используется только
   при complete receipt с совпадающим processing-policy fingerprint;
2. receipt retention использует тот же configurable срок `30d`, что и lifecycle
   history. После retention или policy drift identical file проходит обычный
   ETL/checkpoint заново.

Для fresh-install production template заказчик выбрал fixed TTL `12h`.

**Риск, требующий явного подтверждения.** `12h` меньше обычного суточного feed
cadence. Например, record, последний раз подтверждённая в 09:00, станет
неактивной в 21:00; если следующий документ приходит только в 09:00 следующего
дня, она отсутствует в active dataframe 12 часов. Даже если в 11:00 пришёл
второй неполный документ, records, которых в нём не было, не продлеваются по
I-04 и всё равно истекут в 21:00.

Это не техническая ошибка: такой результат честно реализует строгую freshness
policy. Но если требуется непрерывность между daily feeds, TTL обычно должен
быть больше максимального интервала подтверждения плюс delivery/scheduler
jitter, например не меньше `24h` и практичнее около `36h` для суточного cadence.

**Ответ заказчика по default TTL.** Возможный пустой active set и окна без
records между feeds являются нормальным ожидаемым состоянием. Поэтому строгий
fresh-install production default `12h` принят осознанно. Он остаётся
operator-configurable; classpath/upgrade default по-прежнему `disabled`.

**Статус:** подтверждено.

### I-19 — формальный scope change 0.3.0 и implementation slicing

**Вопрос.** Как включить новую business feature в уже принятый engineering
release contract и не получить частично активируемую реализацию?

#### Почему нужен формальный scope change

`engineering-release.md` явно исключает новые business features и разрешает их
только отдельным scope decision по §11. DATA-TTL-01 изменяет:

- canonical SQLite schema, identity/ID allocation и read semantics;
- upgrade/rollback и operator configuration;
- mutable projections/export membership;
- daemon/oneshot startup, health и scheduling;
- release performance, migration и compatibility evidence.

Скрыть эту работу под `R030-QUAL`, `R030-TEST` или `R030-REL` нельзя: эти goals
проверяют качество/готовность существующего поведения, но не являются owner-ом
новой data-lifecycle capability.

**Текущая рекомендация:** добавить отдельный release-blocking MUST-goal
`R030-DATA` с work item `DATA-TTL-01`. Scope change должен:

- изменить утверждение «новые business features не входят» явным исключением
  для canonical record lifecycle TTL;
- добавить goal contract, dependency edge к `R030-TEST`, `R030-DOC` и
  `R030-REL`, а также строки status matrix/evidence;
- зафиксировать влияние на compatibility и release critical path;
- назвать целевые modules: `core/ioc-application`,
  `core/ioc-application-tck`, `adapter-store-jdbc`, `adapter-sink-csv`,
  `adapter-ingest`, `bootstrap/ioc-app`, packaging и affected docs;
- не расширять scope на downstream target management, per-source TTL,
  public `valid_until`, manual mutation CLI или другие исключения I-01..I-18.

#### Рекомендуемые implementation slices

Изменение следует выполнять inward→outward reviewable slices. Ни один
промежуточный slice не включает TTL для действующей установки или fresh template.

1. **DATA-TTL-01/P0 — decision and characterization.** Новый ADR о canonical
   lifecycle/expiry, release scope change, goal/work-item contract, regression
   characterization текущих ID/read/projection/startup semantics.
2. **P1 — application contracts.** Pure lifecycle values, fixed policy, commands/
   results, storage/projection/status ports и reusable TCK. Без Spring/JDBC и без
   runtime activation.
3. **P2 — durable storage foundation.** Versioned SQLite migrations,
   per-artifact lifecycle/history schema, control/progress, durable internal/
   public ID allocators и projection-work state. `mode=disabled` сохраняет
   прежнее observable behavior.
4. **P3 — lifecycle-aware canonical transaction/read path.** Atomic
   confirm/renew/new-lifecycle semantics, one `asOf`, active predicates во всех
   canonical/projection/export reads, revision rules и receipt writer. После
   slice feature всё ещё не активируется production preset-ом.
5. **P4 — expiry/recovery runtime.** Bounded reaper/history retention,
   projection convergence, startup/admission gate, deadline scheduler, durable
   clock safety, health/diagnostics and crash/race integration coverage.
6. **P5 — ingestion duplicate and activation UX.** Fingerprinted 30-day receipt
   fast path с safe ETL fallback, `existing-records: expire`, one-way config,
   two-step upgrade flow и оба runtime modes.
7. **P6 — release closure.** Fresh template `fixed/12h`, operator/developer docs,
   generated diagnostic/config references, packaging upgrade/rollback tests,
   100k reference-environment evidence и fresh full-reactor `make verify`.

P2–P5 могут оставлять additive dormant schema/code на feature branch, но merge/
release допускается только как одна завершённая capability с закрытым P6. Это
не требует одного огромного commit: checkpoints остаются небольшими и
проверяемыми, а activation surface появляется последней.

#### Documentation outcome

Worknote остаётся discovery/execution evidence и не становится authority.
До production code необходимо создать ADR (следующий свободный номер после live
audit) и goal/work-item contract. В тех же slices обновляются `docs/dev/storage`,
`processing`, `artifact-export`, `ingestion`, `configuration`, `observability`,
architecture/module maps, operator deployment/daemon guides и release notes —
только там, где контракт действительно затронут.

**Ответ заказчика.** Все три положения приняты:

1. DATA-TTL-01 становится отдельным release-blocking scope change 0.3.0 под
   новым MUST-goal `R030-DATA`;
2. ordering P0–P6 и checkpoint model приняты, но частичная activation или
   release до закрытия полного evidence запрещены;
3. сначала оформляются ADR и release/work-item plan для review; production code
   начинается только после следующего явного implementation go-ahead.

**Зафиксированный execution contract.** Detailed TTL worknotes, план и будущее
evidence хранятся отдельным bundle `docs/worknote/0.3.0/data-ttl-01/`. В общих
release worknotes остаются только registration/status links; авторитетный ADR
по правилам репозитория находится в `docs/ADR/`.

**Статус:** подтверждено; интервью завершено.

### I-20 — кто создаёт новый immutable export slice после expiry

**Вопрос заказчика.** Раньше новый export slice появлялся после поступления
источника и canonical write. Что инициирует export, когда active membership
меняется только из-за TTL и нового ingest run нет? Был ли этот переход явно
зафиксирован?

#### Что уже обсуждено

В I-02, I-10, I-11 и I-18 подтверждены отдельные части контракта:

- уже созданные immutable slices не переписываются;
- любой **новый** snapshot с `asOf >= valid_until` не содержит expired record,
  даже если physical reaper ещё не удалил row;
- expiry/removal изменяет active membership и должен двигать artifact revision;
- canonical expiry не откатывается при failure projection/export;
- revision/projection work является durable truth, а event — только latency
  hint;
- expiry batches coalesce-ятся, а projection не запускается на каждую row.

Однако точный переход `TTL deadline → automatic immutable export attempt` не
был закреплён. Поэтому вопрос выявил реальный contract gap, а не повтор уже
полностью принятого решения.

#### Live behavior до TTL

Сейчас fast path существует только у ingestion:

```text
completed ingest run
  -> ingest.canonical-artifacts.changed
  -> CanonicalArtifactsChangedExportListener
  -> DaemonExportScheduler.nudge()
  -> revision/plan/cadence checks
  -> new completed immutable slice
```

Событие требует ingest `runId` и создаётся только `IngestionService`. Lifecycle
expiry не сможет честно переиспользовать его как собственный durable факт.
Потерю ingest event закрывает periodic export poll, который читает
`artifact_revision`. В текущем production template используется interval
`5m`; для interval cadence `nudge()` отключён, поэтому новый slice может ждать
следующего poll. Quiet-period cadence также не означает немедленный export: она
сохраняет configured debounce/max-cap policy.

Если реализовать только ранее записанное «expiry bump-ит revision», новый slice
в итоге появится через periodic backstop, но latest completed slice некоторое
время останется stale. Это не нарушает неизменяемость старого slice, но без
явного latency contract может противоречить ожиданию быстрого service-local
отзыва данных.

#### Дополнительный correctness риск: time predicate против revision pre-gate

Active membership меняется на самой границе времени, но сохранённый
`artifact_revision` не меняется автоматически от хода часов. Если manual или
scheduled export после deadline сначала выполнит только revision pre-gate, он
может решить, что изменений нет, и не открыть snapshot, хотя
`valid_until > asOf` уже исключил бы rows.

Поэтому недостаточно просто добавить event после physical delete. Каждый export
entry point должен сначала выполнить lifecycle precondition для своего `asOf`:
durably отметить logical membership change затронутых artifacts и только затем
проходить revision pre-gate. Physical archive/delete может продолжаться
bounded batches; свежесть нового slice не должна зависеть от завершения очистки
всех `100 000` rows.

#### Текущая рекомендация

Разделить correctness truth и latency path:

```text
deadline / export precondition
  -> small durable logical-expiry checkpoint
       affected artifact revision/generation + pending export work
  -> active-only snapshot is now revision-visible
  -> producer-neutral CanonicalMembershipChanged(EXPIRATION) hint
       -> coalesced DaemonExportScheduler check

periodic export poll + durable revision --------------------^ backstop

bounded history/archive/delete continues independently
```

1. Lifecycle не вызывает export use case напрямую и не зависит от bootstrap.
   Он фиксирует durable canonical membership fact и публикует producer-neutral
   change hint после commit.
2. Событие не должно называться ingest event и не должно требовать ingest
   `runId`. Оно содержит только durable change/cycle identity, cause и affected
   artifacts; payload не переносит rows.
3. Все daemon/manual export entry points выполняют один lifecycle-current
   precondition до revision pre-gate. Snapshot читает revisions и active rows с
   одним согласованным `asOf`.
4. Один logical expiry cycle даёт не более одного coalesced export trigger на
   затронутый profile, а не trigger на record или SQL batch.
5. Старый immutable slice сохраняется. После успешного attempt появляется новый
   complete slice без expired rows; failure оставляет revision lag и retry, но
   не воскрешает canonical data.
6. Periodic poll остаётся correctness backstop после restart/lost event. Event
   является только ускорителем.
7. В oneshot нет background process: следующий stateful `ioc export` сначала
   выполняет lifecycle precondition и формирует корректный slice. Автоматический
   export во время остановленного процесса невозможен и не добавляется скрыто.

#### Оставшийся product/operations выбор

Нужно определить latency policy для **локального immutable slice** в daemon:

1. **Обычная cadence.** Expiry только nudge-ит существующий scheduler, а новый
   slice появляется по configured interval/quiet-period. Архитектурно проще и
   лучше coalesce-ит churn, но при текущем default latest slice может оставаться
   stale до `5m`.
2. **Urgent withdrawal.** Logical expiry checkpoint запускает отдельный
   coalesced urgent check, который не ждёт обычного ingest quiet-period, но всё
   равно соблюдает single-flight, revision/plan gates и не формирует slice на
   каждую row. Periodic cadence остаётся backstop.

**Рекомендация: вариант 2.** Удаление по TTL является time-sensitive withdrawal,
а не обычным накоплением новых observations. Рекомендуемый target — начать один
export attempt затронутого profile сразу после durable logical-expiry checkpoint
и не позднее тех же `5s` в healthy idle daemon. Completion зависит от размера
slice и измеряется отдельно; remote publish/apply SLA остаётся вне DATA-TTL-01.

Нужно подтвердить:

1. должен ли daemon автоматически создавать новый local immutable slice после
   TTL membership change, не ожидая нового source;
2. принимается ли urgent-withdrawal policy с start target `<=5s`, либо допустима
   существующая configured export cadence (default до `5m`);
3. приемлемо ли для oneshot отсутствие background export: корректный новый
   slice появляется при следующем явном `ioc export`.

**Ответ заказчика.** Перерабатывать export cadence ради TTL пока не требуется.
Записи истекают внутри canonical lifecycle, но expiry сам по себе не создаёт
новый immutable slice. Автоматический export выполняется только при пополнении
canonical storage **новыми данными**.

Под новыми данными в этом contract понимается успешный canonical commit,
добавивший хотя бы одну новую public active row. Простое получение source,
duplicate confirmation, renewal существующей lifecycle и TTL expiry не являются
automatic export trigger. Повторное появление уже expired IOC создаёт новую
lifecycle/public row и поэтому считается новыми данными.

**Осознанные следствия.** Принятый контракт означает:

- latest completed immutable slice может содержать records, которые уже
  expired в canonical DB, и может оставаться таким неограниченно долго, если
  новых данных больше нет;
- когда истекает весь active set и новых данных нет, пустой immutable slice
  автоматически не создаётся;
- mutable dataframe и любой новый canonical read всё равно должны исключить
  expired rows согласно TTL contract;
- при следующем добавлении новых public rows автоматический export формирует
  полный active snapshot: накопившиеся expired records в новый slice уже не
  попадают;
- старые immutable slices сохраняются до обычной retention policy и не
  переписываются;
- DATA-TTL-01 не добавляет lifecycle-specific event, urgent nudge или новый
  export latency SLA.

Решение относится к automatic slice formation. Явная команда `ioc export`
сохраняет текущий revision/plan pre-gate: без новых public rows или plan drift
она может завершиться `SKIPPED` и не обязана создавать slice только потому, что
records истекли. Recovery по-прежнему только завершает уже начатый durable run.

#### Архитектурное следствие: сохраняем текущую revision model

Live storage уже задаёт нужную минимальную семантику: `artifact_revision`
двигается только когда canonical commit вставил хотя бы одну новую public row;
duplicate/provenance-only writes её не меняют. DATA-TTL-01 сохраняет этот
контракт и **не** добавляет отдельный export watermark.

Expiry/removal поэтому не двигает `artifact_revision`. Для точного mutable
dataframe и crash recovery lifecycle использует собственный durable
projection-work/cycle state, уже необходимый TTL reconciliation. Этот state не
читается immutable export scheduler-ом и не становится новым export trigger.

Ingestion event остаётся latency hint. Потерянный event добирает существующий
periodic poll по `artifact_revision`; поскольку expiry её не изменяет, backstop
не создаёт ложный slice. Когда новые rows наконец появляются, обычный commit
двигает revision, а export читает active predicate с единым `asOf`, поэтому один
slice одновременно добавляет новые rows и исключает все накопившиеся expired.

I-20 supersede'ит более ранние предварительные формулировки I-02/I-10/I-11/I-18
только в части обязательного revision bump на expiry. История, logical read
filtering и durable mutable-projection recovery сохраняются.

**Статус:** подтверждено; urgent-withdrawal recommendation отклонена для V1.

### I-21 — external reference review and final validity vocabulary

После завершения интервью отдельно рассмотрены актуальные реализации
[OpenCTI](https://github.com/OpenCTI-Platform/opencti),
[MISP](https://github.com/MISP/MISP), STIX 2.1 и применимость Spring runtime
инструментов. Цель review — проверить, не создаёт ли локальный проект
нестандартную TTL-модель и какие seams нужны для будущего перехода от fixed TTL
к decay policies.

#### OpenCTI: deadline, expiration manager и retention

Текущий OpenCTI
[`expiredManager.js`](https://github.com/OpenCTI-Platform/opencti/blob/master/opencti-platform/opencti-graphql/src/manager/expiredManager.js)
периодически выбирает объекты с прошедшим `valid_until`, берёт cluster-wide
lock и bounded-concurrently обновляет их состояние. Для Indicator он выставляет
`revoked=true`, отключает `x_opencti_detection` и снижает score. Это подтверждает
правильность aggregate manager вместо timer/job на каждый IOC, но сама mutation
model не переносится в DATA-TTL-01.

OpenCTI
[Indicators Lifecycle](https://docs.opencti.io/latest/usage/indicators-lifecycle/)
и [Decay rules](https://docs.opencti.io/latest/administration/decay-rules/)
дают полезные проектные прецеденты:

- абсолютный `valid_until` хранится как факт конкретного lifecycle;
- decay rule выбирается отдельно от storage и определяет validity boundary;
- rule changes не должны молча пересчитывать уже принятые lifecycles;
- reaction points нужны для materialized score, но не для самого факта
  active/inactive;
- expiration manager обслуживает данные, но TTL не превращается в набор
  индивидуальных scheduled jobs.

OpenCTI
[Retention policies](https://docs.opencti.io/latest/administration/retentions/)
отдельно удаляют старые объекты. Для нашего проекта это закрепляет три разных
понятия: validity boundary, expiration/archive reconciliation и последующий
history retention purge.

#### STIX: `valid_until` полезен, `revoked` не эквивалентен expiry

[STIX 2.1](https://docs.oasis-open.org/cti/stix/v2.1/os/stix-v2.1-os.html)
определяет `valid_until` как момент, после которого Indicator больше не должен
считаться валидным, и требует `valid_until > valid_from`. Это соответствует
нашему half-open predicate `valid_until > asOf` и делает `valid_until` более
точным canonical vocabulary, чем generic `expires_at`.

Совпадение термина не означает автоматическую STIX-совместимость:

- canonical artifact row не является STIX Indicator;
- `first_confirmed_at` фиксирует нашу successful canonical transaction и не
  переименовывается в STIX `valid_from`;
- source-supplied validity dates в V1 не принимаются как lifecycle policy;
- передача validity fields наружу требует отдельного versioned mapping contract.

STIX revocation является перманентным для object identity: после `revoked=true`
нельзя выпускать новую версию того же объекта. Наш expired IOC может появиться
снова как новая lifecycle с новым internal/public ID. Поэтому V1 сохраняет
`LifecycleCloseReason.EXPIRED`, а не вводит `revoked` или `detection`. OpenCTI
использует эти поля как собственный platform workflow, не как обязательный
шаблон для нашего storage contract.

#### MISP: будущая policy Strategy, но не V1 read algorithm

MISP
[`DecayingModel.php`](https://github.com/MISP/MISP/blob/2.5/app/Model/DecayingModel.php)
и formula classes отделяют выбор модели от вычисления score. Polynomial model
использует монотонную функцию от base score, elapsed time, lifetime и decay
speed, а threshold определяет состояние `decayed`. Последнее sighting,
`last_seen` или update time задаёт начало нового decay interval.

Официальный
[`misp-decaying-models`](https://github.com/MISP/misp-decaying-models)
хранит versioned JSON models: например, phishing model имеет существенно
меньший lifetime, чем NIDS model. Это подтверждает будущую возможность
type/risk-sensitive policy, но не меняет V1 requirement одного fixed TTL.

Для монотонной decay curve policy может аналитически вычислить время достижения
threshold и сохранить один абсолютный `validUntil`. Поэтому будущая замена
fixed policy не требует periodic UPDATE score каждой строки:

```text
accepted confirmation
  -> RecordValidityPolicy selects/evaluates model
  -> ValidityDecision(validUntil)
  -> atomic canonical commit stores absolute boundary
```

MISP API `excludeDecayed` вычисляет decay score в per-attribute processing и
отбрасывает decayed results после чтения batches. Это полезный optional query
feature CTI-платформы, но не подходит как обязательный canonical read predicate
для нашего expected scale: формула, tags/sightings и application filtering
ухудшили бы index selectivity и latency. V1 и будущие policy-backed reads
сохраняют SQL predicate по persisted `valid_until`.

Существующую public dataframe column `score` запрещено переиспользовать под
decay score: её контракт независим от MISP score, OpenCTI `x_opencti_score` и
STIX confidence. Если score lifecycle когда-либо станет consumer requirement,
он получит отдельную internal модель и versioned projection mapping.

#### Runtime/framework disposition

| Option | V1 disposition | Reason |
|---|---|---|
| Admission-gated `SmartLifecycle` + `ScheduledExecutorService` | use | Остаётся inert до общего canonical-data admission, затем поддерживает nearest-deadline one-shot, reschedule/coalescing и deterministic executor tests |
| Spring `@Scheduled` | reject | Годится для fixed periodic trigger, но не выражает dynamic nearest deadline и admission ordering; второй scheduling style не даёт выигрыша |
| ShedLock | reject | Решает только concurrent invocation нескольких nodes, пропускает contender execution и опирается на time-based lock; V1 имеет один daemon и SQLite transaction truth |
| Spring Batch | reject for V1 | Chunk/restart/partition infrastructure дублирует bounded keyset reconciler и durable cycle state при текущем `100k` envelope |

OpenCTI cluster lock не является аргументом в пользу ShedLock для текущего
deployment: в OpenCTI один manager стартует в каждом API process. Если наш
проект перейдёт к multi-process deployment, потребуется общий design writer
ownership, fencing, activation leader и projection ownership; один scheduler
lock эту задачу не закрывает.

Spring Batch следует пересмотреть только при измеренном переходе к
millions/tens-of-millions, многочасовым restartable jobs, multi-step
reclassification или partitioned/multi-writer storage. До этого основными
ограничениями вероятнее станут SQLite single writer и стоимость полной CSV
projection, а не отсутствие batch framework.

#### Принятый implementation vocabulary

| Concept | V1 name |
|---|---|
| Business strategy | `RecordValidityPolicy` |
| Fixed implementation | `FixedRecordValidityPolicy` |
| Policy result | `ValidityDecision(validUntil)` |
| Durable column | `_valid_until_epoch_ms` |
| Active predicate | `_valid_until_epoch_ms > :as_of_epoch_ms` |
| Due predicate | `_valid_until_epoch_ms <= :as_of_epoch_ms` |
| Transition/process | expiration / expiry reconciliation |
| Historical purge | retention cleanup |
| Time-based close reason | `LifecycleCloseReason.EXPIRED` |

`RecordValidityPolicy` остаётся единственной малой Strategy в P1. Registry,
formula DSL, selector hierarchy и model persistence не создаются без реальной
второй policy. При их появлении selection может использовать IOC type, risk и
provenance summary как входные facts, но lifecycle и сохранённый `validUntil`
всё равно принадлежат canonical record, а не source или IOC taxonomy.

**Статус:** external review завершён; vocabulary и framework disposition
приняты как уточнение I-01..I-20 и внесены в architecture/ADR/implementation
documents.

### I-22 — переоткрытие внешнего `id` как reusable export slot

**Причина переоткрытия.** После завершения P6 заказчик уточнил downstream
contract поля `id` в export-slice. В I-03/I-03A мы обсуждали это поле как
долгоживущую service-owned identity и на таком основании приняли запрет reuse.
Это была неверная интерпретация назначения поля. Историю прежнего решения не
удаляем, но настоящий раздел supersede'ит I-03/I-03A и все их следствия только
для внешней export-колонки `id`.

**Исправленная формулировка.** Внешний `id` — чистое поле export projection. На
стороне приложения оно называется `export_slot` и не используется как
canonical primary key, lifecycle identity, dedup key или provenance identity.

Требуемая последовательность:

```text
slice S1: A=1, B=2, C=3
A и B истекли
slice S2 после допустимого new-data trigger: D=1, C=3
slice S3 после следующей новой записи: D=1, E=2, C=3
```

`C` сохраняет слот `3`, пока остаётся active. Никакой dense renumbering после
expiry нет. Новая lifecycle занимает минимальный свободный положительный слот;
gaps допустимы, пока их не заполнят новые records. Старый immutable slice не
изменяется, поэтому один slot может означать разные lifecycle в разных slices.

**Что не меняется.** Internal row ID и `_lifecycle_id` не переиспользуются.
Reappearance после expiry всё ещё является новой lifecycle. Source-owned ID
остаётся namespaced business/provenance field. Expiry по-прежнему не создаёт
immutable slice; slot reconciliation выполняется только внутри export, который
уже разрешён поступлением новых canonical data.

**Архитектурная рекомендация.** Export capability получает отдельный durable
registry в dataframe DB, scoped по `(profile, artifact)`. Reconciliation:

1. освобождает assignments отсутствующих active lifecycle;
2. сохраняет surviving assignments без изменений;
3. выдаёт новым lifecycle минимальные свободные slots;
4. при отсутствии holes использует durable high-water state;
5. проверяет единую canonical generation перед публикацией snapshot.

Registry не переносится в service DB, чтобы не создавать cross-DB consistency
gap с canonical active set. TTL/history path не знает об export slots. Новый
Maven module, библиотека, event или per-record job не нужны. Точный проект,
migration и acceptance matrix находятся в
[export-slot-correction.md](export-slot-correction.md); архитектурная поправка —
в [ADR-0021](../../../ADR/0021-stable-reusable-export-slots.md).

**Статус:** business rule принят; DATA-TTL-01 переоткрыт для P7, реализация и
evidence отсутствуют.

## 5. Emerging model

```text
accepted observation
        |
        v
canonical record lifecycle ----> valid_until / active-at(asOf)
        |                                  |
        +---- provenance evidence          +---- current projections/export
        |
        +---- record-validity policy (fixed TTL in V1; extensible later)
        |
        +---- fingerprinted source receipt (bounded duplicate fast path)
```

Soft expiry в active table отвергнут. Разделение active и historical lifecycle,
bounded expiry processing, deadline-aware scheduler и startup recovery contract
приняты. Component/schema decomposition, bounded duplicate receipt и осознанный
fresh-install TTL `12h` подтверждены в I-18. I-20 фиксирует, что expiry меняет
canonical membership и mutable projection, но не инициирует immutable export;
следующий automatic slice появляется только после добавления новых canonical
rows. I-22 отделяет внешний `id` от canonical identity: surviving lifecycle
сохраняют stable sparse `export_slot`, а slots ушедших lifecycle доступны новым
records только при следующем eligible export.

## 6. Следующий этап

1. Сохранить P0–P6 evidence как доказательство TTL lifecycle и характеристику
   прежней ID-модели.
2. Выполнить P7 из [implementation-plan.md](implementation-plan.md): отделить
   reusable export slots, обновить published docs и повторить release evidence.
