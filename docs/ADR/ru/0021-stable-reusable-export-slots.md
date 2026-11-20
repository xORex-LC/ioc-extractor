> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0021-stable-reusable-export-slots.md).

<a id="0021--stable-reusable-export-slots"></a>
# 0021 — Стабильные переиспользуемые export slots

<a id="status"></a>
## Статус

**Принято 2026-08-19. Реализовано в P7 candidate; packaged release qualification
ещё не завершена.**

Этот ADR узко заменяет раздел 4 «Public and source-owned identity»
[ADR-0020](../0020-canonical-record-expiration-lifecycle.md). ADR-0020 остаётся
в силе для record validity, expiration, history, retention, active reads,
возвращения как нового lifecycle и всех остальных lifecycle decisions.

<a id="context"></a>
## Контекст

Существующая artifact column `id` смешивает два разных вопроса:

- durable technical identity lifecycle канонической record;
- позиционный identifier, нужный downstream consumers export slice.

ADR-0020 рассматривал экспортируемое value как service-owned public identity,
которую нельзя переиспользовать. Это не соответствует consumer contract.
Экспортируемое value — projection slot. Он должен оставаться стабильным,
пока record active, но становиться доступным более поздней record после
истечения прежней и её исчезновения из active exports.

Например, у active records slots `A=1`, `B=2`, `C=3`; затем `A` и `B` истекают,
приходит `D`, и следующий slice содержит `D=1`, `C=3`. Если позднее приходит
`E`, пока `C` активна, она получает slot `2`; `C` остаётся `3`. Существующие
active rows никогда не перенумеровываются просто для закрытия пропуска.

Прежний immutable slice с `A=1` не меняется. Поэтому slot имеет смысл только
вместе со своим export slice и artifact/profile scope; это не глобально
стабильная record identity.

<a id="decision"></a>
## Решение

<a id="1-separate-canonical-identity-from-export-position"></a>
### 1. Отделить canonical identity от export position

Application concept называется **export slot**. Существующие external schemas
могут продолжать выдавать column как `id` для совместимости, но application и
storage code не должны использовать это value как canonical identity,
lifecycle identity, deduplication identity, provenance identity или foreign key
вне export-slot registry.

Canonical technical row identity и `_lifecycle_id` остаются non-reusable.
Возвращение после expiration по-прежнему создаёт новый lifecycle. Source-owned
IDs, если присутствуют, остаются явно namespaced business/provenance fields,
а не export slots.

В export-slot allocation участвуют только настроенные artifacts, external
projection которых содержит column `id`. Artifacts без неё не получают
синтетических slots.

<a id="2-use-stable-sparse-reusable-allocation"></a>
### 2. Стабильное разреженное переиспользуемое выделение

Для каждого namespace `(profile, artifact)`:

1. Active lifecycle с назначенным slot сохраняет его в каждом последующем slice,
   пока остаётся active.
2. Если lifecycle отсутствует в active set при export reconciliation, его slot освобождается.
3. Впервые экспортируемый lifecycle получает наименьший свободный positive slot.
4. Если свободного slot нет, allocation использует и продвигает high-water mark namespace.
5. Существующие active assignments никогда не уплотняются и не перенумеровываются.
6. Пропуски допустимы и остаются до использования новыми lifecycles.
7. Если в одном reconciliation несколько новых lifecycles, детерминированный
   порядок назначения — по возрастанию internal lifecycle identity.

Таким образом, allocator не является ни монотонной public-ID sequence,
ни плотной нумерацией rows `1..N`.

<a id="3-persist-the-registry-beside-canonical-dataframe-truth"></a>
### 3. Хранить registry рядом с canonical dataframe truth

JDBC adapter будет сохранять export-owned state в dataframe SQLite DB,
а не service DB. Это устраняет cross-database commit gap между active canonical
set и slot registry, сохраняя ownership в export application capability.

Целевая logical schema:

```text
export_slot_assignment(
  profile, artifact, lifecycle_id, slot, assigned_at_epoch_ms,
  PRIMARY KEY(profile, artifact, lifecycle_id),
  UNIQUE(profile, artifact, slot)
)

export_slot_free(
  profile, artifact, slot, released_at_epoch_ms,
  PRIMARY KEY(profile, artifact, slot)
)

export_slot_state(
  profile, artifact, next_slot, source_generation, updated_at_epoch_ms,
  PRIMARY KEY(profile, artifact)
)
```

Физическая migration может использовать naming conventions проекта или
эквивалентное normalized representation, но обязана сохранять оба uniqueness
constraints и indexed smallest-free-slot lookup.

<a id="4-reconcile-slots-only-as-part-of-an-eligible-export"></a>
### 4. Сверять slots только как часть допустимого export

Expiration сама не создаёт immutable slice и напрямую не выделяет и не
освобождает export slots. Это сохраняет принятую trigger policy ADR-0020:
automatic export происходит только после добавления новых canonical data
через ingestion.

Когда export допустим, export use case сверяет registry с одной active-set
generation до открытия immutable slice:

1. Освободить assignments неактивных lifecycles.
2. Сохранить каждое surviving assignment без изменений.
3. Выделить освобождённые slots новым active lifecycles, начиная с наименьших.
4. Выделить остаток из `next_slot`.
5. Записать canonical source generation.
6. Читать slice из consistent active snapshot и материализовать
   `export_slot AS id`, упорядочивая по slot там, где существующий artifact
   contract требует ID ordering.

Если canonical membership/generation меняется между reconciliation и snapshot
boundary, export повторяет попытку вместо публикации смешанного mapping.
Существующие export single-flight/saga и immutable-slice completion marker
остаются publication authority.

JDBC implementation должна использовать bounded set-based operations или
staged batch tables. Per-row round trips и полная материализация active set
в JVM недопустимы для reference envelope 100k. Unique constraints — последняя
защита от concurrency, а не allocation algorithm.

<a id="5-migrate-without-renumbering-the-current-active-projection"></a>
### 5. Мигрировать без перенумерации текущей active projection

При первой activation существующие active lifecycles получают начальные slots
из текущих экспортируемых `id`, где mapping однозначен. Это сохраняет текущий
active consumer mapping при upgrade. Allocator high-water mark начинается выше
максимального seeded slot; holes, не принадлежащие active lifecycle, становятся
free slots.

Дублирующийся или ambiguous seed — activation error; migration не должна молча
перенумеровывать rows. Export-slot policy/version входит в export plan или
schema fingerprint. Она не меняет canonical row identity и сама по себе
не требует artifact-identity epoch bump.

<a id="6-keep-the-change-inside-existing-boundaries"></a>
### 6. Сохранить существующие границы

Не вводятся новый Maven module, standalone Java library, control event,
per-record timer или scheduler framework.

- `core/ioc-application` владеет существующим контрактом `SnapshotSliceReader`
  и slot-policy fingerprint без JDBC/Spring types; второй port или export use
  case не вводится, поскольку slot resolution — атомарная часть открытия snapshot.
- `adapter-store-jdbc` владеет SQLite schema, migration, allocation и
  consistent-snapshot implementation.
- `adapter-sink-csv` продолжает сериализовать настроенную external column `id`,
  теперь получаемую из export slot, а не canonical row identity.
- `bootstrap/ioc-app` связывает существующие границы.

Expiry/history/provenance writers не зависят от export slots.

<a id="consequences"></a>
## Последствия

Положительные последствия:

- downstream IDs стабильны для всех ещё активных records;
- expired records перестают занимать ограниченные/упорядоченные consumer slots;
- canonical lifecycle identity остаётся корректной и non-reusable;
- старые immutable slices остаются проверяемыми без ограничения последующих;
- allocation policy изолирована за export-owned port.

Стоимость и риски:

- export теперь владеет durable projection state и reconciliation step;
- slot может обозначать разные lifecycles в разных slices, поэтому logs и
  diagnostics обязаны включать slice и artifact/profile context;
- upgrade требует строгих seeding/collision checks;
- allocation и snapshot generation должны быть race-safe и проверены нагрузкой.

<a id="rejected-alternatives"></a>
## Отклонённые альтернативы

- **Монотонные непереиспользуемые exported IDs:** нарушают требование consumer
  об освобождении expired positions.
- **Плотный `ROW_NUMBER()` в каждом slice:** перенумеровывает surviving rows
  после любого expiry.
- **`MAX(active.id)+1`:** допускает collision, не умеет безопасно заполнять
  наименьший hole и связывает projection с canonical storage identity.
- **Освобождение slots в expiry transaction:** связывает lifecycle maintenance
  с projection concern и меняет state даже без запроса export.
- **Registry только в service DB:** создаёт cross-database consistency gap
  с dataframe truth.
- **Job/event на каждую record:** TTL — data state; export allocator сверяет
  его только когда export уже допустим.

<a id="required-verification-before-acceptance"></a>
## Обязательная проверка до принятия

- Survivor stability, smallest-hole reuse, детерминированное выделение
  нескольких holes, отсутствие compaction, restart recovery, rollback и
  concurrent ingest/export tests.
- Upgrade seeding с пропусками и fail-closed duplicate/collision cases.
- Immutable-slice proof, что исторический `slot=1` и более поздний `slot=1`
  могут указывать на разные lifecycles без изменения прежнего slice.
- Artifacts без external `id` не затронуты.
- 100k profile с SQLite query plans и bounded heap/transaction evidence.
- Обновлённая опубликованная capability, storage, export и operator documentation.
- Повтор packaged fresh-install и v0.2.0 upgrade stand.
