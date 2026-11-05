# Жизненный цикл канонических записей

[English version](../canonical-record-lifecycle.md)

Жизненный цикл оставляет в активном хранилище только актуальные IOC-записи,
сохраняя ограниченную историческую информацию. Он начинается после принятой
канонической транзакции и заканчивается на сохранённой границе `valid_until`.
Он не моделирует отзыв источника, подтверждение downstream firewall, STIX
revocation или отдельную задачу scheduler на каждую запись.

## Поток выполнения

```text
accepted observation
  -> validity policy returns absolute valid_until
  -> one canonical SQLite transaction inserts, renews or closes-and-recreates
  -> active-only mutable projection

nearest deadline hint OR 5s periodic deadline refresh
  -> one aggregate nearest-deadline timer
  -> only a due deadline starts reconciliation
  -> indexed bounded archive/delete batches
  -> durable projection generation
  -> active-only mutable projection convergence

independent hourly retention scheduler
  -> indexed bounded history/receipt deletion
```

События лишь сокращают задержку. Сроки в SQLite, generations проекций и последний
checkpoint reconciliation обеспечивают корректность после потери событий или
restart. Пустой deadline refresh только читает и не создаёт цикл reconciliation.

## Подтверждение и активация

Поддерживаются режимы `disabled` и `fixed`. Classpath default — `disabled`;
production template для новой установки выбирает `fixed` с `fixed-ttl` равным
`12h`. Фиксированный TTL обязан быть положительным; ноль не запрашивает миграцию
или истечение. Срок равен времени подтверждения плюс TTL и хранится внутри как
абсолютное UTC epoch milliseconds в `_valid_until_epoch_ms`.

Срок сразу управляет логической видимостью на зафиксированном чтением `asOf`.
Физическое архивирование/удаление и замена CSV сходятся позже. Просроченная
строка поэтому может ещё занимать страницы SQLite или находиться в прежнем CSV,
не являясь активной в новом каноническом чтении. Snapshot сохраняет свою границу
на всю операцию; длительное чтение не получает новое UTC для каждой строки.

Подтверждение после processing checkpoint либо продлевает активную lifecycle,
либо закрывает истёкшую и создаёт новую. Повтор того же зафиксированного
наблюдения возвращает сохранённый результат, а не назначает новый TTL.
Изменение `fixed-ttl` влияет на последующие подтверждения; оно не переписывает
сохранённые сроки и не продлевает строки, отсутствующие в новом наблюдении.

Активация проходит `DISABLED_COMPATIBLE -> ACTIVATING -> ACTIVE`. При наличии
legacy rows `existing-records: reject` отвергает её; явный `expire` архивирует
и удаляет их ограниченными транзакциями. Зафиксированные batches переживают
отказ, restart продолжает оставшуюся работу. Сходимость проекций предшествует
завершению. После начала активации возврат в `disabled` запрещён.
`existing-records` управляет этим переходом, а не обычным истечением активных
записей. Процедура принадлежит [операторскому guide](../../guides/ru/canonical-record-lifecycle.md).

## Владение изменяемыми проекциями

`GenerationOwnedArtifactProjection` — общий владелец каждого артефакта для
oneshot, ingest, run recovery, activation и сходимости после import/expiry.
Bootstrap предоставляет его как `ArtifactProjection`; низкоуровневый CSV
installer не является bean. Справедливая прерываемая блокировка артефакта
охватывает snapshot/build/install/ack. Построение не удерживает canonical writer
admission; порядок блокировок — владелец артефакта, затем короткий database
admission. Разные артефакты могут продвигаться независимо.

Упорядоченный stream возвращает row count и generation из одной SQLite read
transaction. Atomic replacement устанавливает этот охват, после чего
монотонное защищённое обновление подтверждает его. Мутация g1 → g2 во время
построения g1 оставляет g2 ожидающей; g1 никогда не подтверждает g2. Запуски
возвращаются только после acknowledgement установленного охвата. Один проход
convergence может сообщить и выполненную работу, и более новую ожидающую.

Ожидающие вызовы переиспользуют один успешный результат на артефакт, только
если он соответствует текущему сохранённому охвату. Warning counts сохраняются
при reuse с корреляцией конкретного caller; convergence также испускает
предупреждения проекции. Generation zero не отслеживается и всегда строится
заново последовательно. Владелец не создаёт executor; ожидание прерываемо,
streaming проверяет отмену на каждой строке и перед rename.

Отказ до rename сохраняет прежний output и удаляет временный. Отказ после
rename или во время acknowledgement оставляет работу ожидающей; startup или
действующий periodic backstop исправляет её без новой канонической мутации.
CSV и SQLite не образуют общей транзакции. Один service process должен владеть
своими mutable paths; внешняя порча/удаление уже подтверждённых файлов требует
операторского восстановления проекции. Владение immutable slices не меняется.
Решение: [ADR 0035](../../ADR/0035-generation-owned-mutable-projections.md).

## Границы

- `core/ioc-application` владеет независимыми от storage lifecycle values,
  фиксированной validity strategy, допуском, активацией, reconciliation,
  retention use cases и их портами.
- `adapter-store-jdbc` владеет представлением SQLite, атомарным SQL,
  типизированной history, receipts, монотонными allocators, active reads и
  индексированной очисткой.
- `bootstrap/ioc-app` связывает строгую конфигурацию, внедряет UTC и monotonic
  clocks, задаёт порядок pre-admission recovery и владеет общим scheduler и
  health indicator.
- CSV остаётся projection adapter. Lifecycle не делает CSV или immutable export
  вторым источником истины.

Lifecycle state находится в dataframe DB, поскольку подтверждение, active
membership и перенос в history должны участвовать в общей бизнес-транзакции.
Service DB по-прежнему хранит только ingestion/export/sync coordination.

Владельцы транзакций явно откатывают неуспешную работу, включая фатальные пути
`Error`, до восстановления connection mode. Возврат auto-commit никогда не
публикует частичные канонические строки без receipt. Cleanup сохраняет исходный
отказ. Committed replay использует сохранённую идентичность наблюдения и может
обойтись без повторного открытия подготовки; эта идентичность относится к
одному pinned ingestion event и не переиспользуется для другого payload.

## Инварианты

1. **Срок принадлежит одной lifecycle канонической строки.** Provenance
   подтверждает строку, но не владеет TTL и не планирует его независимо.
2. **Активный интервал полуоткрытый.** Строка активна только при
   `valid_until > asOf`; равенство уже означает истечение на всех read surfaces.
3. **Подтверждение принадлежит commit.** Разбор или подготовка не продлевают
   срок. Одно effective UTC под write ownership решает, продлить запись или
   закрыть и создать новую.
4. **Каноническая идентичность не переиспользуется.** Повторное появление создаёт
   новые lifecycle и canonical row ID. Сохранённые внутренние allocators
   переживают удаление active/history. Immutable export `id` — отдельный
   переиспользуемый слот по [ADR-0021](../../ADR/0021-stable-reusable-export-slots.md).
5. **Истечение не является вставкой.** Оно не увеличивает `artifact_revision`
   и само не вызывает immutable export. Последующий экспорт, допущенный
   изменениями публичных данных или плана, читает текущий активный состав.
   Изменение публичных полей managed import может увеличить revision без вставки.
6. **Публичные схемы стабильны.** Внутренние lifecycle columns исключены из
   CSV/export. Lifecycle не заполняет бизнес-поля `time_first_seen` и
   `time_last_seen`; поставляемое отображение оставляет их `NULL`. Изменение
   семантики экспортируемых дат требует своего mapping и consumer contract.
7. **Работа ограничена.** Индексы deadline и retention питают SQLite transactions
   фиксированного размера; нет timer, thread или job на каждый IOC.

## Отказы и восстановление

Safe UTC читается после получения write ownership и snapshot сохранённого
high-water. Read-only inspection также сначала читает snapshot, затем UTC.
Ожидание пула или владения не должно превращать старую выборку времени в
кажущийся откат часов. Реальное обратное смещение и длительное ограничение
по-прежнему приводят к отказу в пределах настроенной политики.

| Ситуация | Поведение | Основание корректности |
|---|---|---|
| Crash во время истечения | Прерванный checkpoint получает failure при admission, due rows обрабатываются повторно | активная строка и reconcile state |
| Отказ проекции после archive | Active reads уже исключают строку; сохранённая generation ждёт сходимости проекции | generation в dataframe DB |
| Потеря deadline/event hint | Nearest-deadline lookup и пятисекундный refresh находят работу без пустой записи | индексированный абсолютный срок |
| Малый откат UTC | Effective time ограничивается сохранённым high-water; health `DEGRADED` | lifecycle clock control |
| Существенный или длительный откат | Stateful admission/readiness отвергается с `DOWN` | lifecycle clock policy |
| Upgrade с legacy rows | Совместимый старт не разрушает данные; явная возобновляемая activation архивирует их до intake/export | activation state и progress |
| Отказ history retention | Active lifecycle остаётся корректной; следующий ограниченный проход повторит очистку | сохранённая история и scheduler |

History, компактные source evidence и complete confirmation receipts имеют
ограниченную retention; defaults — `30d`. Это не постоянный архив аудита.
Receipt reuse требует совместимых source/policy pins и полного receipt с
неистёкшим сроком. Очистка не сбрасывает canonical/lifecycle ID allocators.
Исторические immutable slices имеют отдельную retention и delivery guard.

### Полномочия Native reservation

Диапазоны row/lifecycle ID независимо фиксируются в authoritative dataframe DB
до Native canonical mutation. Они используют общий writer admission и сгорают
при отказе или неиспользованном worst-case reservation. Mutation, provenance,
lifecycle и receipt затем фиксируются вместе в транзакции своего артефакта;
альтернативной mutation DB или effect-journal backend нет. Private source/row
preparation описана в [обработке](processing.md).

## Расширение политики

Будущий type/risk/source-aware срок расширяет `RecordValidityPolicy` и его
configuration/fingerprint mapping. Persistence по-прежнему получает абсолютный
`valid_until`, поэтому storage, active predicates и reconciliation не зависят
от формулы политики. Экспорт срока или отображение lifecycle dates в публичные
бизнес-поля требует отдельного версионируемого consumer contract.

## Источники истины

- Решения: [ADR-0020](../../ADR/0020-canonical-record-expiration-lifecycle.md),
  [ADR-0021](../../ADR/0021-stable-reusable-export-slots.md),
  [ADR-0022](../../ADR/0022-revision-significant-identical-export.md) и
  [ADR-0023](../../ADR/0023-bounded-lifecycle-reconciliation-runtime.md).
- Application contracts: [пакет lifecycle](../../../core/ioc-application/src/main/java/com/iocextractor/application/artifact/lifecycle),
  [тесты runtime services](../../../core/ioc-application/src/test/java/com/iocextractor/application/artifact/lifecycle/LifecycleRuntimeServicesTest.java).
- SQLite schema и SQL: dataframe migration v4 в `adapter-store-jdbc` для
  lifecycle, v5 для export slots, v6 для bounded reconcile checkpoint и
  тесты адаптера.
- Часы и active semantics: [JdbcLifecycleRuntimeIT](../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcLifecycleRuntimeIT.java),
  [привязка lifecycle TCK](../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalRecordLifecycleContractIT.java).
- Владение проекцией: [GenerationOwnedArtifactProjectionTest](../../../core/ioc-application/src/test/java/com/iocextractor/application/artifact/lifecycle/GenerationOwnedArtifactProjectionTest.java),
  [MutableProjectionOwnershipIT](../../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/MutableProjectionOwnershipIT.java).
- Production composition/defaults: [AppConfig](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java),
  [classpath configuration](../../../bootstrap/ioc-app/src/main/resources/application.yml) и
  [production template](../../../packaging/templates/application.yml).
- Операторское поведение: [гайд lifecycle](../../guides/ru/canonical-record-lifecycle.md).
- Runtime harness: `tools/dev/lifecycle-smoke.sh`; его текущее ограничение
  terminal filename записано как [TOOL-10](../../KNOWN-ISSUES.md), а не принятая smoke evidence.

## Когда обновлять документ

- меняются validity owner, active predicate или граница канонической транзакции;
- меняются activation, expiry, retention или clock recovery semantics;
- истечение начинает влиять на artifact revision или запуск immutable export;
- добавляется новая поддерживаемая validity policy или downstream lifecycle contract.

## Связанные документы

- Механика хранения: [storage.md](storage.md).
- Поведение экспорта: [artifact-export.md](artifact-export.md).
- Точка ingestion confirmation: [ingestion.md](../ingestion.md).
- Карта модулей: [MODULARIZATION.md](../../ru/MODULARIZATION.md).
