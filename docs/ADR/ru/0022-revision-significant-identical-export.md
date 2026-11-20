> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0022-revision-significant-identical-export.md).

<a id="0022--revision-significant-identical-export-candidates"></a>
# 0022 — Revision-significant export candidates с одинаковым содержимым

<a id="status"></a>
## Статус

**Принято 2026-08-22. Реализовано в candidate 0.3.0; final release qualification
ещё не завершена.**

Этот ADR узко заменяет ADR-0012 там, где совпадение profile bytes и `plan_hash`
считалось достаточным для удаления materialized export candidate. ADR-0012
остаётся в силе для snapshot consistency, формирования immutable slice,
export saga, recovery, retention и остального export behavior.

<a id="context"></a>
## Контекст

ADR-0020 ввёл истекающие canonical record lifecycles. Наблюдение после expiry
создаёт новый active lifecycle и продвигает существующую insert-driven
`artifact_revision`. ADR-0021 может назначить этому lifecycle reusable export slot.

При этом более поздний active snapshot может иметь ровно те же public CSV
bytes, что старый slice. Например, все expired IOCs возвращаются с теми же
values и получают те же освободившиеся export slots. Прежнее post-materialization
rule сравнивало только content hashes и `plan_hash`, помечало candidate
`SKIPPED`, сохраняло старый slice ID и не публиковало `SliceCompleted`.

Этот outcome смешивает projection equivalence с delivery equivalence.
Оператор предоставил новый source, canonical storage принял новые public
lifecycles, и downstream delivery должна получить новое immutable occurrence,
даже если его bytes совпадают с историческим occurrence.

<a id="decision"></a>
## Решение

<a id="1-preserve-the-revision-pre-gate"></a>
### 1. Сохранить revision pre-gate

Существующий pre-gate `artifact_revision + ExportProgress` не меняется.
Expiry, renewal и confirmation active lifecycle не продвигают revision и
поэтому не создают export. Canonical commit, вставляющий хотя бы один новый
public lifecycle, продвигает её; возвращение после TTL expiry — такой commit.

В Artifact Emission не добавляются lifecycle-specific export event, history
query или TTL dependency.

<a id="2-make-revision-equality-part-of-redundant-candidate-detection"></a>
### 2. Включить равенство revisions в поиск избыточного candidate

Materialized candidate может стать `SKIPPED` только когда у каждого artifact
всё перечисленное совпадает с его durable `ExportProgress`:

- membership artifact в profile;
- export `plan_hash`;
- точный SHA-256 public content;
- covered `artifact_revision`.

Совпадающие bytes с большей covered revision не избыточны. Run проходит обычный
путь `STAGED -> AVAILABLE -> COMPLETED`, сохраняет candidate slice как новый
progress authority и выдаёт `SliceCompleted` после durable completion checkpoint.

Это profile-level decision: один revision-significant artifact делает
публикуемым весь complete profile slice.

<a id="3-apply-the-same-pure-policy-during-recovery"></a>
### 3. Применять ту же pure policy при recovery

`ExportService` и `ExportRunRecoveryService` используют один framework-free
change detector. Recovery оценивает revisions, уже сохранённые в manifest,
а не перечитывает canonical storage. Поэтому crash после staging не может
молча превратить обязательную новую delivery в `SKIPPED`.

Recovery всё ещё может удалить candidate, чьи plan, revisions и bytes полностью
совпадают с completed progress. Такое staging представляет то же attempted
public occurrence, а не более поздний accepted lifecycle.

<a id="4-keep-events-as-latency-hints"></a>
### 4. Сохранить events как latency hints

Не вводятся новый control-event type, broker, outbox, Maven module или Java
library. `SliceCompleted` продолжает выдаваться только после durable
`COMPLETED`; publish ledger и periodic reconcile остаются delivery truth
и correctness backstop при потере in-process event.

<a id="consequences"></a>
## Последствия

Положительные последствия:

- Возврат source после TTL expiry создаёт новые export и downstream delivery,
  даже при byte-identical public CSV относительно старого slice.
- Active confirmations, renewals и одна expiry сохраняют принятое no-export behavior.
- Forward execution и crash recovery не могут расходиться.
- Lifecycle/history storage остаётся независимым от export policy.

Стоимость:

- Immutable storage и remote delivery могут содержать несколько byte-identical
  slices с разными run/slice identities.
- Content hashes остаются evidence целостности и projection equivalence,
  но больше не являются полной business-occurrence identity.
- При разборе export decisions оператор должен смотреть terminal status
  вместе с covered revision, а не только hashes.

<a id="rejected-alternatives"></a>
## Отклонённые альтернативы

- **Content hash как единственный post-gate:** теряет законные более поздние
  deliveries и скрывает accepted new lifecycles от оператора.
- **Продвигать revision на expiry:** создаёт withdrawal-driven slices вопреки
  принятому TTL trigger contract.
- **Читать history или TTL state из export:** связывает bounded contexts и
  делает recovery зависимым от mutable current state.
- **Специальный reappearance event:** дублирует durable revision signal и всё
  равно требует reconcile correctness.
- **Добавить nonce в CSV bytes:** нарушает deterministic public projections
  ради компенсации ошибки coordination policy.

<a id="verification"></a>
## Проверка

- Detector test: равные plan/hash с равной revision избыточны, большая revision
  допускает publication.
- Forward export test: одинаковые bytes с большей revision завершают новый
  slice и выдают `SliceCompleted`.
- Recovery test: тот же candidate завершается и выдаёт event после crash
  без canonical reread.
- Recovery test: staging с равными plan/hash/revision безопасно остаётся skippable.
- Packaged stand: дать source истечь, принять identical later observation,
  проверить новый slice и publish-ledger delivery.
